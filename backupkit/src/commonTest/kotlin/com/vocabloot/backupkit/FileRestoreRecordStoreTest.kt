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

class FileRestoreRecordStoreTest {
    private fun tempPath(name: String): String =
        Path(SystemTemporaryDirectory, "backupkit-restore-${Random.nextLong().toString(16)}", name).toString()

    @Test
    fun round_trips_and_absent_is_null() {
        val store = FileRestoreRecordStore(tempPath("restore/record.json"))
        assertNull(store.load())
        val record = RestoreRecord(
            runId = "r1",
            source = SourceRef("id", "rid", "f"),
            files = listOf(RestoreFileRecord("a.json", "/a.json", required = true, done = true), RestoreFileRecord("b.jpg", "/b.jpg", required = false, attempts = 2)),
            startedAtEpochMs = 1L,
        )
        store.save(record)
        assertEquals(record, store.load())
        store.clear()
        assertNull(store.load())
    }

    @Test
    fun corrupt_file_is_null() {
        val path = tempPath("corrupt.json")
        SystemFileSystem.createDirectories(Path(path).parent!!)
        SystemFileSystem.sink(Path(path)).buffered().use { it.writeString("{nope") }
        assertNull(FileRestoreRecordStore(path).load())
    }
}
