package com.vocabloot.backupkit.sample

import com.vocabloot.backupkit.FileSyncStateStore
import com.vocabloot.backupkit.RemoteInspection
import com.vocabloot.backupkit.SyncEngine
import com.vocabloot.backupkit.SyncEntry
import com.vocabloot.backupkit.SyncOutcome
import com.vocabloot.backupkit.SyncPolicy
import com.vocabloot.backupkit.SyncSnapshot
import com.vocabloot.backupkit.SyncSource
import com.vocabloot.backupkit.sha256Hex
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.io.write
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class Header(val noteCount: Int, val createdAtEpochMs: Long)

/** The app's data: a list of strings in `notes.json`, mirrored with a `backup.json` marker. */
class NotesBackup {
    private val json = Json { ignoreUnknownKeys = true }
    private val notesPath = Path(appFilesDir(), "notes.json")
    val storage = platformCloudStorage()
    val engine = SyncEngine(
        storage = storage,
        stateStore = FileSyncStateStore(Path(appFilesDir(), "backupkit-state.json").toString()),
        policy = SyncPolicy(markerPath = "backup.json"),
        clock = ::nowEpochMs,
    )

    fun load(): List<String> {
        if (!SystemFileSystem.exists(notesPath)) return emptyList()
        val text = SystemFileSystem.source(notesPath).buffered().use { it.readByteArray() }.decodeToString()
        return json.decodeFromString(text)
    }

    fun save(notes: List<String>) {
        SystemFileSystem.sink(notesPath).buffered().use { it.write(json.encodeToString(notes).encodeToByteArray()) }
    }

    suspend fun sync(notes: List<String>, onProgress: (Int, Int) -> Unit): SyncOutcome {
        val notesBytes = json.encodeToString(notes).encodeToByteArray()
        val header = json.encodeToString(Header(noteCount = notes.size, createdAtEpochMs = nowEpochMs())).encodeToByteArray()
        return engine.sync(
            SyncSnapshot(
                listOf(
                    SyncEntry("notes.json", SyncSource.Bytes(notesBytes), notesBytes.size.toLong(), hash = sha256Hex(notesBytes)),
                    SyncEntry("backup.json", SyncSource.Bytes(header), header.size.toLong(), hash = sha256Hex(header)),
                ),
            ),
            onProgress,
        )
    }

    suspend fun inspect(): RemoteInspection = engine.inspect()

    suspend fun restore(): List<String> {
        val bytes = storage.readBytes("notes.json") ?: return emptyList()
        val notes: List<String> = json.decodeFromString(bytes.decodeToString())
        save(notes)
        return notes
    }

    fun parseHeader(bytes: ByteArray): Header = json.decodeFromString(bytes.decodeToString())
}
