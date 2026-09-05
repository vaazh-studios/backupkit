package com.vocabloot.backupkit

import android.content.Context
import android.content.Intent
import android.content.IntentSender
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Google's one-time permission dialog for the app-data folder. Call [request]; when it returns
 * [Request.Needed], launch the [IntentSender] with `ActivityResultContracts.StartIntentSenderForResult`
 * and hand the result's data to [wasGranted]. No Compose dependency: the app owns the launcher.
 */
public class DriveConsent(private val context: Context) {

    public sealed interface Request {
        /** The grant already exists (for example a reinstall with the same account). */
        public data object AlreadyGranted : Request
        public data class Needed(val intentSender: IntentSender) : Request
        public data class Failed(val cause: Throwable) : Request
    }

    public suspend fun request(): Request = suspendCancellableCoroutine { continuation ->
        val request = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(DRIVE_APPDATA_SCOPE))).build()
        Identity.getAuthorizationClient(context)
            .authorize(request)
            .addOnSuccessListener { result ->
                val pendingIntent = result.pendingIntent
                val outcome = if (result.hasResolution() && pendingIntent != null) {
                    Request.Needed(pendingIntent.intentSender)
                } else {
                    Request.AlreadyGranted
                }
                if (continuation.isActive) continuation.resume(outcome)
            }
            .addOnFailureListener { error ->
                if (continuation.isActive) continuation.resume(Request.Failed(error))
            }
    }

    /** Parse the activity result of the consent dialog. False when declined or failed. */
    public fun wasGranted(resultData: Intent?): Boolean = runCatching {
        Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(resultData).accessToken != null
    }.getOrDefault(false)
}
