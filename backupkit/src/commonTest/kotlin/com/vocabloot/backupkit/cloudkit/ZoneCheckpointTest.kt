package com.vocabloot.backupkit.cloudkit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ZoneCheckpointTest {

    private val scope = CheckpointScope(container = "iCloud.example", zone = "backupkit", environment = "development", userRecordName = "_user1")

    private fun change(path: String, size: Long = 10L, tag: String = "t-$path") =
        RecordChange(recordName = ZoneCheckpoint.recordName(path), path = path, size = size, changeTag = tag)

    @Test
    fun a_matching_scope_round_trips_through_bytes() {
        val saved = ZoneCheckpoint.empty(scope).applyBatch(listOf(change("manifest.json"), change("words/a.jpg", 2_000L)), emptyList(), "tok1")
        val loaded = ZoneCheckpoint.decode(saved.encode(), scope)
        assertEquals("tok1", loaded.token)
        assertEquals(saved.records, loaded.records)
        assertEquals(setOf("manifest.json", "words/a.jpg"), loaded.toRemoteFiles().map { it.path }.toSet())
        assertTrue(loaded.toRemoteFiles().all { it.remoteId == null && it.size > 0L })
    }

    @Test
    fun a_foreign_scope_discards_the_file() {
        val saved = ZoneCheckpoint.empty(scope).applyBatch(listOf(change("manifest.json")), emptyList(), "tok1")
        val other = scope.copy(userRecordName = "_user2")
        val loaded = ZoneCheckpoint.decode(saved.encode(), other)
        assertNull(loaded.token)
        assertTrue(loaded.records.isEmpty())
        assertEquals(other, loaded.scope)
    }

    @Test
    fun corrupt_or_missing_bytes_rebuild_from_nil() {
        assertNull(ZoneCheckpoint.decode("{not json".encodeToByteArray(), scope).token)
        assertTrue(ZoneCheckpoint.decode(null, scope).records.isEmpty())
    }

    @Test
    fun a_kill_after_batch_two_resumes_from_token_two() {
        var live = ZoneCheckpoint.empty(scope)
        live = live.applyBatch(listOf(change("a.json")), emptyList(), "tok1")
        live = live.applyBatch(listOf(change("b.json")), emptyList(), "tok2")
        val persisted = live.encode()
        // the process dies before batch three is applied
        val resumed = ZoneCheckpoint.decode(persisted, scope)
        assertEquals("tok2", resumed.token)
        assertEquals(setOf("a.json", "b.json"), resumed.records.values.map { it.path }.toSet())
        val after = resumed.applyBatch(listOf(change("c.json")), emptyList(), "tok3")
        assertEquals("tok3", after.token)
        assertEquals(3, after.records.size)
    }

    @Test
    fun a_deletion_in_a_batch_removes_the_record() {
        val two = ZoneCheckpoint.empty(scope).applyBatch(listOf(change("a.json"), change("b.json")), emptyList(), "tok1")
        val after = two.applyBatch(emptyList(), listOf(ZoneCheckpoint.recordName("a.json")), "tok2")
        assertEquals(listOf("b.json"), after.toRemoteFiles().map { it.path })
        assertEquals("tok2", after.token)
    }

    @Test
    fun own_saves_and_deletes_are_tracked_without_touching_the_token() {
        val base = ZoneCheckpoint.empty(scope).applyBatch(emptyList(), emptyList(), "tok1")
        val saved = base.upsert(ZoneCheckpoint.recordName("words/x.jpg"), "words/x.jpg", 512L, "ct-1")
        assertEquals("ct-1", saved.changeTag("words__x.jpg"))
        assertEquals("tok1", saved.token)
        val removed = saved.remove("words__x.jpg")
        assertNull(removed.changeTag("words__x.jpg"))
        assertTrue(removed.records.isEmpty())
    }

    @Test
    fun reset_keeps_the_scope_and_clears_everything_else() {
        val full = ZoneCheckpoint.empty(scope).applyBatch(listOf(change("a.json")), emptyList(), "tok1")
        val reset = full.reset()
        assertEquals(scope, reset.scope)
        assertNull(reset.token)
        assertTrue(reset.records.isEmpty())
    }

    @Test
    fun record_names_round_trip_and_never_contain_a_slash() {
        val name = ZoneCheckpoint.recordName("words/word-1723-ab12.jpg")
        assertEquals("words__word-1723-ab12.jpg", name)
        assertTrue('/' !in name)
        assertEquals("words/word-1723-ab12.jpg", ZoneCheckpoint.path(name))
    }
}
