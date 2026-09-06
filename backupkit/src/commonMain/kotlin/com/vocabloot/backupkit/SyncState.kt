package com.vocabloot.backupkit

import com.vocabloot.backupkit.internal.LocalFiles
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What the last successful writes left remote. A CACHE: every [SyncEngine.sync] re-lists the
 * remote and reconciles against it, so a lost or stale state only costs a re-upload, never
 * correctness.
 */
@Serializable
public data class SyncState(
    val provider: String = "",
    val identityKey: String = "",
    val entries: Map<String, SyncedEntry> = emptyMap(),
    val lastSuccessEpochMs: Long? = null,
    val lastEntryCount: Int = 0,
    /** [WriteHold] name; a string so an unknown future value degrades to [WriteHold.None] on read. */
    val hold: String = "None",
)

/** [hash] is sha256-hex for hash-compared entries, null for size-compared ones (identity = path + size). */
@Serializable
public data class SyncedEntry(
    val size: Long,
    val hash: String? = null,
    val remoteId: String? = null,
)

/** Persists [SyncState] between runs. Implement it yourself or use [FileSyncStateStore]. */
public interface SyncStateStore {
    public fun load(): SyncState
    public fun save(state: SyncState)
    public fun clear()
}

/**
 * JSON file at [path], written atomically (temp + rename). A corrupt or absent file is an empty
 * state; the next sync self-heals from the remote listing.
 */
public class FileSyncStateStore(private val path: String) : SyncStateStore {

    private val json = Json { ignoreUnknownKeys = true }

    override fun load(): SyncState {
        val bytes = LocalFiles.readAllBytes(path) ?: return SyncState()
        return runCatching { json.decodeFromString(SyncState.serializer(), bytes.decodeToString()) }
            .getOrElse {
                logW(TAG, it) { "sync state unreadable; starting empty" }
                SyncState()
            }
    }

    override fun save(state: SyncState) {
        LocalFiles.writeAllBytes(path, json.encodeToString(SyncState.serializer(), state).encodeToByteArray())
    }

    override fun clear(): Unit = LocalFiles.delete(path)

    private companion object {
        const val TAG = "FileSyncStateStore"
    }
}
