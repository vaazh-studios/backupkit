package com.vocabloot.backupkit

import com.vocabloot.backupkit.cloudkit.CheckpointScope
import com.vocabloot.backupkit.cloudkit.RecordChange
import com.vocabloot.backupkit.cloudkit.ZoneCheckpoint
import com.vocabloot.backupkit.internal.LocalFiles
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import platform.CloudKit.CKAccountChangedNotification
import platform.CloudKit.CKAccountStatusAvailable
import platform.CloudKit.CKAsset
import platform.CloudKit.CKContainer
import platform.CloudKit.CKCurrentUserDefaultName
import platform.CloudKit.CKDatabase
import platform.CloudKit.CKDatabaseOperation
import platform.CloudKit.CKErrorDomain
import platform.CloudKit.CKErrorRetryAfterKey
import platform.CloudKit.CKFetchRecordZoneChangesConfiguration
import platform.CloudKit.CKFetchRecordZoneChangesOperation
import platform.CloudKit.CKFetchRecordsOperation
import platform.CloudKit.CKModifyRecordZonesOperation
import platform.CloudKit.CKModifyRecordsOperation
import platform.CloudKit.CKOperationConfiguration
import platform.CloudKit.CKPartialErrorsByItemIDKey
import platform.CloudKit.CKRecord
import platform.CloudKit.CKRecordID
import platform.CloudKit.CKRecordSaveAllKeys
import platform.CloudKit.CKRecordZone
import platform.CloudKit.CKRecordZoneID
import platform.CloudKit.CKServerChangeToken
import platform.CloudKit.accountStatusWithCompletionHandler
import platform.CloudKit.fetchUserRecordIDWithCompletionHandler
import platform.CloudKit.privateCloudDatabase
import platform.Foundation.NSData
import platform.Foundation.NSDictionary
import platform.Foundation.NSError
import platform.Foundation.NSKeyedArchiver
import platform.Foundation.NSKeyedUnarchiver
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSQualityOfServiceUserInitiated
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.allValues
import platform.Foundation.setValue
import platform.Foundation.valueForKey
import platform.Foundation.base64EncodedStringWithOptions
import platform.Foundation.create
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * CloudKit transport: one custom zone in the user's private database, one record
 * per file, the bytes as a `CKAsset`. Unlike the iCloud Drive container there are
 * no placeholder files: a save completes when Apple's server has the record, and
 * a read is a network fetch with a definite outcome.
 *
 * - `remoteId` is always null and sizes are always known.
 * - [list] fetches zone changes since the last checkpoint ([checkpointPath], one
 *   atomic JSON file holding the change token together with the records it
 *   stands for) and never downloads assets.
 * - [readBytes] and [downloadFile] serve from a local cache under [cacheDirectory]
 *   when the cached copy matches the record's change tag; [prefetch] fills that
 *   cache eight records per request, two requests in flight.
 * - Pass [environment] as `"development"` for debug builds and `"production"`
 *   otherwise: Xcode's debug builds talk to the Development container, and the
 *   two must never share a checkpoint on one device.
 *
 * Entitlements: `com.apple.developer.icloud-services = [CloudKit]` and the container
 * in `com.apple.developer.icloud-container-identifiers`. The `BackupEntry` record
 * type and the zone are created automatically in the Development environment;
 * deploy the schema to Production in the CloudKit console before a TestFlight build.
 */
@OptIn(ExperimentalForeignApi::class)
public class CloudKitStorage(
    private val checkpointPath: String,
    private val cacheDirectory: String,
    private val containerIdentifier: String? = null,
    private val zoneName: String = "backupkit",
    private val recordType: String = "BackupEntry",
    private val environment: String = "",
) : CloudStorage {

    override val provider: CloudProvider = CloudProvider.ICloud

    private val container: CKContainer = containerIdentifier?.let { CKContainer.containerWithIdentifier(it) } ?: CKContainer.defaultContainer()
    private val database: CKDatabase get() = container.privateCloudDatabase
    private val zoneId = CKRecordZoneID(zoneName = zoneName, ownerName = CKCurrentUserDefaultName)

    private val mutex = Mutex()
    private val prefetchGate = Semaphore(MAX_FETCHES_IN_FLIGHT)
    private var checkpoint: ZoneCheckpoint? = null
    private var zoneReady = false
    private var cachedUserRecordName: String? = null

    init {
        NSNotificationCenter.defaultCenter.addObserverForName(name = CKAccountChangedNotification, `object` = null, queue = null) { _ ->
            cachedUserRecordName = null
            checkpoint = null
            zoneReady = false
        }
    }

    // region availability and identity

    override suspend fun availability(): CloudAvailability = suspendCancellableCoroutine { cont ->
        container.accountStatusWithCompletionHandler { status, _ ->
            cont.resume(if (status == CKAccountStatusAvailable) CloudAvailability.Available else CloudAvailability.NoAccount)
        }
    }

    override suspend fun identityKey(): String? {
        cachedUserRecordName?.let { return it }
        val name = suspendCancellableCoroutine<String?> { cont ->
            container.fetchUserRecordIDWithCompletionHandler { recordId, _ -> cont.resume(recordId?.recordName) }
        }
        cachedUserRecordName = name
        return name
    }

    private suspend fun scope(): CheckpointScope = CheckpointScope(
        container = container.containerIdentifier ?: "default",
        zone = zoneName,
        environment = environment,
        userRecordName = identityKey() ?: throw CloudStorageException(CloudError.NotAvailable, "CloudKit: no iCloud account"),
    )

    // endregion

    // region listing

    override suspend fun list(): List<RemoteFile> = mutex.withLock { refresh().toRemoteFiles() }

    override suspend fun exists(path: String): Boolean =
        mutex.withLock { refresh().records.containsKey(ZoneCheckpoint.recordName(path)) }

    /** Zone changes since the checkpoint, applied batch by batch; a lost zone or token starts over once. */
    private suspend fun refresh(): ZoneCheckpoint {
        ensureZone()
        var current = checkpoint ?: loadCheckpoint()
        current = try {
            fetchChanges(current)
        } catch (reset: ResetNeeded) {
            zoneReady = false
            ensureZone()
            fetchChanges(current.reset().also(::persist))
        }
        checkpoint = current
        sweepCache(current)
        return current
    }

    private suspend fun fetchChanges(start: ZoneCheckpoint): ZoneCheckpoint {
        var current = start
        val changed = mutableListOf<RecordChange>()
        val deleted = mutableListOf<String>()
        var resetNeeded = false
        execute<Unit> { cont ->
            val configuration = CKFetchRecordZoneChangesConfiguration().apply {
                previousServerChangeToken = decodeToken(start.token)
                desiredKeys = listOf(FIELD_PATH, FIELD_SIZE)
            }
            CKFetchRecordZoneChangesOperation(recordZoneIDs = listOf(zoneId), configurationsByRecordZoneID = mapOf(zoneId to configuration)).apply {
                fetchAllChanges = true
                recordWasChangedBlock = { recordId, record, _ ->
                    if (recordId != null && record != null) {
                        changed += RecordChange(
                            recordName = recordId.recordName,
                            path = record.valueForKey(FIELD_PATH) as? String ?: ZoneCheckpoint.path(recordId.recordName),
                            size = (record.valueForKey(FIELD_SIZE) as? NSNumber)?.longLongValue ?: 0L,
                            changeTag = record.recordChangeTag,
                        )
                    }
                }
                recordWithIDWasDeletedBlock = { recordId, _ -> if (recordId != null) deleted += recordId.recordName }
                recordZoneChangeTokensUpdatedBlock = { _, token, _ ->
                    current = current.applyBatch(changed.toList(), deleted.toList(), encodeToken(token))
                    changed.clear()
                    deleted.clear()
                    persist(current)
                }
                recordZoneFetchCompletionBlock = { _, token, _, _, error ->
                    if (error != null) {
                        if (error.isReset()) resetNeeded = true
                    } else {
                        current = current.applyBatch(changed.toList(), deleted.toList(), encodeToken(token))
                        changed.clear()
                        deleted.clear()
                        persist(current)
                    }
                }
                fetchRecordZoneChangesCompletionBlock = { error ->
                    when {
                        resetNeeded || (error != null && error.isReset()) -> cont.resumeWithException(ResetNeeded())
                        error != null -> cont.resumeWithException(mapped(error, "list"))
                        else -> cont.resume(Unit)
                    }
                }
            }
        }
        return current
    }

    private suspend fun ensureZone() {
        if (zoneReady) return
        execute<Unit> { cont ->
            CKModifyRecordZonesOperation(recordZonesToSave = listOf(CKRecordZone(zoneID = zoneId)), recordZoneIDsToDelete = null).apply {
                modifyRecordZonesCompletionBlock = { _, _, error ->
                    if (error != null) cont.resumeWithException(mapped(error, "zone")) else cont.resume(Unit)
                }
            }
        }
        zoneReady = true
    }

    private suspend fun loadCheckpoint(): ZoneCheckpoint = ZoneCheckpoint.decode(LocalFiles.readAllBytes(checkpointPath), scope())

    private fun persist(checkpoint: ZoneCheckpoint) = LocalFiles.writeAllBytes(checkpointPath, checkpoint.encode())

    private suspend fun updateCheckpoint(transform: (ZoneCheckpoint) -> ZoneCheckpoint) = mutex.withLock {
        checkpoint = transform(checkpoint ?: loadCheckpoint()).also(::persist)
    }

    // endregion

    // region writes

    override suspend fun writeBytes(path: String, bytes: ByteArray, mimeType: String, existingRemoteId: String?): String? {
        val outbox = "$cacheDirectory/$OUTBOX_DIR/${NSUUID().UUIDString}"
        LocalFiles.writeAllBytes(outbox, bytes)
        try {
            save(path = path, filePath = outbox, size = bytes.size.toLong())
        } finally {
            LocalFiles.delete(outbox)
        }
        return null
    }

    override suspend fun writeFile(path: String, localPath: String, mimeType: String, existingRemoteId: String?): String? {
        val size = LocalFiles.fileSize(localPath) ?: throw CloudStorageException(CloudError.Transport, "CloudKit: missing local file $localPath")
        // Media are the slow saves: long-lived, so an upload already submitted completes even if
        // the app is suspended or killed. The next list() sees the record through zone changes.
        save(path = path, filePath = localPath, size = size, longLived = true)
        return null
    }

    /** One record per operation, `saveAllKeys`: a single writer, last write wins on purpose. */
    private suspend fun save(path: String, filePath: String, size: Long, longLived: Boolean = false) {
        ensureZone()
        val name = ZoneCheckpoint.recordName(path)
        val saved = execute<CKRecord> { cont ->
            val record = CKRecord(recordType = recordType, recordID = CKRecordID(recordName = name, zoneID = zoneId)).apply {
                setValue(path, forKey = FIELD_PATH)
                setValue(NSNumber(longLong = size), forKey = FIELD_SIZE)
                setValue(CKAsset(fileURL = NSURL.fileURLWithPath(filePath)), forKey = FIELD_CONTENT)
            }
            CKModifyRecordsOperation(recordsToSave = listOf(record), recordIDsToDelete = null).apply {
                savePolicy = CKRecordSaveAllKeys
                qualityOfService = NSQualityOfServiceUserInitiated
                if (longLived) configuration = CKOperationConfiguration().apply { setLongLived(true) }
                modifyRecordsCompletionBlock = { savedRecords, _, error ->
                    if (error != null) cont.resumeWithException(mapped(error, name))
                    else cont.resume(savedRecords?.firstOrNull() as? CKRecord ?: record)
                }
            }
        }
        updateCheckpoint { it.upsert(name, path, size, saved.recordChangeTag) }
    }

    override suspend fun delete(path: String, remoteId: String?) {
        ensureZone()
        val name = ZoneCheckpoint.recordName(path)
        execute<Unit> { cont ->
            CKModifyRecordsOperation(recordsToSave = null, recordIDsToDelete = listOf(CKRecordID(recordName = name, zoneID = zoneId))).apply {
                qualityOfService = NSQualityOfServiceUserInitiated
                modifyRecordsCompletionBlock = { _, _, error ->
                    if (error == null || error.isUnknownItem()) cont.resume(Unit) else cont.resumeWithException(mapped(error, name))
                }
            }
        }
        updateCheckpoint { it.remove(name) }
        cachedFile(name)?.let(LocalFiles::delete)
    }

    // endregion

    // region reads

    override suspend fun readBytes(path: String, remoteId: String?): ByteArray? =
        fetchToCache(path)?.let(LocalFiles::readAllBytes)

    override suspend fun downloadFile(path: String, toLocalPath: String, remoteId: String?) {
        val cached = fetchToCache(path) ?: throw CloudStorageException(CloudError.NotFound, "CloudKit: no record for $path")
        LocalFiles.copy(cached, toLocalPath)
    }

    override suspend fun prefetch(paths: List<String>) {
        runCatching {
            coroutineScope {
                paths.map(ZoneCheckpoint::recordName)
                    .filter { cachedFile(it) == null }
                    .chunked(FETCH_BATCH)
                    .map { batch -> async { prefetchGate.withPermit { runCatching { fetchRecords(batch) } } } }
                    .awaitAll()
            }
        }
    }

    /** The cached asset for [path], fetching it when the cache has no copy for the current change tag; null when the record is absent. */
    private suspend fun fetchToCache(path: String): String? {
        val name = ZoneCheckpoint.recordName(path)
        cachedFile(name)?.let { return it }
        val result = prefetchGate.withPermit { fetchRecords(listOf(name)) }[name] ?: return null
        return result.cachePath
    }

    private class Fetched(val cachePath: String)

    /**
     * One `CKFetchRecordsOperation` for up to [FETCH_BATCH] records. Each asset is
     * moved out of CloudKit's staging area inside the per-record block, because the
     * system deletes staged files on its own schedule.
     */
    private suspend fun fetchRecords(recordNames: List<String>): Map<String, Fetched> {
        ensureZone()
        val fetched = mutableMapOf<String, Fetched>()
        val upserts = mutableListOf<RecordChange>()
        execute<Unit> { cont ->
            CKFetchRecordsOperation(recordIDs = recordNames.map { CKRecordID(recordName = it, zoneID = zoneId) }).apply {
                desiredKeys = listOf(FIELD_PATH, FIELD_SIZE, FIELD_CONTENT)
                qualityOfService = NSQualityOfServiceUserInitiated
                perRecordCompletionBlock = { record, recordId, error ->
                    val name = recordId?.recordName
                    val stagedPath = (record?.valueForKey(FIELD_CONTENT) as? CKAsset)?.fileURL?.path
                    if (name != null && record != null && stagedPath != null && error == null) {
                        val target = cachePath(name, record.recordChangeTag)
                        LocalFiles.move(stagedPath, target)
                        fetched[name] = Fetched(target)
                        upserts += RecordChange(
                            recordName = name,
                            path = record.valueForKey(FIELD_PATH) as? String ?: ZoneCheckpoint.path(name),
                            size = (record.valueForKey(FIELD_SIZE) as? NSNumber)?.longLongValue ?: (LocalFiles.fileSize(target) ?: 0L),
                            changeTag = record.recordChangeTag,
                        )
                    }
                }
                fetchRecordsCompletionBlock = { _, error ->
                    when {
                        error == null || error.isPartialOfUnknownItems() -> cont.resume(Unit)
                        else -> cont.resumeWithException(mapped(error, recordNames.joinToString()))
                    }
                }
            }
        }
        if (upserts.isNotEmpty()) updateCheckpoint { cp -> upserts.fold(cp) { acc, c -> acc.upsert(c.recordName, c.path, c.size, c.changeTag) } }
        return fetched
    }

    private fun cachePath(recordName: String, changeTag: String?): String = "$cacheDirectory/$ASSETS_DIR/$recordName@${changeTag ?: "none"}"

    private fun cachedFile(recordName: String): String? {
        val tag = checkpoint?.changeTag(recordName) ?: return null
        return cachePath(recordName, tag).takeIf(LocalFiles::exists)
    }

    /** Drops cached assets whose record is gone or has a newer change tag, and any outbox file left by a crashed save. */
    private fun sweepCache(current: ZoneCheckpoint) {
        for (file in LocalFiles.listNames("$cacheDirectory/$ASSETS_DIR")) {
            val name = file.substringBeforeLast('@')
            val tag = file.substringAfterLast('@', "")
            if (current.changeTag(name) != tag) LocalFiles.delete("$cacheDirectory/$ASSETS_DIR/$file")
        }
        for (file in LocalFiles.listNames("$cacheDirectory/$OUTBOX_DIR")) LocalFiles.delete("$cacheDirectory/$OUTBOX_DIR/$file")
    }

    // endregion

    // region operations and errors

    private class ResetNeeded : Exception("CloudKit zone or token must be rebuilt")

    private class RetryLater(val afterMs: Long, cause: NSError, val item: String) : Exception(cause.localizedDescription)

    /**
     * Builds and runs one operation, resuming through its completion block. A
     * rate-limit or busy answer is retried once after the server's own delay
     * (capped), then surfaces as [CloudError.Transport].
     */
    private suspend fun <T> execute(build: (CancellableContinuation<T>) -> CKDatabaseOperation): T {
        repeat(MAX_ATTEMPTS - 1) {
            try {
                return runOnce(build)
            } catch (retry: RetryLater) {
                delay(retry.afterMs)
            }
        }
        return try {
            runOnce(build)
        } catch (retry: RetryLater) {
            throw CloudStorageException(CloudError.Transport, "CloudKit ${retry.item}: ${retry.message}")
        }
    }

    private suspend fun <T> runOnce(build: (CancellableContinuation<T>) -> CKDatabaseOperation): T = suspendCancellableCoroutine { cont ->
        val operation = build(cont)
        cont.invokeOnCancellation { operation.cancel() }
        database.addOperation(operation)
    }

    private fun NSError.ckCode(): Long = if (domain == CKErrorDomain) code else -1L

    private fun NSError.isReset(): Boolean = ckCode() in RESET_CODES

    private fun NSError.isUnknownItem(): Boolean = ckCode() == CK_UNKNOWN_ITEM || isPartialOfUnknownItems()

    private fun NSError.isPartialOfUnknownItems(): Boolean {
        if (ckCode() != CK_PARTIAL_FAILURE) return false
        val perItem = (userInfo[CKPartialErrorsByItemIDKey] as? NSDictionary) ?: return false
        val values = perItem.allValues.filterIsInstance<NSError>()
        return values.isNotEmpty() && values.all { it.ckCode() == CK_UNKNOWN_ITEM }
    }

    private fun mapped(error: NSError, item: String): Exception {
        val code = error.ckCode()
        if (code == CK_PARTIAL_FAILURE) {
            val first = ((error.userInfo[CKPartialErrorsByItemIDKey] as? NSDictionary)?.allValues?.filterIsInstance<NSError>())?.firstOrNull()
            if (first != null) return mapped(first, item)
        }
        if (code in RETRY_CODES) {
            val seconds = (error.userInfo[CKErrorRetryAfterKey] as? NSNumber)?.doubleValue ?: DEFAULT_RETRY_SECONDS
            return RetryLater(afterMs = (seconds.coerceIn(0.5, MAX_RETRY_SECONDS) * 1000).toLong(), cause = error, item = item)
        }
        val mappedError = when (code) {
            CK_QUOTA_EXCEEDED -> CloudError.StorageFull
            CK_NETWORK_UNAVAILABLE, CK_NETWORK_FAILURE -> CloudError.Offline
            CK_NOT_AUTHENTICATED -> CloudError.NotAvailable
            CK_PERMISSION_FAILURE -> CloudError.AuthRevoked
            CK_UNKNOWN_ITEM -> CloudError.NotFound
            else -> CloudError.Transport
        }
        return CloudStorageException(mappedError, "CloudKit $item: ${error.localizedDescription} (code $code)")
    }

    private fun encodeToken(token: CKServerChangeToken?): String? = token?.let {
        NSKeyedArchiver.archivedDataWithRootObject(it, requiringSecureCoding = true, error = null)?.base64EncodedStringWithOptions(0u)
    }

    private fun decodeToken(encoded: String?): CKServerChangeToken? {
        val data = encoded?.let { NSData.create(base64EncodedString = it, options = 0u) } ?: return null
        return NSKeyedUnarchiver.unarchivedObjectOfClass(CKServerChangeToken, fromData = data, error = null) as? CKServerChangeToken
    }

    // endregion

    private companion object {
        const val FIELD_PATH = "path"
        const val FIELD_SIZE = "size"
        const val FIELD_CONTENT = "content"
        const val ASSETS_DIR = "assets"
        const val OUTBOX_DIR = "outbox"
        const val FETCH_BATCH = 8
        const val MAX_FETCHES_IN_FLIGHT = 2
        const val MAX_ATTEMPTS = 2
        const val DEFAULT_RETRY_SECONDS = 2.0
        const val MAX_RETRY_SECONDS = 10.0

        const val CK_PARTIAL_FAILURE = 2L
        const val CK_NETWORK_UNAVAILABLE = 3L
        const val CK_NETWORK_FAILURE = 4L
        const val CK_SERVICE_UNAVAILABLE = 6L
        const val CK_REQUEST_RATE_LIMITED = 7L
        const val CK_NOT_AUTHENTICATED = 9L
        const val CK_PERMISSION_FAILURE = 10L
        const val CK_UNKNOWN_ITEM = 11L
        const val CK_CHANGE_TOKEN_EXPIRED = 21L
        const val CK_ZONE_BUSY = 23L
        const val CK_QUOTA_EXCEEDED = 25L
        const val CK_ZONE_NOT_FOUND = 26L
        const val CK_USER_DELETED_ZONE = 28L

        val RESET_CODES = setOf(CK_CHANGE_TOKEN_EXPIRED, CK_ZONE_NOT_FOUND, CK_USER_DELETED_ZONE)
        val RETRY_CODES = setOf(CK_SERVICE_UNAVAILABLE, CK_REQUEST_RATE_LIMITED, CK_ZONE_BUSY)
    }
}
