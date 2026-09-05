package com.vocabloot.backupkit.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.activity.compose.rememberLauncherForActivityResult
import com.vocabloot.backupkit.DriveConsent
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PlatformHolder.appContext = applicationContext
        setContent {
            val consent = remember { DriveConsent(applicationContext) }
            val pending = remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
            val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
                pending.value?.invoke(consent.wasGranted(result.data))
                pending.value = null
            }
            val scope = rememberCoroutineScope()
            App(requestConsent = { onResult ->
                scope.launch {
                    when (val r = consent.request()) {
                        is DriveConsent.Request.Needed -> {
                            pending.value = onResult
                            launcher.launch(IntentSenderRequest.Builder(r.intentSender).build())
                        }
                        DriveConsent.Request.AlreadyGranted -> onResult(true)
                        is DriveConsent.Request.Failed -> onResult(false)
                    }
                }
            })
        }
    }
}
