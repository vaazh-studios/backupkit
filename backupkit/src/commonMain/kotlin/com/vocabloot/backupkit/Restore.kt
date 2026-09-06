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
}

/**
 * One file to bring down. [required] files form the commit boundary: all of them must succeed
 * before any optional file is attempted, and one failure fails the run. Optional files are
 * best-effort with a per-file attempt cap and stay pending across runs.
 */
public data class RestoreFile(
    val path: String,
    val toLocalPath: String,
    val remoteId: String? = null,
    val required: Boolean = true,
)

/** What to restore, pinned to the [SourceRef] the offer was built from (`RemoteProbe.Found.source`). */
public class RestorePlan(public val source: SourceRef, public val files: List<RestoreFile>)

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
    val done: Boolean = false,
    val attempts: Int = 0,
)

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
 * Set [SyncEngine.setHold] to [WriteHold.RestoreRunning] before starting and release it when
 * your app has imported the files; the engine does not touch the hold itself.
 */
@OptIn(ExperimentalTime::class)
public class RestoreEngine(
    private val engine: SyncEngine,
    private val storage: CloudStorage,
    private val recordStore: RestoreRecordStore,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val maxAttempts: Int = 3,
    private val newRunId: () -> String = { Random.nextLong().toULong().toString(radix = 16) },
) {
    private val mutex = Mutex()

    /** Starts a new chain for [plan], replacing any previous record. */
    public suspend fun start(plan: RestorePlan, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): RestoreOutcome = mutex.withLock {
        require(plan.files.map { it.path }.distinct().size == plan.files.size) { "plan lists a path twice" }
        val record = RestoreRecord(
            runId = newRunId(),
            source = plan.source,
            files = plan.files.map { RestoreFileRecord(it.path, it.toLocalPath, it.remoteId, it.required) },
            startedAtEpochMs = clock(),
        )
        recordStore.save(record)
        run(record, onProgress)
    }

    /** Continues the recorded chain after revalidating its source. Done files are never downloaded again. */
    public suspend fun resume(onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): RestoreOutcome = mutex.withLock {
        val record = recordStore.load() ?: return@withLock RestoreOutcome.Failed(RestoreError.SourceUnavailable, 0)
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
        run(record, onProgress)
    }

    public fun record(): RestoreRecord? = recordStore.load()

    public fun clear(): Unit = recordStore.clear()

    private suspend fun run(start: RestoreRecord, onProgress: (Int, Int) -> Unit): RestoreOutcome {
        var record = start
        val total = record.files.size
        onProgress(record.downloaded, total)
        runCatching { storage.prefetch(record.pending.map { it.path }) }
            .onFailure { if (it is CancellationException) throw it }

        // 1) Required files, in plan order. The first failure ends the run; optional files stay untouched.
        for (file in record.files.filter { it.required && !it.done }) {
            try {
                storage.downloadFile(path = file.path, toLocalPath = file.toLocalPath, remoteId = file.remoteId)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                record = record.mark(file.path) { it.copy(attempts = it.attempts + 1) }
                recordStore.save(record)
                logW(TAG, t) { "required restore file failed: ${file.path}" }
                return RestoreOutcome.Failed(errorOf((t as? CloudStorageException)?.error ?: CloudError.Transport), record.downloaded)
            }
            record = record.mark(file.path) { it.copy(done = true) }
            recordStore.save(record)
            onProgress(record.downloaded, total)
        }

        // 2) Optional files, best-effort, capped per file across runs.
        for (file in record.files.filter { !it.required && !it.done && it.attempts < maxAttempts }) {
            val ok = try {
                storage.downloadFile(path = file.path, toLocalPath = file.toLocalPath, remoteId = file.remoteId)
                true
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                logW(TAG, t) { "optional restore file failed: ${file.path} (attempt ${file.attempts + 1})" }
                false
            }
            record = record.mark(file.path) { if (ok) it.copy(done = true) else it.copy(attempts = it.attempts + 1) }
            recordStore.save(record)
            onProgress(record.downloaded, total)
        }

        val pending = record.pending
        if (pending.isEmpty()) {
            record = record.copy(completedAtEpochMs = clock())
            recordStore.save(record)
            logI(TAG) { "restore complete files=$total" }
            return RestoreOutcome.Completed(record.downloaded)
        }
        return RestoreOutcome.Partial(downloaded = record.downloaded, pending = pending.map { it.path })
    }

    private fun RestoreRecord.mark(path: String, transform: (RestoreFileRecord) -> RestoreFileRecord): RestoreRecord =
        copy(files = files.map { if (it.path == path) transform(it) else it })

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
