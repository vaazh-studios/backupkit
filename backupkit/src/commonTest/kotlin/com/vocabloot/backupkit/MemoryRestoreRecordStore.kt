package com.vocabloot.backupkit

class MemoryRestoreRecordStore : RestoreRecordStore {
    var record: RestoreRecord? = null
    var saveCount = 0
    override fun load(): RestoreRecord? = record
    override fun save(record: RestoreRecord) { this.record = record; saveCount += 1 }
    override fun clear() { record = null }
}
