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

## CloudKit specifics

- `RemoteFile.remoteId` is null and `size` is always known; the engine treats CloudKit exactly like iCloud Drive for identity (the user record name from `fetchUserRecordID`, never the marker id).
- `list()` fetches zone changes since the persisted checkpoint (token plus records, written together per batch); an expired token, a missing zone or a zone the user deleted resets the checkpoint and fetches from the beginning once. A checkpoint from another account, container environment or zone is discarded.
- `writeBytes` / `writeFile` save one record per operation with `saveAllKeys` (single writer, last write wins). `writeFile` saves are long-lived: an upload already submitted completes even if the app is suspended or killed; the next `list()` sees the record through zone changes and the engine adopts it by path and size, so nothing uploads twice. `delete` of an absent record succeeds.
- `readBytes` / `downloadFile` serve a cached asset when its change tag matches the checkpoint; otherwise one fetch. `prefetch` fetches eight records per request, two requests in flight, never throws.
- Rate limit, busy zone and service unavailable are retried once after the server's `retryAfter` (capped at 10 s), then `Transport`.

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

`SyncEngine.probe()` never throws for cloud failures. In order: availability gate → `Unavailable`; listing failure → `Failed`; no files → `None`; files but no marker, or a marker that is not downloadable yet → `NotReady`; otherwise `Found(marker bytes, SourceRef, files)`. `SourceRef` pins identity key, marker remote id and marker fingerprint; `matches()` compares identity and fingerprint. Any write of the marker by another device therefore counts as a changed source, including a marker that only carries a newer timestamp; keep volatile fields out of the marker if you want a stricter notion of "same backup".

## Write hold

`setHold(WriteHold)` persists into the sync state without touching entries. While the hold is not `None`, `sync()` returns `Unavailable(WriteHeld)` and writes nothing; a hold set during a run stops that run before its next put or delete. `sync()` calls are serialised by a mutex inside the engine. An identity reset keeps the hold. The library never sets or clears the hold on its own.

## Restore

1. `start(plan)` records the plan (pinned `SourceRef`, files with a `required` flag and an optional opaque `group`) and runs; `prefetch` is asked for every pending path first.
2. Files are downloaded to their `toLocalPath`, then offered to the app's `RestorePlacement` one group at a time (a file with no group is its own group). `Placed` marks the files done; `Rejected` counts one attempt on each and stages them again next run. The default placement keeps files where they landed.
3. Required groups go first, in plan order. The first download failure or rejection ends the run as `Failed(error)`, `PlacementRejected` for a rejection; optional files are untouched.
4. Optional groups are best-effort. What landed of a group is placed even when a sibling failed; a file with `maxAttempts` (3) failures is skipped in later runs until `resume(resetAttempts = true)`.
5. The record is saved after every download and every placement, with `downloaded` (local, not placed) separate from `done` (placed). Stopping is cancelling the caller; nothing else is needed.
6. `RestoreProgress` reports files and groups; a report is sent only when a count changes. `Completed` when nothing is pending (stamped with completion time), else `Partial(pending)`.
7. `resume()` revalidates first: `Found` whose source matches → continue from the record; otherwise `SourceChanged`, `SourceUnavailable`, `NotReady`, `NotAvailable`, `NeedsConsent`, or the mapped transport error. Done files are never downloaded again; a staged file still on disk is not fetched again either.
