package com.vocabloot.backupkit

import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Silent token source for the Drive app-data scope through Play Services' AuthorizationClient.
 * After the user grants once (see [DriveConsent]), `authorize` returns a cached one-hour access
 * token with no UI; before that it reports [DriveToken.NeedsConsent]. No account identity is read
 * or stored.
 */
public class PlayServicesTokenProvider(private val context: Context) : DriveTokenProvider {

    override suspend fun accessToken(): DriveToken = suspendCancellableCoroutine { continuation ->
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DRIVE_APPDATA_SCOPE)))
            .build()
        Identity.getAuthorizationClient(context)
            .authorize(request)
            .addOnSuccessListener { result ->
                val token = result.accessToken
                val outcome = when {
                    result.hasResolution() -> DriveToken.NeedsConsent
                    token != null -> DriveToken.Value(token)
                    else -> DriveToken.Failed(IllegalStateException("authorize returned no token and no resolution"))
                }
                if (continuation.isActive) continuation.resume(outcome)
            }
            .addOnFailureListener { error ->
                logW(TAG, error) { "Drive authorize failed" }
                if (continuation.isActive) continuation.resume(DriveToken.Failed(error))
            }
    }

    override suspend fun invalidate(token: String): Unit = suspendCancellableCoroutine { continuation ->
        Identity.getAuthorizationClient(context)
            .clearToken(ClearTokenRequest.builder().setToken(token).build())
            .addOnCompleteListener { if (continuation.isActive) continuation.resume(Unit) }
    }

    private companion object {
        const val TAG = "PlayServicesTokenProvider"
    }
}
