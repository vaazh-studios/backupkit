package com.vocabloot.backupkit.test

import com.vocabloot.backupkit.SyncState
import com.vocabloot.backupkit.SyncStateStore

/** In-memory [SyncStateStore]. Starts empty; [state] is the last saved value, null after [clear]. */
public class MemorySyncStateStore : SyncStateStore {
    public var state: SyncState? = null

    override fun load(): SyncState = state ?: SyncState()

    override fun save(state: SyncState) {
        this.state = state
    }

    override fun clear() {
        state = null
    }
}
