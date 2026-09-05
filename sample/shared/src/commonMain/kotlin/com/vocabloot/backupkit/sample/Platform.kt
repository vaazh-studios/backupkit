package com.vocabloot.backupkit.sample

import com.vocabloot.backupkit.CloudStorage

/** The platform transport. Android needs a Context, so the app passes its own instance in via [PlatformHolder]. */
expect fun platformCloudStorage(): CloudStorage

/** Absolute path of a writable app-private directory for the notes file and the sync state. */
expect fun appFilesDir(): String

expect fun nowEpochMs(): Long
