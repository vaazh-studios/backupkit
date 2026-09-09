package com.vocabloot.backupkit.test

import com.vocabloot.backupkit.ExperimentalRestoreApi
import com.vocabloot.backupkit.RestoreRecord
import com.vocabloot.backupkit.RestoreRecordStore

/** In-memory [RestoreRecordStore]. [saveCount] counts durable writes, which the engine does after every file. */
@OptIn(ExperimentalRestoreApi::class)
public class MemoryRestoreRecordStore : RestoreRecordStore {
    public var record: RestoreRecord? = null
    public var saveCount: Int = 0

    override fun load(): RestoreRecord? = record

    override fun save(record: RestoreRecord) {
        this.record = record
        saveCount += 1
    }

    override fun clear() {
        record = null
    }
}
