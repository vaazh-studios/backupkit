package com.vocabloot.backupkit

import com.vocabloot.backupkit.internal.DriveRestClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DriveRestClientOfflineTest {
    @Test
    fun connection_failure_maps_to_Offline() = runTest {
        val c = DriveRestClient(
            client = HttpClient(MockEngine { throw IOException("no route") }),
            tokenProvider = { "tok" },
            onUnauthorized = { },
            retryDelay = { },
        )
        assertEquals(CloudError.Offline, assertFailsWith<CloudStorageException> { c.list() }.error)
    }
}
