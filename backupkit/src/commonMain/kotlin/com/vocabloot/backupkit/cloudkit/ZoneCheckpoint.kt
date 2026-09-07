package com.vocabloot.backupkit.cloudkit

import com.vocabloot.backupkit.RemoteFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What a CloudKit zone checkpoint is for: a server change token only points at a
 * place in the zone's history, so it cannot rebuild the record list on its own.
 * The checkpoint therefore stores the token together with the records it stands
 * for, and both are replaced together, batch by batch.
 *
 * Pure Kotlin so the batch rules run on the JVM; `CloudKitStorage` supplies the
 * CloudKit calls and the atomic file write.
 */
@Serializable
internal data class CheckpointScope(
    val container: String,
    val zone: String,
    val environment: String,
    val userRecordName: String,
)

@Serializable
internal data class CheckpointRecord(val path: String, val size: Long, val changeTag: String? = null)

/** One changed record as CloudKit delivers it inside a batch. */
internal data class RecordChange(val recordName: String, val path: String, val size: Long, val changeTag: String?)

@Serializable
internal data class ZoneCheckpointFile(
    val scope: CheckpointScope,
    val token: String? = null,
    val records: Map<String, CheckpointRecord> = emptyMap(),
)

internal class ZoneCheckpoint private constructor(private val file: ZoneCheckpointFile) {

    val scope: CheckpointScope get() = file.scope

    /** Base64 of the archived `CKServerChangeToken`; null means "fetch from the beginning". */
    val token: String? get() = file.token

    val records: Map<String, CheckpointRecord> get() = file.records

    fun changeTag(recordName: String): String? = file.records[recordName]?.changeTag

    fun encode(): ByteArray = json.encodeToString(ZoneCheckpointFile.serializer(), file).encodeToByteArray()

    /**
     * Applies one batch: changed records replace their entries, deleted ones go,
     * and [token] is the token CloudKit returned for that same batch. Persist the
     * result before asking for the next batch, and a token is never stored
     * without the changes it stands for.
     */
    fun applyBatch(changed: List<RecordChange>, deleted: List<String>, token: String?): ZoneCheckpoint {
        val next = file.records.toMutableMap()
        deleted.forEach { next.remove(it) }
        changed.forEach { next[it.recordName] = CheckpointRecord(path = it.path, size = it.size, changeTag = it.changeTag) }
        return ZoneCheckpoint(file.copy(token = token, records = next))
    }

    /** After one of our own saves was acknowledged. */
    fun upsert(recordName: String, path: String, size: Long, changeTag: String?): ZoneCheckpoint =
        ZoneCheckpoint(file.copy(records = file.records + (recordName to CheckpointRecord(path, size, changeTag))))

    /** After one of our own deletes was acknowledged. */
    fun remove(recordName: String): ZoneCheckpoint = ZoneCheckpoint(file.copy(records = file.records - recordName))

    /** Expired token, missing zone, or a zone the user deleted: same scope, fetch from the beginning. */
    fun reset(): ZoneCheckpoint = ZoneCheckpoint(ZoneCheckpointFile(scope = file.scope))

    fun toRemoteFiles(): List<RemoteFile> = file.records.values.map { RemoteFile(path = it.path, size = it.size, remoteId = null) }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun empty(scope: CheckpointScope): ZoneCheckpoint = ZoneCheckpoint(ZoneCheckpointFile(scope = scope))

        /**
         * A missing, corrupt, or foreign-scope file yields an empty checkpoint: a
         * different iCloud account, container environment, or zone must never reuse
         * another one's token or records.
         */
        fun decode(bytes: ByteArray?, expectedScope: CheckpointScope): ZoneCheckpoint {
            if (bytes == null) return empty(expectedScope)
            val parsed = runCatching { json.decodeFromString(ZoneCheckpointFile.serializer(), bytes.decodeToString()) }.getOrNull()
                ?: return empty(expectedScope)
            if (parsed.scope != expectedScope) return empty(expectedScope)
            return ZoneCheckpoint(parsed)
        }

        /** CloudKit record names must not contain `/`; the mapping is reversible because paths never contain `__`. */
        fun recordName(path: String): String = path.replace("/", "__")

        fun path(recordName: String): String = recordName.replace("__", "/")
    }
}
