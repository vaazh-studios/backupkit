package com.vocabloot.backupkit.sample

import com.vocabloot.backupkit.CloudStorage
import com.vocabloot.backupkit.ICloudStorage
import platform.Foundation.NSDate
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask
import platform.Foundation.timeIntervalSince1970

actual fun platformCloudStorage(): CloudStorage = ICloudStorage(folder = "notes-sample")

actual fun appFilesDir(): String =
    NSFileManager.defaultManager.URLsForDirectory(NSDocumentDirectory, NSUserDomainMask).first().let { (it as platform.Foundation.NSURL).path!! }

actual fun nowEpochMs(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()
