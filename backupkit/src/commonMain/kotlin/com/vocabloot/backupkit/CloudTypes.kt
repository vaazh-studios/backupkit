package com.vocabloot.backupkit

/** Where the user's own copy lives. One per platform. */
public enum class CloudProvider { ICloud, GoogleDrive }

/** Whether the transport can be used right now, without touching the network. */
public sealed interface CloudAvailability {
    public data object Available : CloudAvailability

    /** iOS: no iCloud account on the device. Android: no Google account, or Play Services failed. */
    public data object NoAccount : CloudAvailability

    /** Android only: the one-time Drive permission dialog has not been accepted yet. See `DriveConsent`. */
    public data object NeedsConsent : CloudAvailability
}

/** The only failure vocabulary the library exposes. Render these; never parse messages. */
public enum class CloudError {
    /** The transport reported [CloudAvailability.NoAccount]. */
    NotAvailable,

    /** The transport reported [CloudAvailability.NeedsConsent]. */
    NeedsConsent,

    /** A connection could not be made. Retry later. */
    Offline,

    /** The user's cloud quota is full. Only the user can fix this. */
    StorageFull,

    /** The token or grant was revoked; ask for consent again. */
    AuthRevoked,

    /** A read of a path that does not exist remotely. */
    NotFound,

    /** Anything else: server errors after retries, coordination failures, timeouts. */
    Transport,
}

/** Every transport failure is mapped to a [CloudError] the app can show. */
public class CloudStorageException(
    public val error: CloudError,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * One file as the cloud sees it. [remoteId] is Drive's file id, null on iCloud.
 * [size] is -1 when unknown (an iCloud placeholder that has not been downloaded yet).
 */
public data class RemoteFile(val path: String, val size: Long, val remoteId: String?)
