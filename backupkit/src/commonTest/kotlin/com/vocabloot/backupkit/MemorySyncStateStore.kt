package com.vocabloot.backupkit

class MemorySyncStateStore : SyncStateStore {
    var state: SyncState? = null
    override fun load(): SyncState = state ?: SyncState()
    override fun save(state: SyncState) { this.state = state }
    override fun clear() { state = null }
}
