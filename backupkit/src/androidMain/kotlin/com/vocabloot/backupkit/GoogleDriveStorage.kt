package com.vocabloot.backupkit

import android.content.Context
import com.vocabloot.backupkit.internal.DriveRestClient
import com.vocabloot.backupkit.internal.LocalFiles
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Google Drive app-data transport. Flat folder; paths are used verbatim as Drive file names.
 * Single uploads are capped at 5 MB (Drive's multipart limit).
 */
public class GoogleDriveStorage(
    context: Context,
    private val tokenProvider: DriveTokenProvider = PlayServicesTokenProvider(context),
    httpClient: HttpClient = BackupKitHttp.default(),
) : CloudStorage {

    override val provider: CloudProvider = CloudProvider.GoogleDrive

    private val rest = DriveRestClient(
        client = httpClient,
        tokenProvider = { requireToken() },
        onUnauthorized = { token -> tokenProvider.invalidate(token) },
    )

    override suspend fun availability(): CloudAvailability = when (tokenProvider.accessToken()) {
        is DriveToken.Value -> CloudAvailability.Available
        DriveToken.NeedsConsent -> CloudAvailability.NeedsConsent
        is DriveToken.Failed -> CloudAvailability.NoAccount
    }

    /** No account is read; a sync engine keys identity off the marker file's Drive id instead. */
    override suspend fun identityKey(): String? = null

    override suspend fun list(): List<RemoteFile> = withContext(Dispatchers.IO) {
        val byName = rest.list().groupBy { it.name }
        buildList {
            for ((name, dupes) in byName) {
                val newest = dupes.maxByOrNull { it.modifiedTime ?: "" } ?: continue
                // Interrupted creates can leave duplicates; keep the newest, drop the rest best-effort.
                dupes.filter { it.id != newest.id }.forEach { dupe ->
                    runCatching { rest.delete(dupe.id) }.onFailure { logW(TAG, it) { "dedupe delete failed for $name" } }
                }
                add(RemoteFile(path = name, size = newest.size?.toLongOrNull() ?: -1L, remoteId = newest.id))
            }
        }
    }

    override suspend fun exists(path: String): Boolean = resolveId(path) != null

    override suspend fun writeBytes(path: String, bytes: ByteArray, mimeType: String, existingRemoteId: String?): String? =
        withContext(Dispatchers.IO) {
            if (bytes.size > MAX_SINGLE_UPLOAD_BYTES) {
                throw CloudStorageException(CloudError.Transport, "$path is ${bytes.size} bytes; above the 5 MB single-upload limit")
            }
            if (existingRemoteId != null) {
                rest.update(fileId = existingRemoteId, bytes = bytes, mimeType = mimeType)
                existingRemoteId
            } else {
                rest.create(name = path, bytes = bytes, mimeType = mimeType)
            }
        }

    override suspend fun writeFile(path: String, localPath: String, mimeType: String, existingRemoteId: String?): String? {
        val bytes = withContext(Dispatchers.IO) { LocalFiles.readAllBytes(localPath) }
            ?: throw CloudStorageException(CloudError.NotFound, "local file missing: $localPath")
        return writeBytes(path = path, bytes = bytes, mimeType = mimeType, existingRemoteId = existingRemoteId)
    }

    override suspend fun readBytes(path: String, remoteId: String?): ByteArray? = withContext(Dispatchers.IO) {
        val id = remoteId ?: resolveId(path) ?: return@withContext null
        rest.downloadBytes(fileId = id)
    }

    override suspend fun downloadFile(path: String, toLocalPath: String, remoteId: String?): Unit = withContext(Dispatchers.IO) {
        val id = remoteId ?: resolveId(path) ?: throw CloudStorageException(CloudError.NotFound, "remote missing: $path")
        rest.download(fileId = id, toLocalPath = toLocalPath)
    }

    override suspend fun delete(path: String, remoteId: String?): Unit = withContext(Dispatchers.IO) {
        val id = remoteId ?: resolveId(path) ?: return@withContext
        rest.delete(fileId = id)
    }

    private suspend fun requireToken(): String = when (val result = tokenProvider.accessToken()) {
        is DriveToken.Value -> result.accessToken
        DriveToken.NeedsConsent -> throw CloudStorageException(CloudError.NeedsConsent, "Drive consent missing")
        is DriveToken.Failed -> throw CloudStorageException(CloudError.NotAvailable, "Drive authorization failed", result.cause)
    }

    private suspend fun resolveId(path: String): String? = list().firstOrNull { it.path == path }?.remoteId

    private companion object {
        const val TAG = "GoogleDriveStorage"
        const val MAX_SINGLE_UPLOAD_BYTES = 5 * 1024 * 1024
    }
}
