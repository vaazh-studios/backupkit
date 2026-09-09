# Errors

Every failure is a `CloudStorageException` carrying one `CloudError`:
`NotAvailable`, `NeedsConsent`, `Offline`, `StorageFull`, `AuthRevoked`, `NotFound`, `Transport`.
`SyncEngine.sync` never throws for cloud failures; it returns `SyncOutcome.Failed(error)`.

## Suggested user copy

Every adopter ends up writing these strings. A starting point that Vocabloot ships:

| `CloudError` | Shown when | Suggested line |
|---|---|---|
| `NotAvailable` | no iCloud account, or Play Services missing | "Sign in to iCloud on this iPhone to back up." / "Google Play services is needed for backup." |
| `NeedsConsent` | Drive grant missing or revoked | "Allow Vocabloot to use your Google Drive to keep a backup." |
| `Offline` | no network | "Offline. Will retry when you are back online." |
| `StorageFull` | iCloud or Drive quota reached | "Your iCloud storage is full. Free some space to keep backing up." |
| `AuthRevoked` | token rejected | "Backup lost access to your account. Turn it off and on again." |
| `NotFound` | a file vanished between list and read | (retry silently; the next sync heals it) |
| `Transport` | anything else | "Backup hit a problem. Will retry." |

`SyncOutcome.Unavailable(reason)` carries `NoAccount`, `NeedsConsent` or `RestorePending`; the last one means the device is empty and the cloud is not, so offer a restore instead of uploading.
