package com.vocabloot.backupkit

import com.vocabloot.backupkit.internal.DriveRestClient
import com.vocabloot.backupkit.internal.LocalFiles
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DriveRestClientTest {

    private val requests = mutableListOf<HttpRequestData>()
    private val tokens = mutableListOf("tok-1", "tok-2")
    private val cleared = mutableListOf<String>()

    private fun client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): DriveRestClient =
        DriveRestClient(
            client = HttpClient(MockEngine { request -> requests += request; handler(request) }),
            tokenProvider = { tokens.first() },
            onUnauthorized = { cleared += it; tokens.removeAt(0) },
            retryDelay = { },
        )

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun list_pages_through_the_app_data_space() = runTest {
        val c = client { req ->
            assertEquals("appDataFolder", req.url.parameters["spaces"])
            if (req.url.parameters["pageToken"] == null) {
                respond("""{"nextPageToken":"p2","files":[{"id":"a","name":"backup.json","size":"12"}]}""", HttpStatusCode.OK, jsonHeaders)
            } else {
                respond("""{"files":[{"id":"b","name":"items/w.jpg","size":"3","modifiedTime":"2026-01-01T00:00:00Z"}]}""", HttpStatusCode.OK, jsonHeaders)
            }
        }

        val listed = c.list()

        assertEquals(listOf("a", "b"), listed.map { it.id })
        assertEquals("Bearer tok-1", requests.first().headers[HttpHeaders.Authorization])
    }

    @Test
    fun create_sends_multipart_related_with_app_data_parent() = runTest {
        val c = client { respond("""{"id":"new-1","name":"items/w.jpg"}""", HttpStatusCode.OK, jsonHeaders) }

        val id = c.create(name = "items/w.jpg", bytes = "JPEGDATA".encodeToByteArray(), mimeType = "image/jpeg")

        assertEquals("new-1", id)
        val req = requests.single()
        assertEquals("multipart", req.url.parameters["uploadType"])
        assertTrue(req.body.contentType.toString().startsWith("multipart/related; boundary="))
        val body = (req.body as ByteArrayContent).bytes().decodeToString()
        assertTrue(""""parents":["appDataFolder"]""" in body, body)
        assertTrue(""""name":"items/w.jpg"""" in body, body)
        assertTrue("Content-Type: image/jpeg" in body, body)
        assertTrue("JPEGDATA" in body, body)
    }

    @Test
    fun update_patches_media_content() = runTest {
        val c = client { respond("""{"id":"x"}""", HttpStatusCode.OK, jsonHeaders) }

        c.update(fileId = "x", bytes = "{}".encodeToByteArray(), mimeType = "application/json")

        val req = requests.single()
        assertEquals("PATCH", req.method.value)
        assertTrue(req.url.encodedPath.endsWith("/upload/drive/v3/files/x"), req.url.encodedPath)
        assertEquals("media", req.url.parameters["uploadType"])
    }

    @Test
    fun a_401_clears_the_token_and_retries_once() = runTest {
        var calls = 0
        val c = client {
            calls += 1
            if (calls == 1) respond("unauthorized", HttpStatusCode.Unauthorized) else respond("""{"files":[]}""", HttpStatusCode.OK, jsonHeaders)
        }

        c.list()

        assertEquals(listOf("tok-1"), cleared)
        assertEquals("Bearer tok-2", requests.last().headers[HttpHeaders.Authorization])
    }

    @Test
    fun quota_exceeded_maps_to_StorageFull_and_other_403s_to_AuthRevoked() = runTest {
        val quota = client { respond("""{"error":{"errors":[{"reason":"storageQuotaExceeded"}]}}""", HttpStatusCode.Forbidden, jsonHeaders) }
        assertEquals(CloudError.StorageFull, assertFailsWith<CloudStorageException> { quota.list() }.error)

        val revoked = client { respond("""{"error":{"errors":[{"reason":"insufficientPermissions"}]}}""", HttpStatusCode.Forbidden, jsonHeaders) }
        assertEquals(CloudError.AuthRevoked, assertFailsWith<CloudStorageException> { revoked.list() }.error)
    }

    @Test
    fun server_errors_retry_three_times_then_fail_as_Transport() = runTest {
        var calls = 0
        val c = client { calls += 1; respond("boom", HttpStatusCode.InternalServerError) }

        val failure = assertFailsWith<CloudStorageException> { c.list() }

        assertEquals(CloudError.Transport, failure.error)
        assertEquals(3, calls)
    }

    @Test
    fun update_reports_a_vanished_file_instead_of_throwing() = runTest {
        val gone = client { respond("not found", HttpStatusCode.NotFound) }
        assertEquals(false, gone.update(fileId = "old", bytes = "{}".encodeToByteArray(), mimeType = "application/json"))

        val ok = client { respond("""{"id":"x"}""", HttpStatusCode.OK, jsonHeaders) }
        assertEquals(true, ok.update(fileId = "x", bytes = "{}".encodeToByteArray(), mimeType = "application/json"))
    }

    @Test
    fun delete_treats_404_as_success() = runTest {
        val c = client { respond("gone", HttpStatusCode.NotFound) }
        c.delete(fileId = "missing")
    }

    @Test
    fun download_writes_alt_media_bytes_into_the_target_file() = runTest {
        val c = client { req ->
            assertEquals("media", req.url.parameters["alt"])
            respond("BYTES".encodeToByteArray(), HttpStatusCode.OK)
        }
        val target = Path(SystemTemporaryDirectory, "backupkit-${Random.nextLong().toString(16)}", "out.jpg").toString()

        c.download(fileId = "f", toLocalPath = target)

        assertEquals("BYTES", LocalFiles.readAllBytes(target)!!.decodeToString())
    }
}
