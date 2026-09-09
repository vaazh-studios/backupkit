# Restore on first launch

```kotlin
when (val probe = engine.probe()) {
    is RemoteProbe.Found -> offerRestore(parseHeader(probe.marker), probe.source)   // your schema, your UI
    RemoteProbe.NotReady -> showStillUploading()                                    // a writer never finished, or iCloud is still fetching
    RemoteProbe.None, is RemoteProbe.Unavailable, is RemoteProbe.Failed -> Unit
}
```

Then hand `RestoreEngine` a plan pinned to that source. Required files are the commit boundary: all of
them download before any optional file, and one failure fails the run. Optional files are best-effort
with three attempts each. Give files an opaque `group` (a record id, a word) and a `RestorePlacement`:
the engine hands each group to you once its files are local, you import them into your own model and
answer `Placed` or `Rejected`, and progress comes back in files and in groups. Progress is written to
a record after every file, so a killed process continues from where it stopped with `resume()`, which
first re-checks that the remote set is still the one the user accepted and reports `SourceChanged`
otherwise.

```kotlin
engine.setHold(WriteHold.RestoreRunning)                       // sync() writes nothing while a hold is set
val restore = RestoreEngine(engine, storage, FileRestoreRecordStore(path), placement = { group, files ->
    if (group == "meta") importManifest(files) else attachPhotos(group, files)   // your model, your rules
    PlacementResult.Placed
})
val outcome = restore.start(
    RestorePlan(source = probe.source, files = listOf(
        RestoreFile("manifest.json", toLocalPath = "$dir/manifest.json", required = true, group = "meta"),
        RestoreFile("photos/w1.jpg", toLocalPath = "$dir/w1.jpg", required = false, group = "w1"),
        RestoreFile("photos/w1.png", toLocalPath = "$dir/w1.png", required = false, group = "w1"),
    )),
) { progress -> show("Photos for ${'$'}{progress.groupsDone} of ${'$'}{progress.groupsTotal} words") }
if (outcome !is RestoreOutcome.Failed) engine.setHold(WriteHold.None)
```

Importing the files into your own data structures is your code; BackupKit never guesses your schema.

## Holds

`engine.setHold(WriteHold.RestoreRunning)` makes `sync()` write nothing while a restore is in flight, and `WriteHold.RestoreIncomplete` keeps the cloud copy safe until the user retries or starts fresh. Release with `WriteHold.None`. The hold is part of the persisted `SyncState`, so it survives a kill.

## Resume after a kill

`RestoreEngine` writes its record after every file. On the next launch, `load()` the record store; if a record exists, call `resume()` instead of `start()`. `resume()` re-probes the source first and answers `RestoreOutcome.Failed(SourceChanged)` when the remote set is no longer the one the user accepted.

## Retry past the attempt cap

Optional files get three attempts per run. An explicit "try again" from the user should call `resume(resetAttempts = true)` so files that hit the cap are tried again; a silent background resume should not.
