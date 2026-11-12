package com.vocabloot.backupkit.cloudkit

/**
 * Moving a fetched asset out of CloudKit's staging area, from inside CloudKit's per-record callback.
 *
 * Nothing may throw there: an exception in a CloudKit callback aborts the app. A long-lived fetch that CloudKit
 * replays on a later launch hands back records whose staged files iOS has already deleted, so in 0.3.1 every
 * launch crashed on the same replay. A record that cannot be moved is skipped and fetched again later.
 */
internal object StagedAssets {
    /** True when [staged] now sits at [target]; false, never an exception, when it is gone or the move failed. */
    fun moveIntoCache(staged: String, target: String, exists: (String) -> Boolean, move: (String, String) -> Unit): Boolean =
        runCatching { exists(staged) && run { move(staged, target); true } }.getOrDefault(false)
}
