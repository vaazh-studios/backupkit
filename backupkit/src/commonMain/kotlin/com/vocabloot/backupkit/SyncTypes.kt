package com.vocabloot.backupkit

/** Where an entry's bytes come from. */
public sealed interface SyncSource {
    /** A local file, uploaded as a whole. */
    public data class LocalFile(val path: String) : SyncSource

    /** In-memory bytes, typically a small generated JSON file. */
    public class Bytes(public val bytes: ByteArray) : SyncSource

    /** Declared but missing locally: never uploaded, and its remote copy is never deleted (it may be the last one). */
    public data object Absent : SyncSource
}

/**
 * One file the app wants mirrored. [hash] (use [sha256Hex]) makes the entry hash-compared;
 * null makes it size-compared, which suits write-once media. Hash-compared entries upload in
 * snapshot order after the size-compared ones.
 */
public data class SyncEntry(val path: String, val source: SyncSource, val size: Long, val hash: String? = null)

/**
 * The complete desired remote set. Anything the engine knows about that is not here gets deleted.
 *
 * [isEmpty] tells the engine the app's data is empty even when scaffold files (an empty manifest,
 * a settings file) are still listed; it drives the empty-over-existing guard. The default is
 * "no entry has a source", which is right only for apps without scaffold files.
 */
public data class SyncSnapshot(
    val entries: List<SyncEntry>,
    val isEmpty: Boolean = entries.none { it.source !is SyncSource.Absent },
)

public data class SyncPolicy(
    /** Uploaded LAST, only after everything else succeeded; its presence means "the remote set is complete". */
    val markerPath: String,
    /** An empty snapshot on a device that never synced must not overwrite an existing remote set. */
    val guardEmptyOverExisting: Boolean = true,
    val mimeTypeOf: (path: String) -> String = ::defaultMimeType,
)

public enum class UnavailableReason {
    NoAccount,
    NeedsConsent,

    /** The snapshot is empty, this device never synced, and the cloud holds a set: restore first or add data on purpose. */
    RestorePending,
}

public sealed interface SyncOutcome {
    /** [entryCount] is the number of non-marker entries present in the snapshot. */
    public data class Synced(val entryCount: Int) : SyncOutcome
    public data class Unavailable(val reason: UnavailableReason) : SyncOutcome
    public data class Failed(val error: CloudError, val cause: Throwable) : SyncOutcome
}

/** What a fresh install sees remotely. [marker] is null when the remote set is absent or incomplete. */
public class RemoteInspection(
    public val availability: CloudAvailability,
    public val files: List<RemoteFile>,
    public val marker: ByteArray?,
)
