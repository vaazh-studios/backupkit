# Bring your own scheduler

BackupKit runs one sync per call. A typical app wants: sync a few seconds after data changes,
sync on foreground at most once a minute, back off on failure. That is the whole of it:

```kotlin
class BackupScheduler(
    private val engine: SyncEngine,
    private val snapshots: () -> SyncSnapshot,
    changes: Flow<Unit>,
    foreground: Flow<Unit>,
    scope: CoroutineScope,
) {
    private val runs = Mutex()
    val status = MutableStateFlow<SyncOutcome?>(null)

    init {
        scope.launch {
            merge(changes.debounce(5.seconds), foreground.throttleFirst(60.seconds)).collectLatest { syncOnce() }
        }
    }

    private suspend fun syncOnce() = runs.withLock {
        val outcome = engine.sync(snapshots())
        status.value = outcome
        if (outcome is SyncOutcome.Failed && outcome.error == CloudError.Offline) delay(30.seconds)
    }
}
```

`throttleFirst` is not in kotlinx.coroutines; the sample app ships a ten-line version in `Throttle.kt`.
