# Module BackupKit

Backup into the user's own cloud: iCloud Drive on iOS, the Google Drive app-data folder on Android.

# Package com.vocabloot.backupkit

Two layers. `CloudStorage` is the filesystem-style transport (`ICloudStorage`, `GoogleDriveStorage`).
`SyncEngine` owns the complete-or-absent reconciliation over a `SyncSnapshot` and persists its
`SyncState` through a `SyncStateStore`. Start with the README quickstart, then `SyncEngine.sync`.
