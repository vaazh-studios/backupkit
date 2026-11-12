# Changelog

## 0.3.2 (2026-11-12)

Fixes a crash loop in 0.3.1. Found in Vocabloot: an app closed during a restore's photo download aborted on every later launch.

- `CloudKitStorage`: the per-record and completion blocks of a record fetch never throw. CloudKit replays a finished long-lived prefetch when `prefetch` reattaches it, but iOS may already have deleted the replayed records' staged asset files; moving a missing file threw inside the CloudKit callback, which aborts a Kotlin/Native app, and the fetch never completed, so the next launch replayed it again. A record whose staged file is gone, or cannot be moved, is now skipped and fetched again by the same `prefetch`. A completion block called again, or after the wait was cancelled, does nothing.
- No API change; drop-in for 0.3.1.

## 0.3.1 (2026-11-12)

A hash-compared entry that another writer replaced is no longer left stale. Found in Vocabloot: one iPhone ran a store build (CloudKit Production) and an Xcode build (Development) with one state file; the other environment kept an old `manifest.json` because the cached hash still matched the device's own data, while new photos uploaded and a fresh marker was written over it (marker 119 words, manifest 93).

- `SyncEngine` uploads a hash-compared entry again when the listed size differs from the snapshot's, whatever the cached hash says.
- `RemoteFile.version` (CloudKit: the record change tag; Drive: the file `version`) and `SyncedEntry.remoteVersion`: a hash-compared entry whose remote version moved since this device last saw it is uploaded again, which catches a same-size replacement. The version is adopted from the listing after the device's own upload (when the size matches). On Drive a server-side version bump (for example metadata) can cost one extra upload of a small file.
- `CloudStorage.stateScope()` (default `""`; `CloudKitStorage` returns its `environment`) is stored in `SyncState.scope`. A scope change drops the cached entries and keeps the write hold: hash-compared entries upload once, media are trusted by size. A state written by 0.3.0 has an empty scope, so a `CloudKitStorage` with an environment resets it once.
- `backupkit-test`: `FakeCloudStorage` gains `reportVersions`, `scope` and `replaceByAnotherWriter()`.
- `CloudKitStorage.prefetch` hands iCloud every missing record at once as long-lived fetches (32 records each, named `backupkit.prefetch`) instead of eight-record fetches two at a time, so a restore keeps downloading while the app is suspended or closed. The next `prefetch` reattaches those fetches (`fetchAllLongLivedOperationIDs`) and moves what they brought into the cache. Found in Vocabloot: leaving the app during "Downloading photos" stopped the download as `Offline`. Single reads stay short-lived.
- **Binary-incompatible for callers of the generated `copy`/`componentN` of `RemoteFile`, `SyncedEntry` and `SyncState`** (new trailing fields with defaults; source-compatible). Recompile against 0.3.1. A custom `CloudStorage` needs no change.

## 0.3.0 (2026-09-11)

- `SyncPolicy.shrinkGuard` (on by default: more than half of at least four remote entries) refuses a run that would delete most of the backup and answers the new `UnavailableReason.ShrinkSuspected`; `SyncSnapshot.allowShrink = true` lifts it for a confirmed run. **Source-breaking for exhaustive `when` over `UnavailableReason`; `SyncPolicy` and `SyncSnapshot` gained constructor parameters, so recompile against 0.3.0.**
- Google Drive: files above 5 MB upload through Drive's resumable protocol in 8 MiB chunks, streamed from disk for local files, continuing from what Drive holds after a dropped connection. The 5 MB `Transport` error is gone.
- `backupkit-test`: `FakeCloudStorage` gains `reportUnknownSizes`, `failFetchesContaining` and put gating (`gatePutsContaining`, `putStarted`, `releasePuts()`).

## 0.2.0 (2026-09-09)

- New artifact `com.vocabloot:backupkit-test`: `FakeCloudStorage`, `MemorySyncStateStore`, `MemoryRestoreRecordStore`, the fakes the library's own tests run on, so an app can unit-test its sync and restore code without a cloud.
- Library code unchanged from 0.1.0; the public API of `backupkit` is identical.
- README rewritten as a landing page; long-form guides moved to the docs site.

## 0.1.0 (2026-09-06)

- `CloudStorage`: filesystem-style transport with `ICloudStorage` (iOS) and `GoogleDriveStorage` (Android, app-data folder, silent Play Services token, `DriveConsent` helper).
- `SyncEngine`: complete-or-absent reconciliation with resume, identity reset, the empty-over-existing guard, and `inspect()` for restore offers.
- `FileSyncStateStore`: atomic JSON state file over kotlinx-io.
- Typed errors: `CloudStorageException(CloudError)`.
- `SyncEngine.probe()` (typed inspection with a pinned `SourceRef`), `WriteHold`, `CloudStorage.prefetch`.
- `CloudKitStorage` (iOS): custom-zone CloudKit transport with long-lived media saves, with a change-token checkpoint, cached batch reads and typed error mapping. Pick it over `ICloudStorage` for app data the user never opens as files.
- `RestoreEngine`: resumable restore with a required-first commit boundary, three-strike optional files, durable `RestoreRecord` and source revalidation. Files carry an opaque `group`; a `RestorePlacement` receives each group once its files are local and answers `Placed` or `Rejected`; `RestoreProgress` counts files and groups; `resume(resetAttempts = true)` is the explicit retry. Marked `@ExperimentalRestoreApi` until Vocabloot's device pass on it lands.
