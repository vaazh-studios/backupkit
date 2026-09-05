# The SyncEngine contract

`SyncEngine.sync(snapshot)` guarantees, on both platforms:

1. **Availability gate.** `NoAccount` / `NeedsConsent` return `Unavailable` without touching the cloud.
2. **Remote truth.** `storage.list()` is the source of truth; the state file is a cache. State entries missing remotely are dropped; remote files unknown to the state are adopted (size trusted, hash unknown, so hashed entries re-upload).
3. **Identity.** `storage.identityKey()`, else the marker's remote id, else `""`. A change resets the state: an account switch means a full re-upload, never a merge of two accounts.
4. **Empty guard.** `snapshot.isEmpty` + never synced + remote holds a non-marker file → `Unavailable(RestorePending)`, nothing written. Pass `SyncSnapshot(entries, isEmpty = yourModel.isEmpty())` when scaffold files are always listed. Disable with `guardEmptyOverExisting = false`.
5. **Diff.** Size-compared entries (`hash == null`) upload when unknown, or when the local or remote size differs. Hash-compared entries upload when the stored hash differs. The marker uploads whenever anything else uploaded or it is missing remotely.
6. **Deletes.** State entries absent from the snapshot are deleted, except the marker and except entries declared with `SyncSource.Absent`.
7. **Order.** Size-compared uploads, then hash-compared in snapshot order, then the marker. State is saved after every put.
8. **Deletes after uploads.** A failed delete is logged and retried next run; it never fails the sync.
9. **Drive identity adoption.** When `identityKey()` is null, the marker's file id becomes the identity after its first upload.
10. **Progress.** `onProgress(done, total)` fires before the first step and after every put and delete.

## Error mapping

| Situation | `CloudError` |
|---|---|
| iCloud Cocoa error 640 (`NSFileWriteOutOfSpaceError`) or 4354 (quota) | `StorageFull` |
| Drive 403 `storageQuotaExceeded` | `StorageFull` |
| Drive 403 rate limit, 3 retries exhausted | `Transport` |
| Drive other 403, or 401 after one token refresh | `AuthRevoked` |
| Connection failure | `Offline` |
| Read/download of an absent path | `NotFound` |
| Container unavailable, Play Services failure | `NotAvailable` |
| Consent dialog not accepted | `NeedsConsent` |
| Everything else (5xx after retries, coordination failure, timeout) | `Transport` |
