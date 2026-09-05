package com.vocabloot.backupkit.sample

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.Duration
import kotlin.time.TimeSource

/** Emits the first value, then drops values for [window] after each emission. */
fun <T> Flow<T>.throttleFirst(window: Duration): Flow<T> = flow {
    var last: TimeSource.Monotonic.ValueTimeMark? = null
    collect { value ->
        val mark = last
        if (mark == null || mark.elapsedNow() >= window) {
            last = TimeSource.Monotonic.markNow()
            emit(value)
        }
    }
}
