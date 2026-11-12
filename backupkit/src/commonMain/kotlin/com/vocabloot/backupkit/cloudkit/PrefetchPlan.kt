package com.vocabloot.backupkit.cloudkit

/**
 * How `CloudKitStorage.prefetch` hands a restore's records to iCloud: every batch at once, each a long-lived fetch
 * under one operation name, so iCloud's own daemon keeps downloading while the app is suspended or closed, and the
 * next prefetch can find those fetches again by name.
 */
internal object PrefetchPlan {
    /** The `NSOperation.name` of every long-lived prefetch; other long-lived operations (uploads) are never touched. */
    const val OPERATION_NAME: String = "backupkit.prefetch"

    /** Records per long-lived fetch: few operations for iCloud to queue, small enough that one failure costs little. */
    const val BATCH: Int = 32

    /** The record names still to fetch, in order and without repeats, split into batches of at most [batch]. */
    fun batches(recordNames: List<String>, isCached: (String) -> Boolean, batch: Int = BATCH): List<List<String>> {
        require(batch > 0) { "batch must be positive" }
        return recordNames.distinct().filterNot(isCached).chunked(batch)
    }

    /** A long-lived operation this library submitted as a prefetch. */
    fun isOwnPrefetch(operationName: String?): Boolean = operationName == OPERATION_NAME
}
