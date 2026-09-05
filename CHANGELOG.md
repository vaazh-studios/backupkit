# Changelog

## 0.1.0 (2026-09-05)

- `CloudStorage`: filesystem-style transport with `ICloudStorage` (iOS) and `GoogleDriveStorage` (Android, app-data folder, silent Play Services token, `DriveConsent` helper).
- `SyncEngine`: complete-or-absent reconciliation with resume, identity reset, the empty-over-existing guard, and `inspect()` for restore offers.
- `FileSyncStateStore`: atomic JSON state file over kotlinx-io.
- Typed errors: `CloudStorageException(CloudError)`.
