@file:OptIn(com.vocabloot.backupkit.ExperimentalRestoreApi::class)

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
        val placements = mutableListOf<Pair<String?, List<String>>>()
        var reject: (String?) -> String? = { null }
        val placement = RestorePlacement { group, files ->
            placements += group to files.map { it.path }
            reject(group)?.let { PlacementResult.Rejected(it) } ?: PlacementResult.Placed
        }
        val restore = RestoreEngine(engine, storage, records, placement = placement, clock = { now }, newRunId = { "run-1" })
        val progress = mutableListOf<RestoreProgress>()

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

        suspend fun start() = restore.start(plan()) { progress += it }
        suspend fun start(plan: RestorePlan) = restore.start(plan) { progress += it }
        suspend fun resume(resetAttempts: Boolean = false) = restore.resume(resetAttempts) { progress += it }

        /** Two photos of one word, plus the word's own group of two files. */
        suspend fun groupedPlan(): RestorePlan = RestorePlan(
            source = source(),
            files = listOf(
                RestoreFile("manifest.json", "/local/manifest.json", required = true, group = "meta"),
                RestoreFile("backup.json", "/local/backup.json", required = true, group = "meta"),
                RestoreFile("items/a.jpg", "/local/a.jpg", required = false, group = "w1"),
                RestoreFile("items/b.jpg", "/local/b.jpg", required = false, group = "w1"),
            ),
        )
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
        assertEquals(listOf(0, 1, 2, 3, 4), h.progress.map { it.filesDone })
        assertEquals(4, h.progress.last().filesTotal)
        assertEquals(4 to 4, h.progress.last().groupsDone to h.progress.last().groupsTotal, "ungrouped files are their own groups")
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
    fun a_group_is_placed_once_with_all_its_files_and_counted_as_one() = runTest {
        val h = Harness()

        val outcome = h.start(h.groupedPlan())

        assertEquals(RestoreOutcome.Completed(4), outcome)
        assertEquals(listOf<Pair<String?, List<String>>>("meta" to listOf("manifest.json", "backup.json"), "w1" to listOf("items/a.jpg", "items/b.jpg")), h.placements)
        assertEquals(2 to 2, h.progress.last().groupsDone to h.progress.last().groupsTotal)
        // The word counts as done only when its last file has been placed, and the count never moves back.
        val groups = h.progress.map { it.groupsDone }
        assertEquals(0, groups.first()); assertEquals(2, groups.last())
        assertTrue(groups.zipWithNext().all { (a, b) -> b >= a }, "$groups")
    }

    @Test
    fun a_rejected_optional_group_keeps_its_files_pending_with_one_more_attempt_each() = runTest {
        val h = Harness()
        h.reject = { group -> if (group == "w1") "not in manifest" else null }

        val outcome = h.start(h.groupedPlan())

        assertEquals(RestoreOutcome.Partial(2, listOf("items/a.jpg", "items/b.jpg")), outcome)
        val record = h.restore.record()!!
        assertEquals(listOf(1, 1), record.files.filter { it.group == "w1" }.map { it.attempts })
        assertTrue(record.files.filter { it.group == "w1" }.none { it.downloaded }, "rejected files are staged again next run")
    }

    @Test
    fun a_rejected_required_group_fails_the_run() = runTest {
        val h = Harness()
        h.reject = { group -> if (group == "meta") "bad manifest" else null }

        val outcome = h.start(h.groupedPlan())

        assertEquals(RestoreOutcome.Failed(RestoreError.PlacementRejected, 0), outcome)
        assertEquals(listOf<Pair<String?, List<String>>>("meta" to listOf("manifest.json", "backup.json")), h.placements, "optional files were never touched")
    }

    @Test
    fun a_partially_downloaded_group_places_what_landed_and_retries_the_rest() = runTest {
        val h = Harness()
        h.storage.failDownloadsContaining = "b.jpg"

        val outcome = h.start(h.groupedPlan())

        assertEquals(RestoreOutcome.Partial(3, listOf("items/b.jpg")), outcome)
        assertEquals<Pair<String?, List<String>>>("w1" to listOf("items/a.jpg"), h.placements.last())
        assertEquals(1 to 2, h.progress.last().groupsDone to h.progress.last().groupsTotal, "the word is not done until both files are")
    }

    @Test
    fun an_explicit_retry_resets_the_attempt_cap() = runTest {
        val h = Harness()
        h.storage.failDownloadsContaining = "b.jpg"
        h.start(); h.resume(); h.resume()
        assertEquals(3, h.restore.record()!!.files.first { it.path == "items/b.jpg" }.attempts)
        h.storage.failDownloadsContaining = null

        assertEquals(RestoreOutcome.Partial(3, listOf("items/b.jpg")), h.resume(), "a plain resume keeps the cap")
        assertEquals(RestoreOutcome.Completed(4), h.resume(resetAttempts = true))
    }

    @Test
    fun a_duplicate_path_in_the_plan_is_rejected() = runTest {
        val h = Harness()
        val bad = RestorePlan(h.source(), listOf(RestoreFile("x", "/x"), RestoreFile("x", "/y")))
        val failed = runCatching { h.restore.start(bad) }
        assertIs<IllegalArgumentException>(failed.exceptionOrNull())
    }
}
