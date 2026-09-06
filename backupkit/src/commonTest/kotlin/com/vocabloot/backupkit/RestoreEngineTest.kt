package com.vocabloot.backupkit

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RestoreEngineTest {

    private class Harness {
        val local = mutableMapOf<String, ByteArray>()
        val storage = FakeCloudStorage(readLocal = { local[it] }, writeLocal = { p, b -> local[p] = b })
        val syncState = MemorySyncStateStore()
        val records = MemoryRestoreRecordStore()
        var now = 1_000L
        val engine = SyncEngine(storage, syncState, SyncPolicy(markerPath = "backup.json"), clock = { now })
        val restore = RestoreEngine(engine, storage, records, clock = { now }, newRunId = { "run-1" })
        val progress = mutableListOf<Pair<Int, Int>>()

        init {
            storage.remote["backup.json"] = "{\"count\":2}".encodeToByteArray()
            storage.remote["manifest.json"] = "{}".encodeToByteArray()
            storage.remote["items/a.jpg"] = "a".encodeToByteArray()
            storage.remote["items/b.jpg"] = "b".encodeToByteArray()
        }

        suspend fun source(): SourceRef = (engine.probe() as RemoteProbe.Found).source

        suspend fun plan(): RestorePlan = RestorePlan(
            source = source(),
            files = listOf(
                RestoreFile("manifest.json", "/local/manifest.json", required = true),
                RestoreFile("backup.json", "/local/backup.json", required = true),
                RestoreFile("items/a.jpg", "/local/a.jpg", required = false),
                RestoreFile("items/b.jpg", "/local/b.jpg", required = false),
            ),
        )

        suspend fun start() = restore.start(plan()) { d, t -> progress += d to t }
        suspend fun resume() = restore.resume { d, t -> progress += d to t }
    }

    @Test
    fun required_files_download_first_in_plan_order_then_optional() = runTest {
        val h = Harness()

        val outcome = h.start()

        assertEquals(RestoreOutcome.Completed(4), outcome)
        assertEquals(listOf("manifest.json", "backup.json", "items/a.jpg", "items/b.jpg"), h.storage.downloadLog)
        assertEquals("a", h.local.getValue("/local/a.jpg").decodeToString())
    }

    @Test
    fun a_required_failure_fails_the_run_and_leaves_optional_files_untouched() = runTest {
        val h = Harness()
        h.storage.failDownloadsContaining = "backup.json"

        val outcome = h.start()

        assertEquals(RestoreOutcome.Failed(RestoreError.Transport, 1), outcome)
        assertEquals(listOf("manifest.json"), h.storage.downloadLog)
        val record = h.restore.record()!!
        assertTrue(record.files.first { it.path == "manifest.json" }.done)
        assertEquals(1, record.files.first { it.path == "backup.json" }.attempts)
    }

    @Test
    fun an_optional_failure_is_kept_pending_with_an_attempt_count() = runTest {
        val h = Harness()
        h.storage.failDownloadsContaining = "b.jpg"

        val outcome = h.start()

        assertEquals(RestoreOutcome.Partial(3, listOf("items/b.jpg")), outcome)
        assertEquals(1, h.restore.record()!!.files.first { it.path == "items/b.jpg" }.attempts)
        assertNull(h.restore.record()!!.completedAtEpochMs)
    }

    @Test
    fun three_optional_failures_give_up_on_that_file() = runTest {
        val h = Harness()
        h.storage.failDownloadsContaining = "b.jpg"
        h.start()
        h.resume()
        h.resume()
        h.storage.downloadLog.clear()

        val outcome = h.resume()

        assertEquals(RestoreOutcome.Partial(3, listOf("items/b.jpg")), outcome)
        assertTrue(h.storage.downloadLog.isEmpty(), "capped file must not be retried: ${h.storage.downloadLog}")
        assertEquals(3, h.restore.record()!!.files.first { it.path == "items/b.jpg" }.attempts)
    }

    @Test
    fun resume_skips_files_already_done() = runTest {
        val h = Harness()
        h.storage.failDownloadsContaining = "b.jpg"
        h.start()
        h.storage.failDownloadsContaining = null
        h.storage.downloadLog.clear()

        val outcome = h.resume()

        assertEquals(RestoreOutcome.Completed(4), outcome)
        assertEquals(listOf("items/b.jpg"), h.storage.downloadLog)
        assertNotNull(h.restore.record()!!.completedAtEpochMs)
    }

    @Test
    fun resume_with_a_changed_source_reports_source_changed() = runTest {
        val h = Harness()
        h.storage.failDownloadsContaining = "b.jpg"
        h.start()
        h.storage.remote["backup.json"] = "{\"count\":9}".encodeToByteArray() // another device wrote a different backup
        h.storage.downloadLog.clear()

        val outcome = h.resume()

        assertEquals(RestoreOutcome.Failed(RestoreError.SourceChanged, 3), outcome)
        assertTrue(h.storage.downloadLog.isEmpty())
    }

    @Test
    fun resume_when_the_remote_is_gone_reports_source_unavailable() = runTest {
        val h = Harness()
        h.storage.failDownloadsContaining = "b.jpg"
        h.start()
        h.storage.remote.clear()

        assertEquals(RestoreOutcome.Failed(RestoreError.SourceUnavailable, 3), h.resume())
    }

    @Test
    fun resume_without_a_record_reports_source_unavailable() = runTest {
        assertEquals(RestoreOutcome.Failed(RestoreError.SourceUnavailable, 0), Harness().resume())
    }

    @Test
    fun resume_while_the_marker_is_missing_reports_not_ready() = runTest {
        val h = Harness()
        h.storage.failDownloadsContaining = "b.jpg"
        h.start()
        h.storage.remote.remove("backup.json")

        assertEquals(RestoreOutcome.Failed(RestoreError.NotReady, 3), h.resume())
    }

    @Test
    fun prefetch_is_asked_for_every_pending_path() = runTest {
        val h = Harness()
        h.storage.failDownloadsContaining = "b.jpg"
        h.start()
        h.resume()

        assertEquals(listOf("manifest.json", "backup.json", "items/a.jpg", "items/b.jpg"), h.storage.prefetchLog[0])
        assertEquals(listOf("items/b.jpg"), h.storage.prefetchLog[1])
    }

    @Test
    fun progress_counts_every_file() = runTest {
        val h = Harness()
        h.start()
        assertEquals(listOf(0 to 4, 1 to 4, 2 to 4, 3 to 4, 4 to 4), h.progress)
    }

    @Test
    fun completed_run_stamps_completion_and_clear_removes_it() = runTest {
        val h = Harness()
        h.now = 5_000L
        h.start()

        assertEquals(5_000L, h.restore.record()!!.completedAtEpochMs)
        h.restore.clear()
        assertNull(h.restore.record())
    }

    @Test
    fun missing_remote_ids_are_resolved_with_one_listing() = runTest {
        val h = Harness()
        h.storage.assignsRemoteIds = true
        h.storage.remote.clear()
        h.storage.writeBytes("backup.json", "{\"count\":1}".encodeToByteArray(), "application/json")
        h.storage.writeBytes("items/a.jpg", "a".encodeToByteArray(), "image/jpeg")
        val plan = RestorePlan(h.source(), listOf(RestoreFile("backup.json", "/local/backup.json"), RestoreFile("items/a.jpg", "/local/a.jpg", required = false)))
        h.storage.listCalls = 0

        h.restore.start(plan)

        assertEquals(1, h.storage.listCalls)
        assertTrue(h.restore.record()!!.files.all { it.remoteId != null })
    }

    @Test
    fun a_duplicate_path_in_the_plan_is_rejected() = runTest {
        val h = Harness()
        val bad = RestorePlan(h.source(), listOf(RestoreFile("x", "/x"), RestoreFile("x", "/y")))
        val failed = runCatching { h.restore.start(bad) }
        assertIs<IllegalArgumentException>(failed.exceptionOrNull())
    }
}
