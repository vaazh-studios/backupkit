package com.vocabloot.backupkit

/** A short-lived OAuth access token for the `drive.appdata` scope. */
public sealed interface DriveToken {
    public data class Value(val accessToken: String) : DriveToken

    /** The user has not accepted Google's one-time permission dialog yet. Run [DriveConsent]. */
    public data object NeedsConsent : DriveToken

    public data class Failed(val cause: Throwable) : DriveToken
}

/**
 * Supplies Drive access tokens. The default, [PlayServicesTokenProvider], needs no sign-in UI.
 * Apps that already run Google Sign-In can plug their own token in here instead.
 */
public interface DriveTokenProvider {
    public suspend fun accessToken(): DriveToken

    /** Called after Drive rejected [token] with 401; drop any cache so the next call fetches a fresh one. */
    public suspend fun invalidate(token: String) {}
}

/** The Drive scope BackupKit uses: the hidden, app-private folder only. */
public const val DRIVE_APPDATA_SCOPE: String = "https://www.googleapis.com/auth/drive.appdata"
