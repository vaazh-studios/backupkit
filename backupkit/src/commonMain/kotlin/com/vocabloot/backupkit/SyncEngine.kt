package com.vocabloot.backupkit

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Layer 2: ONE reconciliation run per [sync]. Lists the remote, self-heals the state cache, diffs
 * the snapshot against it, uploads size-compared entries, then hash-compared entries, then the
 * marker, then deletes what is no longer in the snapshot. State is saved after every put, so a
 * killed process resumes exactly where it stopped. See docs/contract.md.
 */
@OptIn(ExperimentalTime::class)
public class SyncEngine(
    private val storage: CloudStorage,
    private val stateStore: SyncStateStore,
    private val policy: SyncPolicy,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {

    private val mutex = Mutex()

    /**
     * Runs on the caller's dispatcher. Never throws for cloud failures; they come back as
     * [SyncOutcome.Failed]. Concurrent calls are serialised: a second caller waits for the first run.
     * A [WriteHold] set while a run is in progress stops it before its next put or delete.
     */
    public suspend fun sync(snapshot: SyncSnapshot, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): SyncOutcome = mutex.withLock {
        when (storage.availability()) {
            CloudAvailability.NoAccount -> return@withLock SyncOutcome.Unavailable(UnavailableReason.NoAccount)
            CloudAvailability.NeedsConsent -> return@withLock SyncOutcome.Unavailable(UnavailableReason.NeedsConsent)
            CloudAvailability.Available -> Unit
        }
        if (hold != WriteHold.None) return@withLock SyncOutcome.Unavailable(UnavailableReason.WriteHeld)
        try {
            syncAvailable(snapshot, onProgress)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            val error = (t as? CloudStorageException)?.error ?: CloudError.Transport
            logW(TAG, t) { "sync failed: $error" }
            SyncOutcome.Failed(error = error, cause = t)
        }
    }

    /** The persisted [WriteHold]; [WriteHold.None] when the state is absent or holds an unknown value. */
    public val hold: WriteHold
        get() = stateStore.load().hold.let { name -> WriteHold.entries.firstOrNull { it.name == name } ?: WriteHold.None }

    /** Persists [hold] without touching the cached entries. While it is not [WriteHold.None], [sync] writes nothing. */
    public fun setHold(hold: WriteHold) {
        stateStore.save(stateStore.load().copy(hold = hold.name))
    }

    /**
     * Metadata-only inspection for a restore offer: availability, the listing, and the marker bytes.
     * Never throws for cloud failures; they come back as [RemoteProbe.Failed].
     */
    public suspend fun probe(): RemoteProbe {
        when (storage.availability()) {
            CloudAvailability.NoAccount -> return RemoteProbe.Unavailable(UnavailableReason.NoAccount)
            CloudAvailability.NeedsConsent -> return RemoteProbe.Unavailable(UnavailableReason.NeedsConsent)
            CloudAvailability.Available -> Unit
        }
        val files = try {
            storage.list()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            return RemoteProbe.Failed(errorOf(t))
        }
        if (files.isEmpty()) return RemoteProbe.None
        val marker = files.firstOrNull { it.path == policy.markerPath } ?: return RemoteProbe.NotReady
        val bytes = try {
            storage.readBytes(marker.path, marker.remoteId)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            return RemoteProbe.Failed(errorOf(t))
        } ?: return RemoteProbe.NotReady
        val identity = runCatching { storage.identityKey() }.getOrNull()
        return RemoteProbe.Found(marker = bytes, source = SourceRef(identity, marker.remoteId, sha256Hex(bytes)), files = files)
    }

    private suspend fun syncAvailable(snapshot: SyncSnapshot, onProgress: (Int, Int) -> Unit): SyncOutcome {
        // 1) Remote truth + state self-heal.
        val remote = storage.list().groupBy { it.path }.mapValues { (_, dupes) -> dupes.first() }
        var state = stateStore.load()
        val identity = storage.identityKey() ?: remote[policy.markerPath]?.remoteId ?: ""
        if (state.provider != storage.provider.name || state.identityKey != identity) {
            logI(TAG) { "identity changed ('${state.identityKey}' -> '$identity'); resetting sync state" }
            state = SyncState(provider = storage.provider.name, identityKey = identity, hold = state.hold)
        }
        state = state.copy(
            entries = state.entries.filterKeys { it in remote }.mapValues { (path, cached) ->
                cached.copy(remoteId = remote.getValue(path).remoteId ?: cached.remoteId)
            } + remote.filterKeys { it !in state.entries }.mapValues { (_, r) ->
                // Present remotely but unknown to us: trust size-compared entries by size, force hashed re-upload.
                SyncedEntry(size = r.size, hash = null, remoteId = r.remoteId)
            },
        )

        // 2) Desired set.
        val entries = snapshot.entries
        require(entries.count { it.path == policy.markerPath } <= 1) { "snapshot declares the marker more than once" }
        val marker = entries.firstOrNull { it.path == policy.markerPath }
        val declared = entries.associateBy { it.path }
        val present = entries.filter { it.source !is SyncSource.Absent && it.path != policy.markerPath }

        val sizeUploads = present.filter { it.hash == null }.filter { d ->
            val cached = state.entries[d.path]
            val remoteSize = remote[d.path]?.size
            cached == null || !(remoteSize == null || remoteSize < 0 || remoteSize == d.size) || cached.size != d.size
        }
        val hashedUploads = present.filter { it.hash != null }.filter { d -> state.entries[d.path]?.hash != d.hash }
        val markerNeeded = marker != null && marker.source !is SyncSource.Absent &&
            (sizeUploads.isNotEmpty() || hashedUploads.isNotEmpty() || policy.markerPath !in remote)
        val deletes = state.entries.keys.filter { path -> path != policy.markerPath && path !in declared }

        // A device that never completed a sync must never publish an empty set over a backup it did not produce.
        if (policy.guardEmptyOverExisting && snapshot.isEmpty && state.lastSuccessEpochMs == null &&
            remote.keys.any { it != policy.markerPath }
        ) {
            logW(TAG) { "empty snapshot on a device that never synced while the cloud holds a set; leaving it untouched" }
            return SyncOutcome.Unavailable(UnavailableReason.RestorePending)
        }

        // A run that would delete most of a healthy remote set is refused until the app confirms it.
        policy.shrinkGuard?.let { guard ->
            val remoteCount = state.entries.keys.count { it != policy.markerPath }
            if (!snapshot.allowShrink && remoteCount >= guard.minRemoteEntries &&
                deletes.size.toDouble() / remoteCount > guard.maxDeleteFraction
            ) {
                logW(TAG) { "snapshot would delete ${deletes.size} of $remoteCount remote entries; refusing without allowShrink" }
                return SyncOutcome.Unavailable(UnavailableReason.ShrinkSuspected)
            }
        }

        val total = sizeUploads.size + hashedUploads.size + (if (markerNeeded) 1 else 0) + deletes.size
        var done = 0
        onProgress(done, total)

        // 3) Uploads: size-compared first, hashed in snapshot order, marker last.
        val uploads = sizeUploads + hashedUploads + listOfNotNull(marker.takeIf { markerNeeded })
        for (d in uploads) {
            if (hold != WriteHold.None) return SyncOutcome.Unavailable(UnavailableReason.WriteHeld)
            val existingId = state.entries[d.path]?.remoteId
            val mime = policy.mimeTypeOf(d.path)
            val remoteId = when (val source = d.source) {
                is SyncSource.Bytes -> storage.writeBytes(path = d.path, bytes = source.bytes, mimeType = mime, existingRemoteId = existingId)
                is SyncSource.LocalFile -> storage.writeFile(path = d.path, localPath = source.path, mimeType = mime, existingRemoteId = existingId)
                SyncSource.Absent -> error("unreachable: Absent entries are never uploaded")
            }
            state = state.copy(entries = state.entries + (d.path to SyncedEntry(size = d.size, hash = d.hash, remoteId = remoteId ?: existingId)))
            if (d.path == policy.markerPath && storage.identityKey() == null) {
                // Drive has no account identity for us; the marker's file id IS the identity.
                // Adopt it now so the NEXT run does not mistake our own fresh marker for an account switch.
                (remoteId ?: existingId)?.let { state = state.copy(identityKey = it) }
            }
            persist(state)
            done += 1
            onProgress(done, total)
        }

        // 4) Deletes after uploads; a failed delete is logged and retried next run.
        for (path in deletes) {
            if (hold != WriteHold.None) return SyncOutcome.Unavailable(UnavailableReason.WriteHeld)
            val ok = runCatching { storage.delete(path = path, remoteId = state.entries[path]?.remoteId) }
                .onFailure { logW(TAG, it) { "delete failed for $path (will retry next run)" } }
                .isSuccess
            if (ok) {
                state = state.copy(entries = state.entries - path)
                persist(state)
            }
            done += 1
            onProgress(done, total)
        }

        state = state.copy(lastSuccessEpochMs = clock(), lastEntryCount = present.size)
        persist(state)
        logI(TAG) { "sync ok entries=${present.size} uploads=${uploads.size} deletes=${deletes.size}" }
        return SyncOutcome.Synced(entryCount = present.size)
    }

    /** Saves [state] while keeping whatever [WriteHold] was persisted meanwhile: the hold is owned by the app, not by a run. */
    private fun persist(state: SyncState) {
        stateStore.save(state.copy(hold = stateStore.load().hold))
    }

    private companion object {
        const val TAG = "SyncEngine"

        fun errorOf(t: Throwable): CloudError = (t as? CloudStorageException)?.error ?: CloudError.Transport
    }
}
