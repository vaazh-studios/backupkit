package com.vocabloot.backupkit

import kotlinx.serialization.Serializable

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
    /** The user confirmed that this device's smaller set should replace the backup; the shrink guard steps aside for this run. */
    val allowShrink: Boolean = false,
)

/**
 * Refuses a run that would delete more than [maxDeleteFraction] of the remote set once the remote
 * holds at least [minRemoteEntries] non-marker files. A device that lost most of its data, or an app
 * bug that projected an empty model, must not turn a healthy backup into a copy of the damage. The
 * run answers [UnavailableReason.ShrinkSuspected] and writes nothing; the app asks the user and
 * retries with `SyncSnapshot(allowShrink = true)`.
 */
public data class ShrinkGuard(
    val maxDeleteFraction: Double = 0.5,
    val minRemoteEntries: Int = 4,
) {
    init {
        require(maxDeleteFraction in 0.0..1.0) { "maxDeleteFraction must be within 0..1" }
        require(minRemoteEntries >= 1) { "minRemoteEntries must be at least 1" }
    }
}

public data class SyncPolicy(
    /** Uploaded LAST, only after everything else succeeded; its presence means "the remote set is complete". */
    val markerPath: String,
    /** An empty snapshot on a device that never synced must not overwrite an existing remote set. */
    val guardEmptyOverExisting: Boolean = true,
    /** Refuses runs that would delete most of the remote set; null disables it. See [ShrinkGuard]. */
    val shrinkGuard: ShrinkGuard? = ShrinkGuard(),
    val mimeTypeOf: (path: String) -> String = ::defaultMimeType,
)

public enum class UnavailableReason {
    NoAccount,
    NeedsConsent,

    /** The snapshot is empty, this device never synced, and the cloud holds a set: restore first or add data on purpose. */
    RestorePending,

    /** A [WriteHold] is set (a restore is running or incomplete); nothing was written. */
    WriteHeld,

    /**
     * The snapshot would delete more than the [ShrinkGuard] allows of the remote set; nothing was
     * written. Ask the user whether this device's smaller set should replace the backup, then retry
     * with `SyncSnapshot(allowShrink = true)`, or offer a restore instead.
     */
    ShrinkSuspected,
}

/** Parks [SyncEngine.sync] while a restore runs or is incomplete. Persisted in [SyncState]. */
public enum class WriteHold { None, RestoreRunning, RestoreIncomplete }

/** Pins the remote set a restore was offered from, so a later resume can detect a switch to another backup. */
@Serializable
public data class SourceRef(val identityKey: String?, val markerRemoteId: String?, val markerFingerprint: String) {
    /** True when both references describe the same remote set. */
    public fun matches(other: SourceRef): Boolean =
        identityKey == other.identityKey && markerFingerprint == other.markerFingerprint
}

/** Typed, metadata-only inspection of the remote set, for a restore offer. */
public sealed interface RemoteProbe {
    /** A complete set: the marker is readable. [marker] is its bytes; parse them with your own schema. */
    public class Found(public val marker: ByteArray, public val source: SourceRef, public val files: List<RemoteFile>) : RemoteProbe

    /** Nothing remote. */
    public data object None : RemoteProbe

    /** Files exist but the marker is absent or not downloadable yet: a writer never finished, or iCloud is still fetching. */
    public data object NotReady : RemoteProbe

    public data class Unavailable(val reason: UnavailableReason) : RemoteProbe

    public data class Failed(val error: CloudError) : RemoteProbe
}

public sealed interface SyncOutcome {
    /** [entryCount] is the number of non-marker entries present in the snapshot. */
    public data class Synced(val entryCount: Int) : SyncOutcome
    public data class Unavailable(val reason: UnavailableReason) : SyncOutcome
    public data class Failed(val error: CloudError, val cause: Throwable) : SyncOutcome
}

