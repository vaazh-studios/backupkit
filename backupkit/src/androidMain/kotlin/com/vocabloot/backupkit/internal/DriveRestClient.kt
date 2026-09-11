package com.vocabloot.backupkit.internal

import com.vocabloot.backupkit.CloudError
import com.vocabloot.backupkit.CloudStorageException
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.http.HttpHeaders
import io.ktor.client.request.put
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The Drive v3 endpoints the app-data transport needs, over Ktor. No Drive Java client.
 * Every call carries a bearer token from [tokenProvider]; one 401 triggers [onUnauthorized]
 * (token cache clear) and a single retry; 5xx / 429 / rate-limit 403s retry three times.
 */
internal class DriveRestClient(
    private val client: HttpClient,
    private val tokenProvider: suspend () -> String,
    private val onUnauthorized: suspend (token: String) -> Unit,
    private val retryDelay: suspend (Long) -> Unit = { delay(it) },
    /** Resumable upload chunk size; Drive requires a multiple of 256 KiB except for the final chunk. */
    private val chunkBytes: Int = DEFAULT_CHUNK_BYTES,
) {
    @Serializable
    data class DriveFile(val id: String, val name: String, val size: String? = null, val modifiedTime: String? = null)

    @Serializable
    private data class FileList(val nextPageToken: String? = null, val files: List<DriveFile> = emptyList())

    @Serializable
    private data class FileMeta(val name: String, val parents: List<String>)
    @Serializable
    private data class FileId(val id: String)

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun list(): List<DriveFile> {
        val out = mutableListOf<DriveFile>()
        var pageToken: String? = null
        do {
            val response = call { token ->
                client.get("$API/files") {
                    bearerAuth(token)
                    url {
                        parameters.append("spaces", "appDataFolder")
                        parameters.append("fields", "nextPageToken,files(id,name,size,modifiedTime)")
                        parameters.append("pageSize", "1000")
                        pageToken?.let { parameters.append("pageToken", it) }
                    }
                }
            }
            val page = json.decodeFromString(FileList.serializer(), response.bodyAsText())
            out += page.files
            pageToken = page.nextPageToken
        } while (pageToken != null)
        return out
    }

    /** Multipart upload: metadata part + content part. Drive caps it at 5 MB; larger files go through [uploadResumable]. */
    suspend fun create(name: String, bytes: ByteArray, mimeType: String): String {
        val boundary = "backupkit-${bytes.size}-${name.hashCode()}"
        val meta = json.encodeToString(FileMeta.serializer(), FileMeta(name = name, parents = listOf("appDataFolder")))
        val head = "--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n--$boundary\r\nContent-Type: $mimeType\r\n\r\n".encodeToByteArray()
        val tail = "\r\n--$boundary--".encodeToByteArray()
        val response = call { token ->
            client.post("$UPLOAD/files") {
                bearerAuth(token)
                url { parameters.append("uploadType", "multipart") }
                setBody(ByteArrayContent(head + bytes + tail, ContentType.parse("multipart/related; boundary=$boundary")))
            }
        }
        return json.decodeFromString(DriveFile.serializer(), response.bodyAsText()).id
    }

    /** False when [fileId] no longer exists (404): the caller should create instead. */
    suspend fun update(fileId: String, bytes: ByteArray, mimeType: String): Boolean {
        val response = call(okStatuses = setOf(HttpStatusCode.NotFound)) { token ->
            client.patch("$UPLOAD/files/$fileId") {
                bearerAuth(token)
                url { parameters.append("uploadType", "media") }
                setBody(ByteArrayContent(bytes, ContentType.parse(mimeType)))
            }
        }
        return response.status != HttpStatusCode.NotFound
    }

    /**
     * Resumable upload (Drive's protocol for anything above the multipart limit): one request opens a
     * session, then [chunkBytes]-sized PUTs carry the bytes with `Content-Range`; Drive answers 308
     * with the range it holds until the last chunk returns the file. A failed chunk is retried by [call];
     * if that gives up, the session is queried once and the upload continues from what Drive has. A
     * session that vanished (404) is started over once. Returns the file id.
     */
    suspend fun uploadResumable(name: String, mimeType: String, source: ChunkSource, existingFileId: String?): String {
        var restarted = false
        while (true) {
            val session = startResumable(name, mimeType, source.size, existingFileId)
            var offset = 0L
            var recovered = false
            while (true) {
                val length = minOf(chunkBytes.toLong(), source.size - offset).toInt()
                val result = try {
                    putChunk(session, mimeType, source.read(offset, length), offset, source.size)
                } catch (e: CloudStorageException) {
                    if (recovered || e.error == CloudError.StorageFull || e.error == CloudError.AuthRevoked) throw e
                    recovered = true
                    when (val status = queryResumable(session, source.size)) {
                        is ChunkResult.Done -> return status.fileId
                        is ChunkResult.Continue -> { offset = status.nextOffset; continue }
                        ChunkResult.Gone -> break
                    }
                }
                when (result) {
                    is ChunkResult.Done -> return result.fileId
                    is ChunkResult.Continue -> offset = result.nextOffset
                    ChunkResult.Gone -> break
                }
            }
            if (restarted) throw CloudStorageException(CloudError.Transport, "Drive resumable session for $name vanished twice")
            restarted = true
        }
    }

    private suspend fun startResumable(name: String, mimeType: String, total: Long, existingFileId: String?): String {
        if (existingFileId != null) {
            val response = call(okStatuses = setOf(HttpStatusCode.NotFound)) { token ->
                client.patch("$UPLOAD/files/$existingFileId") {
                    bearerAuth(token)
                    url { parameters.append("uploadType", "resumable") }
                    header("X-Upload-Content-Type", mimeType)
                    header("X-Upload-Content-Length", total.toString())
                    setBody(ByteArrayContent("{}".encodeToByteArray(), ContentType.Application.Json))
                }
            }
            if (response.status != HttpStatusCode.NotFound) return response.sessionUri()
        }
        val meta = json.encodeToString(FileMeta.serializer(), FileMeta(name = name, parents = listOf("appDataFolder")))
        val response = call { token ->
            client.post("$UPLOAD/files") {
                bearerAuth(token)
                url { parameters.append("uploadType", "resumable") }
                header("X-Upload-Content-Type", mimeType)
                header("X-Upload-Content-Length", total.toString())
                setBody(ByteArrayContent(meta.encodeToByteArray(), ContentType.Application.Json))
            }
        }
        return response.sessionUri()
    }

    private fun HttpResponse.sessionUri(): String =
        headers[HttpHeaders.Location] ?: throw CloudStorageException(CloudError.Transport, "Drive resumable start returned no session URI")

    private suspend fun putChunk(session: String, mimeType: String, chunk: ByteArray, offset: Long, total: Long): ChunkResult {
        val response = call(okStatuses = setOf(HttpStatusCode.PermanentRedirect, HttpStatusCode.NotFound)) { token ->
            client.put(session) {
                bearerAuth(token)
                header(HttpHeaders.ContentRange, "bytes $offset-${offset + chunk.size - 1}/$total")
                setBody(ByteArrayContent(chunk, ContentType.parse(mimeType)))
            }
        }
        return response.toChunkResult(fallbackOffset = offset + chunk.size)
    }

    private suspend fun queryResumable(session: String, total: Long): ChunkResult {
        val response = call(okStatuses = setOf(HttpStatusCode.PermanentRedirect, HttpStatusCode.NotFound)) { token ->
            client.put(session) {
                bearerAuth(token)
                header(HttpHeaders.ContentRange, "bytes */$total")
            }
        }
        return response.toChunkResult(fallbackOffset = 0L)
    }

    private suspend fun HttpResponse.toChunkResult(fallbackOffset: Long): ChunkResult = when {
        status == HttpStatusCode.NotFound -> ChunkResult.Gone
        status == HttpStatusCode.PermanentRedirect -> {
            // "Range: bytes=0-N" is what Drive holds; nothing held means start at 0.
            val held = headers[HttpHeaders.Range]?.substringAfter("bytes=0-", "")?.toLongOrNull()
            ChunkResult.Continue(if (held == null) (if (headers[HttpHeaders.Range] == null) fallbackOffset else 0L) else held + 1)
        }
        else -> ChunkResult.Done(json.decodeFromString(FileId.serializer(), bodyAsText()).id)
    }

    sealed interface ChunkResult {
        data class Done(val fileId: String) : ChunkResult
        data class Continue(val nextOffset: Long) : ChunkResult
        data object Gone : ChunkResult
    }

    suspend fun delete(fileId: String) {
        call(okStatuses = setOf(HttpStatusCode.NotFound)) { token -> client.delete("$API/files/$fileId") { bearerAuth(token) } }
    }

    suspend fun download(fileId: String, toLocalPath: String) {
        LocalFiles.writeAllBytes(path = toLocalPath, bytes = downloadBytes(fileId))
    }

    suspend fun downloadBytes(fileId: String): ByteArray {
        val response = call { token ->
            client.get("$API/files/$fileId") {
                bearerAuth(token)
                url { parameters.append("alt", "media") }
            }
        }
        return response.body()
    }

    private suspend fun call(
        okStatuses: Set<HttpStatusCode> = emptySet(),
        request: suspend (token: String) -> HttpResponse,
    ): HttpResponse {
        var unauthorizedRetried = false
        var attempt = 0
        while (true) {
            val token = tokenProvider()
            val response = try {
                request(token)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                throw CloudStorageException(CloudError.Offline, "Drive request failed: ${t.message}", t)
            }
            val status = response.status
            when {
                status.isSuccess() || status in okStatuses -> return response

                status == HttpStatusCode.Unauthorized && !unauthorizedRetried -> {
                    unauthorizedRetried = true
                    onUnauthorized(token)
                }

                status == HttpStatusCode.Unauthorized ->
                    throw CloudStorageException(CloudError.AuthRevoked, "Drive token rejected")

                status == HttpStatusCode.Forbidden -> {
                    val text = response.bodyAsText()
                    val rateLimited = "rateLimitExceeded" in text || "userRateLimitExceeded" in text
                    when {
                        "storageQuotaExceeded" in text ->
                            throw CloudStorageException(CloudError.StorageFull, "Drive quota exceeded: ${text.take(300)}")
                        rateLimited && ++attempt < MAX_ATTEMPTS -> retryDelay(BACKOFF_MS[attempt - 1])
                        rateLimited -> throw CloudStorageException(CloudError.Transport, "Drive rate limited")
                        else -> throw CloudStorageException(CloudError.AuthRevoked, "Drive access denied: ${text.take(200)}")
                    }
                }

                status.value >= 500 || status == HttpStatusCode.TooManyRequests -> {
                    if (++attempt >= MAX_ATTEMPTS) {
                        throw CloudStorageException(CloudError.Transport, "Drive ${status.value} after $attempt attempts")
                    }
                    retryDelay(BACKOFF_MS[attempt - 1])
                }

                else -> throw CloudStorageException(CloudError.Transport, "Drive ${status.value}: ${response.bodyAsText().take(200)}")
            }
        }
    }

    private companion object {
        const val API = "https://www.googleapis.com/drive/v3"
        const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        const val MAX_ATTEMPTS = 3
        const val DEFAULT_CHUNK_BYTES = 8 * 1024 * 1024
        val BACKOFF_MS = listOf(1_000L, 2_000L, 4_000L)
    }
}
