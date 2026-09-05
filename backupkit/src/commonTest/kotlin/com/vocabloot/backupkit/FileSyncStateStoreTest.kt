package com.vocabloot.backupkit

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.writeString
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FileSyncStateStoreTest {

    private fun tempPath(name: String): String =
        Path(SystemTemporaryDirectory, "backupkit-test-${Random.nextLong().toString(16)}", name).toString()

    @Test
    fun round_trips_state_and_creates_parent_directories() {
        val store = FileSyncStateStore(tempPath("state/mirror-state.json"))
        val state = SyncState(
            provider = "ICloud",
            identityKey = "abc",
            entries = mapOf("a.json" to SyncedEntry(size = 3, hash = "h", remoteId = null)),
            lastSuccessEpochMs = 42L,
            lastEntryCount = 1,
        )

        store.save(state)

        assertEquals(state, store.load())
    }

    @Test
    fun absent_file_is_an_empty_state() {
        assertEquals(SyncState(), FileSyncStateStore(tempPath("missing.json")).load())
    }

    @Test
    fun corrupt_file_is_an_empty_state() {
        val path = tempPath("corrupt.json")
        SystemFileSystem.createDirectories(Path(path).parent!!)
        SystemFileSystem.sink(Path(path)).buffered().use { it.writeString("{not json") }

        assertEquals(SyncState(), FileSyncStateStore(path).load())
    }

    @Test
    fun clear_removes_the_file_and_leaves_no_temp_sibling() {
        val path = tempPath("state.json")
        val store = FileSyncStateStore(path)
        store.save(SyncState(provider = "GoogleDrive"))

        store.clear()

        assertNull(SystemFileSystem.metadataOrNull(Path(path)))
        assertEquals(emptyList(), SystemFileSystem.list(Path(path).parent!!).map { it.name })
    }
}
