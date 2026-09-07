@file:OptIn(com.vocabloot.backupkit.ExperimentalRestoreApi::class)

package com.vocabloot.backupkit.sample

import com.vocabloot.backupkit.FileSyncStateStore
import com.vocabloot.backupkit.FileRestoreRecordStore
import com.vocabloot.backupkit.RemoteProbe
import com.vocabloot.backupkit.RestoreEngine
import com.vocabloot.backupkit.RestoreFile
import com.vocabloot.backupkit.RestoreOutcome
import com.vocabloot.backupkit.RestorePlan
import com.vocabloot.backupkit.WriteHold
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

    private val restoreEngine = RestoreEngine(
        engine = engine,
        storage = storage,
        recordStore = FileRestoreRecordStore(Path(appFilesDir(), "backupkit-restore.json").toString()),
        clock = ::nowEpochMs,
    )

    /** Typed inspection for the first-launch offer: Found carries the header bytes and a pinned source. */
    suspend fun probe(): RemoteProbe = engine.probe()

    /**
     * The restore contract in four lines: hold writes, download the pinned set (notes.json is
     * required, the header is optional here), import, release the hold. A kill mid-way leaves a
     * record that `restoreEngine.resume()` continues from.
     */
    suspend fun restore(found: RemoteProbe.Found): List<String> {
        engine.setHold(WriteHold.RestoreRunning)
        val plan = RestorePlan(
            source = found.source,
            files = listOf(
                RestoreFile(path = "notes.json", toLocalPath = notesPath.toString(), remoteId = found.files.firstOrNull { it.path == "notes.json" }?.remoteId, required = true),
                RestoreFile(path = "backup.json", toLocalPath = Path(appFilesDir(), "restored-backup.json").toString(), required = false),
            ),
        )
        return when (restoreEngine.start(plan)) {
            is RestoreOutcome.Completed, is RestoreOutcome.Partial -> {
                engine.setHold(WriteHold.None)
                restoreEngine.clear()
                load()
            }
            is RestoreOutcome.Failed -> {
                engine.setHold(WriteHold.RestoreIncomplete) // keep the cloud copy safe until the user retries
                emptyList()
            }
        }
    }

    fun parseHeader(bytes: ByteArray): Header = json.decodeFromString(bytes.decodeToString())
}
