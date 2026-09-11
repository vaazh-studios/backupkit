package com.vocabloot.backupkit

import com.vocabloot.backupkit.test.FakeCloudStorage
import com.vocabloot.backupkit.test.MemoryRestoreRecordStore
import com.vocabloot.backupkit.test.MemorySyncStateStore

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SyncEngineTest {

    private class Harness(ids: List<String>) {
        val local = mutableMapOf<String, ByteArray>()
        val items = ids.toMutableList()
        var favorite = false
        var meta = "{}"
        val storage = FakeCloudStorage(readLocal = { local[it] }, writeLocal = { p, b -> local[p] = b })
        val stateStore = MemorySyncStateStore()
        var now = 1_000L
        val progress = mutableListOf<Pair<Int, Int>>()

        init {
            for (id in ids) addMedia(id)
        }

        fun addMedia(id: String) {
            local["items/$id.jpg"] = "jpg:$id".encodeToByteArray()
            local["items/$id.png"] = "png:$id".encodeToByteArray()
        }

        fun deleteMedia(id: String) {
            local.remove("items/$id.jpg")
            local.remove("items/$id.png")
        }

        fun engine() = SyncEngine(
            storage = storage,
            stateStore = stateStore,
            policy = SyncPolicy(markerPath = "backup.json"),
            clock = { now },
        )

        /** Media that is declared but missing locally becomes [SyncSource.Absent], like a manifest-declared file the app lost. */
        fun snapshot(): SyncSnapshot {
            fun media(path: String): SyncEntry {
                val bytes = local[path]
                return if (bytes == null) SyncEntry(path, SyncSource.Absent, size = -1) else SyncEntry(path, SyncSource.LocalFile(path), size = bytes.size.toLong())
            }
            fun json(path: String, text: String): SyncEntry {
                val bytes = text.encodeToByteArray()
                return SyncEntry(path, SyncSource.Bytes(bytes), size = bytes.size.toLong(), hash = sha256Hex(bytes))
            }
            val media = items.flatMap { listOf(media("items/$it.jpg"), media("items/$it.png")) }
            val manifest = json("manifest.json", """{"items":${items.joinToString(",", "[", "]") { "\"$it\"" }},"favorite":$favorite}""")
            val metaEntry = json("meta.json", meta)
            val marker = json("backup.json", """{"count":${items.size},"at":$now}""")
            return SyncSnapshot(media + listOf(manifest, metaEntry, marker), isEmpty = items.isEmpty())
        }

        suspend fun run(): SyncOutcome = engine().sync(snapshot()) { done, total -> progress += done to total }
    }

    private fun harness(vararg ids: String) = Harness(ids.toList())

    @Test
    fun first_sync_uploads_media_then_hashed_entries_then_marker_last() = runTest {
        val h = harness("a", "b")

        val outcome = h.run()

        assertIs<SyncOutcome.Synced>(outcome)
        assertEquals(6, outcome.entryCount)
        assertEquals(
            listOf("items/a.jpg", "items/a.png", "items/b.jpg", "items/b.png", "manifest.json", "meta.json", "backup.json"),
            h.storage.putLog,
        )
        assertEquals(7 to 7, h.progress.last())
        assertEquals(0 to 7, h.progress.first())
    }

    @Test
    fun second_sync_with_no_change_uploads_nothing() = runTest {
        val h = harness("a")
        h.run()
        h.storage.putLog.clear()

        val outcome = h.run()

        assertIs<SyncOutcome.Synced>(outcome)
        assertTrue(h.storage.putLog.isEmpty(), "unchanged snapshot must not re-upload: ${h.storage.putLog}")
    }

    @Test
    fun a_hashed_entry_change_reuploads_only_that_entry_and_the_marker() = runTest {
        val h = harness("a")
        h.run()
        h.storage.putLog.clear()
        h.favorite = true

        h.run()

        assertEquals(listOf("manifest.json", "backup.json"), h.storage.putLog)
    }

    @Test
    fun another_hashed_entry_change_reuploads_it_and_the_marker() = runTest {
        val h = harness("a")
        h.run()
        h.storage.putLog.clear()
        h.meta = """{"theme":"dark"}"""

        h.run()

        assertEquals(listOf("meta.json", "backup.json"), h.storage.putLog)
    }

    @Test
    fun a_removed_item_deletes_its_media_after_the_uploads() = runTest {
        val h = harness("a", "b")
        h.run()
        h.storage.putLog.clear()
        h.items.remove("b")
        h.deleteMedia("b")

        h.run()

        assertEquals(listOf("manifest.json", "backup.json"), h.storage.putLog)
        assertEquals(listOf("items/b.jpg", "items/b.png"), h.storage.deleteLog)
        assertFalse(h.storage.remote.containsKey("items/b.jpg"))
    }

    @Test
    fun interrupted_sync_resumes_without_reuploading_finished_files() = runTest {
        val h = harness("a", "b")
        h.storage.failPutsContaining = "b.png"

        val first = h.run()
        assertIs<SyncOutcome.Failed>(first)
        assertEquals(CloudError.Transport, first.error)
        assertFalse(h.storage.remote.containsKey("backup.json"), "marker must never land before all media")

        h.storage.failPutsContaining = null
        h.storage.putLog.clear()
        val second = h.run()

        assertIs<SyncOutcome.Synced>(second)
        assertEquals(listOf("items/b.png", "manifest.json", "meta.json", "backup.json"), h.storage.putLog)
    }

    @Test
    fun stale_state_self_heals_from_the_remote_listing() = runTest {
        val h = harness("a")
        h.run()
        h.storage.remote.remove("items/a.jpg") // the cloud lost a file behind our back
        h.storage.putLog.clear()

        h.run()

        assertEquals(listOf("items/a.jpg", "backup.json"), h.storage.putLog)
    }

    @Test
    fun identity_change_resets_state_and_reuploads_everything() = runTest {
        val h = harness("a")
        h.run()
        h.storage.remote.clear() // a different account's (empty) container
        h.storage.identity = "identity-B"
        h.storage.putLog.clear()

        h.run()

        assertEquals(5, h.storage.putLog.size, "2 media + 2 hashed + marker")
        assertEquals("identity-B", h.stateStore.load().identityKey)
    }

    @Test
    fun marker_remote_id_acts_as_identity_when_the_transport_has_none() = runTest {
        val h = harness("a")
        h.storage.identity = null
        h.storage.assignsRemoteIds = true
        h.run()
        val idA = h.stateStore.load().identityKey
        h.storage.remote.clear() // same paths, different Drive ids = a different account's folder
        h.storage.putLog.clear()

        h.run()

        assertEquals(5, h.storage.putLog.size)
        assertTrue(h.stateStore.load().identityKey != idA)
    }

    @Test
    fun drive_style_identity_survives_the_first_marker_upload() = runTest {
        val h = harness("a")
        h.storage.identity = null
        h.storage.assignsRemoteIds = true
        h.run()
        h.storage.putLog.clear()

        val outcome = h.run()

        assertIs<SyncOutcome.Synced>(outcome)
        assertTrue(h.storage.putLog.isEmpty(), "the marker's fresh id is adopted as identity, not an account switch: ${h.storage.putLog}")
    }

    @Test
    fun an_absent_entry_is_never_uploaded_and_its_remote_copy_is_never_deleted() = runTest {
        val h = harness("a")
        h.run()
        h.local.remove("items/a.jpg") // local copy lost; the item is still declared
        h.storage.putLog.clear()

        h.run()

        assertTrue(h.storage.deleteLog.isEmpty(), "the cloud copy is now the LAST copy: ${h.storage.deleteLog}")
        assertTrue(h.storage.remote.containsKey("items/a.jpg"))
        assertTrue(h.storage.putLog.isEmpty(), "nothing else changed, so no marker churn either: ${h.storage.putLog}")
    }

    @Test
    fun unavailable_storage_short_circuits() = runTest {
        val h = harness("a")
        h.storage.availability = CloudAvailability.NeedsConsent

        val outcome = h.run()

        assertIs<SyncOutcome.Unavailable>(outcome)
        assertEquals(UnavailableReason.NeedsConsent, outcome.reason)
        assertEquals(0, h.storage.listCalls)
    }

    @Test
    fun missing_local_media_on_first_sync_is_skipped_not_fatal() = runTest {
        val h = harness("a", "b")
        h.local.remove("items/b.jpg")

        val outcome = h.run()

        assertIs<SyncOutcome.Synced>(outcome)
        assertFalse(h.storage.remote.containsKey("items/b.jpg"))
        assertTrue(h.storage.remote.containsKey("backup.json"))
    }

    @Test
    fun storage_full_is_reported_as_such() = runTest {
        val h = harness("a")
        h.storage.failPutsContaining = "manifest"
        h.storage.failError = CloudError.StorageFull

        val outcome = h.run()

        assertIs<SyncOutcome.Failed>(outcome)
        assertEquals(CloudError.StorageFull, outcome.error)
    }

    @Test
    fun delete_failure_does_not_fail_the_sync() = runTest {
        val h = harness("a", "b")
        h.run()
        h.items.remove("b")
        h.storage.failDeletes = true

        val outcome = h.run()

        assertIs<SyncOutcome.Synced>(outcome)
    }

    @Test
    fun state_is_saved_after_every_put() = runTest {
        val h = harness("a")
        h.storage.failPutsContaining = "manifest"

        h.run()

        assertEquals(setOf("items/a.jpg", "items/a.png"), h.stateStore.load().entries.keys)
    }

    @Test
    fun empty_snapshot_on_a_device_that_never_synced_leaves_the_remote_untouched() = runTest {
        // A reinstall: the cloud still holds the old set, the device has no state and no items.
        val h = harness("a")
        h.run()
        h.stateStore.clear()
        h.items.clear()
        h.deleteMedia("a")
        h.storage.putLog.clear()

        val outcome = h.run()

        assertIs<SyncOutcome.Unavailable>(outcome)
        assertEquals(UnavailableReason.RestorePending, outcome.reason)
        assertTrue(h.storage.deleteLog.isEmpty(), "must never wipe a set this device never produced")
        assertTrue(h.storage.putLog.isEmpty(), "must not publish an empty set over the backup")
        assertTrue(h.storage.remote.containsKey("items/a.jpg"))
    }

    @Test
    fun a_device_that_synced_before_may_clear_the_remote_when_everything_is_deleted() = runTest {
        val h = harness("a")
        h.run()
        h.items.clear()
        h.deleteMedia("a")
        h.storage.putLog.clear()

        val outcome = h.run()

        assertIs<SyncOutcome.Synced>(outcome)
        assertEquals(2, outcome.entryCount, "manifest.json and meta.json are still declared")
        assertEquals(listOf("manifest.json", "backup.json"), h.storage.putLog)
        assertEquals(listOf("items/a.jpg", "items/a.png"), h.storage.deleteLog)
    }

    @Test
    fun probe_is_none_when_the_remote_is_empty() = runTest {
        assertEquals(RemoteProbe.None, harness("a").engine().probe())
    }

    @Test
    fun probe_is_not_ready_when_files_exist_without_a_marker() = runTest {
        val h = harness("a")
        h.storage.failPutsContaining = "backup.json"
        h.run()

        assertEquals(RemoteProbe.NotReady, h.engine().probe())
    }

    @Test
    fun probe_is_found_with_a_source_reference_after_a_sync() = runTest {
        val h = harness("a")
        h.run()

        val probe = h.engine().probe()

        assertIs<RemoteProbe.Found>(probe)
        assertTrue(probe.marker.decodeToString().startsWith("{\"count\":1"))
        assertEquals(5, probe.files.size)
        assertEquals("identity-A", probe.source.identityKey)
        assertEquals(64, probe.source.markerFingerprint.length)
        assertTrue(probe.source.matches((h.engine().probe() as RemoteProbe.Found).source))
    }

    @Test
    fun probe_reports_unavailable_and_listing_failures() = runTest {
        val h = harness("a")
        h.storage.availability = CloudAvailability.NoAccount
        assertEquals(RemoteProbe.Unavailable(UnavailableReason.NoAccount), h.engine().probe())

        h.storage.availability = CloudAvailability.Available
        h.storage.failListing = CloudError.Offline
        assertEquals(RemoteProbe.Failed(CloudError.Offline), h.engine().probe())
    }

    @Test
    fun hold_blocks_sync_and_survives_a_reload() = runTest {
        val h = harness("a")
        h.engine().setHold(WriteHold.RestoreRunning)

        val outcome = h.run()

        assertEquals(SyncOutcome.Unavailable(UnavailableReason.WriteHeld), outcome)
        assertEquals(0, h.storage.listCalls)
        assertEquals(WriteHold.RestoreRunning, h.engine().hold)
        h.engine().setHold(WriteHold.None)
        assertIs<SyncOutcome.Synced>(h.run())
    }

    @Test
    fun a_hold_set_mid_run_stops_the_run_before_the_next_put() = runTest {
        val h = harness("a", "b")
        h.storage.onPut = { path -> if (path == "items/a.png") h.engine().setHold(WriteHold.RestoreRunning) }

        val outcome = h.run()

        assertEquals(SyncOutcome.Unavailable(UnavailableReason.WriteHeld), outcome)
        assertEquals(listOf("items/a.jpg", "items/a.png"), h.storage.putLog)
        assertFalse(h.storage.remote.containsKey("backup.json"))
    }

    @Test
    fun identity_reset_keeps_the_hold() = runTest {
        val h = harness("a")
        h.run()
        h.engine().setHold(WriteHold.RestoreIncomplete)
        h.storage.identity = "identity-B"

        assertEquals(SyncOutcome.Unavailable(UnavailableReason.WriteHeld), h.run())
        assertEquals(WriteHold.RestoreIncomplete, h.engine().hold)
    }
    @Test
    fun unknown_remote_sizes_do_not_force_media_reuploads() = runTest {
        val h = harness("a")
        h.run()
        h.storage.putLog.clear()
        h.storage.reportUnknownSizes = true   // iCloud placeholders: the listing knows the files, not their sizes

        val outcome = h.run()

        assertIs<SyncOutcome.Synced>(outcome)
        assertEquals(emptyList(), h.storage.putLog)
    }

    @Test
    fun a_hold_set_while_a_put_is_gated_stops_the_run_before_the_next_put() = runTest {
        val h = harness("a")
        h.storage.gatePutsContaining = "items/a.png"
        val engine = h.engine()
        val run = async { engine.sync(h.snapshot()) { _, _ -> } }
        h.storage.putStarted.first { it == "items/a.png" }

        engine.setHold(WriteHold.RestoreRunning)
        h.storage.releasePuts()

        assertEquals(SyncOutcome.Unavailable(UnavailableReason.WriteHeld), run.await())
        assertEquals(listOf("items/a.jpg", "items/a.png"), h.storage.putLog)
    }

    // Shrink guard: 4 items = 8 media files remotely; dropping 3 items deletes 6 of 8 (75%), dropping 2 deletes 4 of 8 (50%).

    @Test
    fun deleting_most_of_the_remote_set_is_refused_and_writes_nothing() = runTest {
        val h = harness("a", "b", "c", "d")
        h.run()
        h.storage.putLog.clear()
        h.items.retainAll(listOf("d")); h.deleteMedia("a"); h.deleteMedia("b"); h.deleteMedia("c")

        val outcome = h.run()

        assertEquals(SyncOutcome.Unavailable(UnavailableReason.ShrinkSuspected), outcome)
        assertEquals(emptyList(), h.storage.putLog)
        assertEquals(emptyList(), h.storage.deleteLog)
    }

    @Test
    fun deleting_exactly_the_guard_fraction_is_allowed() = runTest {
        val h = harness("a", "b", "c", "d")
        h.run()
        h.items.retainAll(listOf("c", "d")); h.deleteMedia("a"); h.deleteMedia("b")

        assertIs<SyncOutcome.Synced>(h.run())
        assertEquals(4, h.storage.deleteLog.size)
    }

    @Test
    fun the_guard_does_not_apply_below_its_floor() = runTest {
        val h = harness("a")   // 2 remote media files, below minRemoteEntries = 4
        h.run()
        h.items.clear(); h.deleteMedia("a")

        assertIs<SyncOutcome.Synced>(h.run())
        assertEquals(2, h.storage.deleteLog.size)
    }

    @Test
    fun allowShrink_lets_a_confirmed_shrink_through() = runTest {
        val h = harness("a", "b", "c", "d")
        h.run()
        h.items.retainAll(listOf("d")); h.deleteMedia("a"); h.deleteMedia("b"); h.deleteMedia("c")

        val outcome = h.engine().sync(h.snapshot().copy(allowShrink = true)) { _, _ -> }

        assertIs<SyncOutcome.Synced>(outcome)
        assertEquals(6, h.storage.deleteLog.size)
    }

    @Test
    fun a_null_guard_disables_the_check() = runTest {
        val h = harness("a", "b", "c", "d")
        h.run()
        h.items.retainAll(listOf("d")); h.deleteMedia("a"); h.deleteMedia("b"); h.deleteMedia("c")
        val engine = SyncEngine(h.storage, h.stateStore, SyncPolicy(markerPath = "backup.json", shrinkGuard = null), clock = { h.now })

        assertIs<SyncOutcome.Synced>(engine.sync(h.snapshot()) { _, _ -> })
    }

    @Test
    fun the_empty_guard_answers_before_the_shrink_guard() = runTest {
        val h = harness("a", "b", "c", "d")
        h.run()
        val fresh = Harness(emptyList())          // never synced, empty, same remote
        fresh.storage.remote.putAll(h.storage.remote)

        assertEquals(SyncOutcome.Unavailable(UnavailableReason.RestorePending), fresh.run())
    }

}
