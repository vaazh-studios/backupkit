package com.vocabloot.backupkit

import com.vocabloot.backupkit.internal.LocalFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Why a restore run stopped. */
/**
 * Marks the restore half of BackupKit: unit-tested, not yet run on a device by a
 * shipping app. Opt in knowingly; the API may change in 0.2.0.
 */
@RequiresOptIn(
    message = "RestoreEngine is unit-tested but has not been run on a device by a shipping app yet; its API may change.",
    level = RequiresOptIn.Level.WARNING,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
public annotation class ExperimentalRestoreApi

public enum class RestoreError {
    /** The remote set is no longer the one the plan was pinned to (another device or account wrote a different backup). */
    SourceChanged,

    /** The remote set is gone. */
    SourceUnavailable,

    /** The remote set is present but incomplete or still downloading; try again later. */
    NotReady,
    NotAvailable,
    NeedsConsent,
    Offline,
    StorageFull,
    AuthRevoked,
    Transport,

    /** The app's [RestorePlacement] rejected a required group. */
    PlacementRejected,
}

/**
 * One file to bring down. [required] files form the commit boundary: all of them must succeed
 * before any optional file is attempted, and one failure fails the run. Optional files are
 * best-effort with a per-file attempt cap and stay pending across runs.
 *
 * [group] is an opaque key the app chooses (a record id, an album, a word). Files sharing a
 * group are handed to [RestorePlacement] together and counted together in [RestoreProgress];
 * the library never interprets it. Null means the file is its own group.
 */
public data class RestoreFile(
    val path: String,
    val toLocalPath: String,
    val remoteId: String? = null,
    val required: Boolean = true,
    val group: String? = null,
)

/** What to restore, pinned to the [SourceRef] the offer was built from (`RemoteProbe.Found.source`). */
public class RestorePlan(public val source: SourceRef, public val files: List<RestoreFile>)

/** Progress in files and in the app's own unit, its groups. */
public data class RestoreProgress(
    val filesDone: Int,
    val filesTotal: Int,
    val groupsDone: Int,
    val groupsTotal: Int,
)

public sealed interface PlacementResult {
    public data object Placed : PlacementResult

    /** The files stay staged, count one more attempt each, and are offered again next run. */
    public data class Rejected(val reason: String) : PlacementResult
}

/**
 * Where a downloaded group goes. Called once every downloadable file of a group is local (per
 * file when the file has no group), with the records whose [RestoreFileRecord.toLocalPath] now
 * holds the bytes. The app validates, moves or imports them however it likes and answers. The
 * default keeps files where they landed.
 */
public fun interface RestorePlacement {
    public suspend fun place(group: String?, files: List<RestoreFileRecord>): PlacementResult

    public companion object {
        public val KeepDownloaded: RestorePlacement = RestorePlacement { _, _ -> PlacementResult.Placed }
    }
}

public sealed interface RestoreOutcome {
    public data class Completed(val downloaded: Int) : RestoreOutcome

    /** Every required file is local; [pending] optional files are not (yet). */
    public data class Partial(val downloaded: Int, val pending: List<String>) : RestoreOutcome

    /** [downloaded] counts files that are local and stay local; `resume()` continues from them. */
    public data class Failed(val error: RestoreError, val downloaded: Int) : RestoreOutcome
}

@Serializable
public data class RestoreFileRecord(
    val path: String,
    val toLocalPath: String,
    val remoteId: String? = null,
    val required: Boolean = true,
    /** Placed: the app accepted it. */
    val done: Boolean = false,
    val attempts: Int = 0,
    val group: String? = null,
    /** Local at [toLocalPath] but not yet placed; a kill between the two resumes at placement. */
    val downloaded: Boolean = false,
) {
    /** The key the file is grouped and counted under. */
    public val groupKey: String get() = group ?: path
}

/** Durable progress of one restore chain. Saved after every file so a killed process resumes exactly where it stopped. */
@Serializable
public data class RestoreRecord(
    val runId: String,
    val source: SourceRef,
    val files: List<RestoreFileRecord>,
    val startedAtEpochMs: Long,
    val completedAtEpochMs: Long? = null,
) {
    public val pending: List<RestoreFileRecord> get() = files.filterNot { it.done }
    public val downloaded: Int get() = files.count { it.done }
    public val groupsTotal: Int get() = files.distinctBy { it.groupKey }.size
    public val groupsDone: Int get() = files.groupBy { it.groupKey }.count { (_, fs) -> fs.all { it.done } }
    public val progress: RestoreProgress get() = RestoreProgress(downloaded, files.size, groupsDone, groupsTotal)
}

public interface RestoreRecordStore {
    public fun load(): RestoreRecord?
    public fun save(record: RestoreRecord)
    public fun clear()
}

/** JSON file at [path], written atomically. A corrupt or absent file reads as null. */
public class FileRestoreRecordStore(private val path: String) : RestoreRecordStore {
    private val json = Json { ignoreUnknownKeys = true }

    override fun load(): RestoreRecord? {
        val bytes = LocalFiles.readAllBytes(path) ?: return null
        return runCatching { json.decodeFromString(RestoreRecord.serializer(), bytes.decodeToString()) }
            .getOrElse {
                logW(TAG, it) { "restore record unreadable; treating as absent" }
                null
            }
    }

    override fun save(record: RestoreRecord) {
        LocalFiles.writeAllBytes(path, json.encodeToString(RestoreRecord.serializer(), record).encodeToByteArray())
    }

    override fun clear(): Unit = LocalFiles.delete(path)

    private companion object {
        const val TAG = "FileRestoreRecordStore"
    }
}

/**
 * Resumable download of a pinned remote set. Required files first (the commit boundary), then
 * optional files best-effort with [maxAttempts] tries each. Progress is durable per file; [resume]
 * revalidates the source through [SyncEngine.probe] before continuing, so a backup that changed
 * under the plan is reported as [RestoreError.SourceChanged] instead of being mixed in.
 *
 * Files are downloaded to their `toLocalPath` and then offered to [placement] group by group.
 * Stopping is cancelling the calling coroutine: the record is saved after every file and every
 * placement, so a later [resume] continues from exactly there.
 *
 * Set [SyncEngine.setHold] to [WriteHold.RestoreRunning] before starting and release it when
 * your app has imported the files; the engine does not touch the hold itself.
 */
@OptIn(ExperimentalTime::class)
@ExperimentalRestoreApi
public class RestoreEngine(
    private val engine: SyncEngine,
    private val storage: CloudStorage,
    private val recordStore: RestoreRecordStore,
    private val placement: RestorePlacement = RestorePlacement.KeepDownloaded,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val maxAttempts: Int = 3,
    private val newRunId: () -> String = { Random.nextLong().toULong().toString(radix = 16) },
) {
    private val mutex = Mutex()

    /** Starts a new chain for [plan], replacing any previous record. */
    public suspend fun start(plan: RestorePlan, onProgress: (RestoreProgress) -> Unit = {}): RestoreOutcome = mutex.withLock {
        require(plan.files.map { it.path }.distinct().size == plan.files.size) { "plan lists a path twice" }
        val record = RestoreRecord(
            runId = newRunId(),
            source = plan.source,
            files = plan.files.map { RestoreFileRecord(it.path, it.toLocalPath, it.remoteId, it.required, group = it.group) },
            startedAtEpochMs = clock(),
        )
        recordStore.save(record)
        run(record, onProgress)
    }

    /**
     * Continues the recorded chain after revalidating its source. Done files are never downloaded
     * again. [resetAttempts] is the explicit retry: files that reached the attempt cap get a fresh
     * set of tries; a plain resume keeps the cap.
     */
    public suspend fun resume(resetAttempts: Boolean = false, onProgress: (RestoreProgress) -> Unit = {}): RestoreOutcome = mutex.withLock {
        var record = recordStore.load() ?: return@withLock RestoreOutcome.Failed(RestoreError.SourceUnavailable, 0)
        when (val probe = engine.probe()) {
            is RemoteProbe.Found -> if (!probe.source.matches(record.source)) {
                return@withLock RestoreOutcome.Failed(RestoreError.SourceChanged, record.downloaded)
            }
            RemoteProbe.None -> return@withLock RestoreOutcome.Failed(RestoreError.SourceUnavailable, record.downloaded)
            RemoteProbe.NotReady -> return@withLock RestoreOutcome.Failed(RestoreError.NotReady, record.downloaded)
            is RemoteProbe.Unavailable -> return@withLock RestoreOutcome.Failed(
                if (probe.reason == UnavailableReason.NeedsConsent) RestoreError.NeedsConsent else RestoreError.NotAvailable,
                record.downloaded,
            )
            is RemoteProbe.Failed -> return@withLock RestoreOutcome.Failed(errorOf(probe.error), record.downloaded)
        }
        if (resetAttempts) {
            record = record.copy(files = record.files.map { if (it.done) it else it.copy(attempts = 0) })
            recordStore.save(record)
        }
        run(record, onProgress)
    }

    public fun record(): RestoreRecord? = recordStore.load()

    public fun clear(): Unit = recordStore.clear()

    private suspend fun run(start: RestoreRecord, onProgressRaw: (RestoreProgress) -> Unit): RestoreOutcome {
        var record = start
        // One report per change: a download that does not yet place anything moves no count.
        var lastReported: RestoreProgress? = null
        val onProgress: (RestoreProgress) -> Unit = { p -> if (p != lastReported) { lastReported = p; onProgressRaw(p) } }
        onProgress(record.progress)
        record = resolveRemoteIds(record)
        runCatching { storage.prefetch(record.pending.filterNot { it.downloaded && LocalFiles.exists(it.toLocalPath) }.map { it.path }) }
            .onFailure { if (it is CancellationException) throw it }

        // Groups keep plan order; required groups first, each one the commit boundary for the next.
        val groups = record.files.groupBy { it.groupKey }.keys.toList()

        // 1) Required groups, in plan order. The first failure ends the run; optional files stay untouched.
        for (key in groups) {
            val files = record.files.filter { it.groupKey == key && it.required && !it.done }
            if (files.isEmpty()) continue
            for (file in files) {
                val ok = download(file)
                record = record.mark(file.path) { if (ok) it.copy(downloaded = true) else it.copy(attempts = it.attempts + 1, downloaded = false) }
                recordStore.save(record)
                if (!ok) {
                    logW(TAG) { "required restore file failed: ${file.path}" }
                    return RestoreOutcome.Failed(lastError ?: RestoreError.Transport, record.downloaded)
                }
            }
            val group = files.first().group
            when (val result = placement.place(group, record.files.filter { it.groupKey == key && it.required })) {
                PlacementResult.Placed -> record = record.markAll(files.map { it.path }) { it.copy(done = true) }
                is PlacementResult.Rejected -> {
                    record = record.markAll(files.map { it.path }) { it.copy(attempts = it.attempts + 1, downloaded = false) }
                    recordStore.save(record)
                    logW(TAG) { "required group rejected: ${key}: ${result.reason}" }
                    return RestoreOutcome.Failed(RestoreError.PlacementRejected, record.downloaded)
                }
            }
            recordStore.save(record)
            onProgress(record.progress)
        }

        // 2) Optional groups, best-effort, capped per file across runs.
        for (key in groups) {
            val files = record.files.filter { it.groupKey == key && !it.required && !it.done && it.attempts < maxAttempts }
            if (files.isEmpty()) continue
            val landed = mutableListOf<String>()
            for (file in files) {
                val ok = download(file)
                record = record.mark(file.path) { if (ok) it.copy(downloaded = true) else it.copy(attempts = it.attempts + 1, downloaded = false) }
                recordStore.save(record)
                if (ok) landed += file.path else logW(TAG) { "optional restore file failed: ${file.path} (attempt ${file.attempts + 1})" }
                onProgress(record.progress)
            }
            if (landed.isEmpty()) continue
            val group = files.first().group
            record = when (val result = placement.place(group, record.files.filter { it.path in landed })) {
                PlacementResult.Placed -> record.markAll(landed) { it.copy(done = true) }
                is PlacementResult.Rejected -> {
                    logW(TAG) { "optional group rejected: ${key}: ${result.reason}" }
                    record.markAll(landed) { it.copy(attempts = it.attempts + 1, downloaded = false) }
                }
            }
            recordStore.save(record)
            onProgress(record.progress)
        }

        val pending = record.pending
        if (pending.isEmpty()) {
            record = record.copy(completedAtEpochMs = clock())
            recordStore.save(record)
            logI(TAG) { "restore complete files=${record.files.size}" }
            return RestoreOutcome.Completed(record.downloaded)
        }
        return RestoreOutcome.Partial(downloaded = record.downloaded, pending = pending.map { it.path })
    }

    private var lastError: RestoreError? = null

    /** True when the bytes are at `toLocalPath`; a staged file from an earlier run is not fetched again. */
    private suspend fun download(file: RestoreFileRecord): Boolean {
        if (file.downloaded && LocalFiles.exists(file.toLocalPath)) return true
        return try {
            storage.downloadFile(path = file.path, toLocalPath = file.toLocalPath, remoteId = file.remoteId)
            true
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            lastError = errorOf((t as? CloudStorageException)?.error ?: CloudError.Transport)
            false
        }
    }

    /**
     * Drive resolves a path to a file id through a full listing; without ids every download would
     * list again. One listing per run fills the ids the plan left out (iCloud ignores them).
     */
    private suspend fun resolveRemoteIds(record: RestoreRecord): RestoreRecord {
        if (record.pending.none { it.remoteId == null }) return record
        val ids = try {
            storage.list().associate { it.path to it.remoteId }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            return record
        }
        if (ids.values.all { it == null }) return record
        val resolved = record.copy(files = record.files.map { f -> if (f.remoteId == null) f.copy(remoteId = ids[f.path]) else f })
        recordStore.save(resolved)
        return resolved
    }

    private fun RestoreRecord.mark(path: String, transform: (RestoreFileRecord) -> RestoreFileRecord): RestoreRecord =
        copy(files = files.map { if (it.path == path) transform(it) else it })

    private fun RestoreRecord.markAll(paths: List<String>, transform: (RestoreFileRecord) -> RestoreFileRecord): RestoreRecord =
        copy(files = files.map { if (it.path in paths) transform(it) else it })

    private companion object {
        const val TAG = "RestoreEngine"

        fun errorOf(error: CloudError): RestoreError = when (error) {
            CloudError.NotAvailable -> RestoreError.NotAvailable
            CloudError.NeedsConsent -> RestoreError.NeedsConsent
            CloudError.Offline -> RestoreError.Offline
            CloudError.StorageFull -> RestoreError.StorageFull
            CloudError.AuthRevoked -> RestoreError.AuthRevoked
            CloudError.NotFound -> RestoreError.NotReady
            CloudError.Transport -> RestoreError.Transport
        }
    }
}
