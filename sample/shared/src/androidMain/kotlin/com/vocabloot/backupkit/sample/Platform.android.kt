package com.vocabloot.backupkit.sample

import android.content.Context
import com.vocabloot.backupkit.CloudStorage
import com.vocabloot.backupkit.GoogleDriveStorage

object PlatformHolder {
    lateinit var appContext: Context
}

actual fun platformCloudStorage(): CloudStorage = GoogleDriveStorage(PlatformHolder.appContext)

actual fun appFilesDir(): String = PlatformHolder.appContext.filesDir.absolutePath

actual fun nowEpochMs(): Long = System.currentTimeMillis()
