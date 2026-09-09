# Support matrix

| | CloudKit (iOS) | iCloud Drive (iOS) | Google Drive app-data (Android) |
|---|---|---|---|
| Transport `CloudStorage` | ✅ `CloudKitStorage` | ✅ `ICloudStorage` | ✅ `GoogleDriveStorage` |
| Sync `SyncEngine` | ✅ | ✅ | ✅ |
| Auth | entitlement only | entitlement only | silent token, `DriveConsent` for the one-time dialog |
| Nested paths | ✅ (record name encodes `/`) | ✅ | flat folder, path used as file name |
| File ids | – | – | `RemoteFile.remoteId` |
| Sizes in `list()` | always known | -1 until downloaded | always known |
| Reads | network fetch, batched by `prefetch` | placeholder download, one at a time | network fetch |
| Single upload cap | 1 asset per save, container quota | container quota | 5 MB (multipart) |
| Verified on a real device | Vocabloot development build, iPhone 16 Pro, 2026-09-07 | Vocabloot development build, iPhone 16 Pro, 2026-09-05 | Vocabloot development build, Pixel 7 Pro, 2026-09-02 and 2026-09-07 |

Which iOS transport? **CloudKit** for app data the user never opens as files: saves complete when Apple's server has the record, reads are definite, no placeholder files. **iCloud Drive** when the files should also be visible in the Files app or another app reads the same container. Both are the user's own iCloud storage.
