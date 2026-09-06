package com.vocabloot.backupkit.internal

import com.vocabloot.backupkit.CloudError
import com.vocabloot.backupkit.CloudStorageException
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
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
) {
    @Serializable
    data class DriveFile(val id: String, val name: String, val size: String? = null, val modifiedTime: String? = null)

    @Serializable
    private data class FileList(val nextPageToken: String? = null, val files: List<DriveFile> = emptyList())

    @Serializable
    private data class FileMeta(val name: String, val parents: List<String>)

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

    /** Multipart upload: metadata part + content part. Files must be at most 5 MB (Drive's multipart limit). */
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
        val BACKOFF_MS = listOf(1_000L, 2_000L, 4_000L)
    }
}
