package com.vocabloot.backupkit.sample

import com.vocabloot.backupkit.CloudStorage
import com.vocabloot.backupkit.CloudKitStorage
import com.vocabloot.backupkit.ICloudStorage
import platform.Foundation.NSDate
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask
import platform.Foundation.timeIntervalSince1970

/**
 * Flip to true after enabling the CloudKit service on the sample's container in the
 * Apple Developer portal and in `iosApp.entitlements` (`icloud-services = [CloudKit]`).
 * Debug builds talk to the Development environment, hence the `environment` salt.
 */
private const val USE_CLOUDKIT = false

actual fun platformCloudStorage(): CloudStorage = if (USE_CLOUDKIT) {
    CloudKitStorage(
        checkpointPath = "${appFilesDir()}/notes-sample/cloudkit-checkpoint.json",
        cacheDirectory = "${appFilesDir()}/notes-sample/cloudkit-cache",
        zoneName = "notes-sample",
        environment = "development",
    )
} else {
    ICloudStorage(folder = "notes-sample")
}

actual fun appFilesDir(): String =
    NSFileManager.defaultManager.URLsForDirectory(NSDocumentDirectory, NSUserDomainMask).first().let { (it as platform.Foundation.NSURL).path!! }

actual fun nowEpochMs(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()
