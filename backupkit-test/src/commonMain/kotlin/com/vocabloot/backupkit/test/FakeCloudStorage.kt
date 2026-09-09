package com.vocabloot.backupkit.test

import com.vocabloot.backupkit.CloudAvailability
import com.vocabloot.backupkit.CloudError
import com.vocabloot.backupkit.CloudProvider
import com.vocabloot.backupkit.CloudStorage
import com.vocabloot.backupkit.CloudStorageException
import com.vocabloot.backupkit.RemoteFile

/**
 * In-memory [CloudStorage] for tests. Every failure mode the engines react to can be switched on:
 * listing, put, read, download and delete failures, an availability or identity change, and Drive-style
 * remote ids. [readLocal] resolves the local paths the engines pass to [writeFile]; [writeLocal] receives
 * downloads. The logs record every call so a test can assert order and count.
 */
public class FakeCloudStorage(
    override val provider: CloudProvider = CloudProvider.ICloud,
    private val readLocal: (String) -> ByteArray?,
    private val writeLocal: (String, ByteArray) -> Unit,
) : CloudStorage {
    /** Remote path to bytes, in first-write order. */
    public val remote: MutableMap<String, ByteArray> = linkedMapOf()
    private val ids = mutableMapOf<String, String>()
    private var nextId = 1

    public var availability: CloudAvailability = CloudAvailability.Available
    public var identity: String? = "identity-A"
    /** Drive semantics: a create mints a fresh id, an update keeps it. */
    public var assignsRemoteIds: Boolean = false
    public var failPutsContaining: String? = null
    public var failError: CloudError = CloudError.Transport
    public var failDeletes: Boolean = false
    public var failListing: CloudError? = null
    public var failReadsContaining: String? = null
    public var failDownloadsContaining: String? = null

    public val prefetchLog: MutableList<List<String>> = mutableListOf()
    public val downloadLog: MutableList<String> = mutableListOf()
    public val putLog: MutableList<String> = mutableListOf()
    public val deleteLog: MutableList<String> = mutableListOf()
    /** Called with the path on every successful put, before the bytes land; a hook for mid-run interference. */
    public var onPut: (String) -> Unit = {}
    public var listCalls: Int = 0

    override suspend fun availability(): CloudAvailability = availability

    override suspend fun identityKey(): String? = identity

    override suspend fun list(): List<RemoteFile> {
        listCalls += 1
        failListing?.let { throw CloudStorageException(it, "listing failed") }
        return remote.map { (path, bytes) -> RemoteFile(path = path, size = bytes.size.toLong(), remoteId = ids[path]) }
    }

    override suspend fun exists(path: String): Boolean = path in remote

    override suspend fun writeFile(path: String, localPath: String, mimeType: String, existingRemoteId: String?): String? {
        val bytes = readLocal(localPath) ?: throw CloudStorageException(CloudError.NotFound, "local missing: $localPath")
        return writeBytes(path = path, bytes = bytes, mimeType = mimeType, existingRemoteId = existingRemoteId)
    }

    override suspend fun writeBytes(path: String, bytes: ByteArray, mimeType: String, existingRemoteId: String?): String? {
        failPutsContaining?.let { if (path.contains(it)) throw CloudStorageException(failError, "put failed: $path") }
        putLog += path
        onPut(path)
        val created = !remote.containsKey(path)
        remote[path] = bytes
        if (!assignsRemoteIds) return null
        if (created || path !in ids) ids[path] = "id-${nextId++}"
        return ids.getValue(path)
    }

    override suspend fun readBytes(path: String, remoteId: String?): ByteArray? {
        failReadsContaining?.let { if (path.contains(it)) throw CloudStorageException(CloudError.Transport, "read failed: $path") }
        return remote[path]
    }

    override suspend fun prefetch(paths: List<String>) {
        prefetchLog += paths
    }

    override suspend fun downloadFile(path: String, toLocalPath: String, remoteId: String?) {
        failDownloadsContaining?.let { if (path.contains(it)) throw CloudStorageException(CloudError.Transport, "download failed: $path") }
        val bytes = remote[path] ?: throw CloudStorageException(CloudError.NotFound, "remote missing: $path")
        downloadLog += path
        writeLocal(toLocalPath, bytes)
    }

    override suspend fun delete(path: String, remoteId: String?) {
        if (failDeletes) throw CloudStorageException(CloudError.Transport, "delete failed: $path")
        deleteLog += path
        remote.remove(path)
        ids.remove(path)
    }
}
