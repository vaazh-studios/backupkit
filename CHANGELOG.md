# Changelog

## 0.1.0 (2026-09-06)

- `CloudStorage`: filesystem-style transport with `ICloudStorage` (iOS) and `GoogleDriveStorage` (Android, app-data folder, silent Play Services token, `DriveConsent` helper).
- `SyncEngine`: complete-or-absent reconciliation with resume, identity reset, the empty-over-existing guard, and `inspect()` for restore offers.
- `FileSyncStateStore`: atomic JSON state file over kotlinx-io.
- Typed errors: `CloudStorageException(CloudError)`.
- `SyncEngine.probe()` (typed inspection with a pinned `SourceRef`), `WriteHold`, `CloudStorage.prefetch`.
- `CloudKitStorage` (iOS): custom-zone CloudKit transport with long-lived media saves, with a change-token checkpoint, cached batch reads and typed error mapping. Pick it over `ICloudStorage` for app data the user never opens as files.
- `RestoreEngine`: resumable restore with a required-first commit boundary, three-strike optional files, durable `RestoreRecord` and source revalidation. Files carry an opaque `group`; a `RestorePlacement` receives each group once its files are local and answers `Placed` or `Rejected`; `RestoreProgress` counts files and groups; `resume(resetAttempts = true)` is the explicit retry. Marked `@ExperimentalRestoreApi` until Vocabloot's device pass on it lands.
