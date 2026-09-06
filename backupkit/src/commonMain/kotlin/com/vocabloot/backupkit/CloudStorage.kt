package com.vocabloot.backupkit

/**
 * Layer 1: filesystem-style access to the user's own cloud.
 *
 * Paths are relative and `/`-separated (`notes/2026-09.json`). iCloud stores them as nested
 * files; Google Drive app-data is flat, so the path string is used verbatim as the file name.
 * Every write is complete-or-absent from a reader's point of view. All functions are safe to
 * call from any dispatcher; they never block the main thread.
 *
 * Failures throw [CloudStorageException]. `remoteId` parameters are optional hints that save a
 * [list] round trip on Drive; iCloud ignores them.
 */
public interface CloudStorage {
    public val provider: CloudProvider

    /** Cheap. Never touches the network. */
    public suspend fun availability(): CloudAvailability

    /**
     * Stable per (device, cloud account) when the platform can tell, so a sync engine can detect
     * an account switch. Null on Drive, where the engine keys identity off the marker file's id.
     */
    public suspend fun identityKey(): String?

    /** Every file, deduplicated by path (newest wins). */
    public suspend fun list(): List<RemoteFile>

    public suspend fun exists(path: String): Boolean

    /** Create or replace from a local file. Returns the remote id on Drive, null on iCloud. */
    public suspend fun writeFile(path: String, localPath: String, mimeType: String, existingRemoteId: String? = null): String?

    /** Create or replace from memory. Returns the remote id on Drive, null on iCloud. */
    public suspend fun writeBytes(path: String, bytes: ByteArray, mimeType: String, existingRemoteId: String? = null): String?

    /** Whole contents, or null when absent. Forces an iCloud download first. */
    public suspend fun readBytes(path: String, remoteId: String? = null): ByteArray?

    /** Downloads into [toLocalPath], replacing it. Throws [CloudError.NotFound] when absent. */
    public suspend fun downloadFile(path: String, toLocalPath: String, remoteId: String? = null)

    /** No-op when already absent. */
    public suspend fun delete(path: String, remoteId: String? = null)

    /**
     * Starts downloading every not-yet-local file in [paths] at once, without waiting. iCloud
     * only; Drive has nothing to prefetch. Never throws. Call it before a restore so files
     * download in parallel instead of one at a time.
     */
    public suspend fun prefetch(paths: List<String>) {}
}
