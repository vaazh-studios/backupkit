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

## Probe

`SyncEngine.probe()` never throws for cloud failures. In order: availability gate → `Unavailable`; listing failure → `Failed`; no files → `None`; files but no marker, or a marker that is not downloadable yet → `NotReady`; otherwise `Found(marker bytes, SourceRef, files)`. `SourceRef` pins identity key, marker remote id and marker fingerprint; `matches()` compares identity and fingerprint.

## Write hold

`setHold(WriteHold)` persists into the sync state without touching entries. While the hold is not `None`, `sync()` returns `Unavailable(WriteHeld)` and writes nothing. An identity reset keeps the hold. The library never sets or clears the hold on its own.

## Restore

1. `start(plan)` records the plan (pinned `SourceRef`, files with a `required` flag) and runs; `prefetch` is asked for every pending path first.
2. Required files download in plan order. The first failure ends the run as `Failed(error)`; optional files are untouched. Files already downloaded stay downloaded.
3. Optional files download best-effort; a failure increments that file's attempts, and a file with `maxAttempts` (3) failures is skipped in later runs.
4. The record is saved after every file. `Completed` when nothing is pending (stamped with completion time), else `Partial(pending)`.
5. `resume()` revalidates first: `Found` whose source matches → continue from the record; otherwise `SourceChanged`, `SourceUnavailable`, `NotReady`, `NotAvailable`, `NeedsConsent`, or the mapped transport error. Done files are never downloaded again.
