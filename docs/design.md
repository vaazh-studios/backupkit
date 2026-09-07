# BackupKit: a Kotlin Multiplatform library for backup into the user's own cloud

**Date:** 2026-09-05
**Status:** approved design (founder, 2026-09-05)
**Repo:** `vaazh-studios/backupkit` (public, Apache 2.0), sibling checkout at `../backupkit`
**Artifact:** `com.vocabloot:backupkit`
**Why now:** the Shipaton "Ship Kotlin Everywhere" category lists an optional
"giving back" item (publish a library, contribute upstream, open-source a
reusable part). Vocabloot's cloud backup is the most novel KMP piece we
own and nothing on klibs.io or Maven Central covers it.


> **Delta 2026-09-06 (before the first release):** `inspect()` / `RemoteInspection` were replaced by
> `SyncEngine.probe(): RemoteProbe` with a pinned `SourceRef`; `WriteHold` was added to `SyncState` and
> gates `sync()`; `CloudStorage.prefetch` was added; `RestoreEngine` (required-first commit boundary,
> three-strike optional files, durable `RestoreRecord`, source revalidation on `resume()`) was added.
> Plan: `docs/superpowers/plans/2026-09-06-backupkit-restore.md` in the Vocabloot repo; rules in
> `docs/contract.md`.
>
> **Delta 2026-09-07:** `RestoreEngine` learned opaque file groups (`RestoreFile.group`), a
> `RestorePlacement` hook that moves a group's staged files into app data (Placed/Rejected),
> progress in groups as well as files, `downloaded` vs `done` per file, and
> `resume(resetAttempts = true)` for an explicit retry past the attempt cap. The API is
> `@ExperimentalRestoreApi` until it has run on a device. Plan:
> `docs/superpowers/plans/2026-09-07-restore-engine-adoption.md` in the Vocabloot repo.

## 1. What it is

BackupKit mirrors an app's files into the user's **own** cloud, with no
server, no account on our side, and no OAuth client setup on iOS:

- iOS: the app's iCloud Drive ubiquity container, outside `Documents/`, so
  files stay private to the app and invisible in Files.
- Android: the Google Drive **app-data folder** (`drive.appdata` scope), the
  same hidden folder WhatsApp uses. Silent token via Play Services
  `AuthorizationClient`; the user sees one consent dialog, once.

Two public layers, both usable on their own:

| Layer | Name | Modeled on | Job |
|---|---|---|---|
| 1 | `CloudStorage` | react-native-cloud-storage | Filesystem-style per-file transport: write, read, download, delete, list, exists, availability |
| 2 | `SyncEngine` | IceCream, Apple `CKSyncEngine` | Owns the complete-or-absent reconciliation and its persisted state; the app only says what the desired set of files is |

The code is a copy of Vocabloot's shipped transport and reconciler
(`shared/data/.../source/cloud/`), made app-agnostic. **Vocabloot itself is
not changed by this work.** Adoption by the app is a separate decision after
the Shipaton deadline (2026-09-30).

## 2. Goals and non-goals

Goals

1. A developer can go from `implementation("com.vocabloot:backupkit:0.1.0")`
   to a working sync in about ten lines of Kotlin plus the platform
   entitlement/console steps.
2. Identical semantics on both platforms (Kotlin library guideline: "ensure
   all platform implementations have identical behavior").
3. Every failure is a typed code the app can render, never a raw platform
   exception.
4. Listed on klibs.io; published to Maven Central under `com.vocabloot`.

Non-goals (documented in the README as "bring your own")

- Scheduling (debounce, connectivity gating, foreground throttle). The
  README shows a twelve-line scheduler snippet.
- Restore/import of the mirrored files into app data structures. The engine
  exposes the remote listing and the marker file; the app decides.
- Any UI, any DI framework, mandatory encryption, key-value store, other
  providers (Dropbox, OneDrive: CloudBridge already does those).

## 3. Patterns adopted from comparable libraries

| Pattern | Source | How BackupKit applies it |
|---|---|---|
| Filesystem verbs, one entry object, `isCloudAvailable()` | react-native-cloud-storage (285 stars) | `CloudStorage` interface with `writeFile/writeBytes/readBytes/downloadFile/delete/list/exists/availability` |
| Sync engine set up in ~10 lines; engine owns the algorithm | IceCream (2k stars) | `SyncEngine(storage, stateStore).sync(snapshot)` |
| Engine owns state; app persists the serialized blob | `CKSyncEngine` | `SyncStateStore` interface, `FileSyncStateStore` default |
| Numbered portal/console setup steps, typed error codes | icloud_storage (Flutter) | `docs/setup-ios.md`, `docs/setup-android.md`, `CloudError` enum |
| Silent, hidden app-data folder, restore offer on first launch | Android Auto Backup | `drive.appdata`, `SyncEngine.inspect()` for the restore offer |
| Library owns Google auth, three verbs | local_first_gdrive_backup | `DriveConsent` helper, silent token provider built in |
| Counter-example: Hilt required, AES required | android-google-drive-sync (0 stars) | No DI, no encryption, plain constructors |

Where BackupKit improves on the field: react-native-cloud-storage makes the
app fetch a Google access token itself; BackupKit ships a silent token
provider and still accepts a custom one for apps that already run Google
Sign-In.

## 4. Repository, module, toolchain

```
backupkit/
├── backupkit/                 # the published module
│   └── src/{commonMain,androidMain,iosMain,commonTest,androidHostTest,iosSimulatorArm64Test}
├── sample/                    # Compose Multiplatform "Notes" app, NOT published
│   ├── composeApp/
│   └── iosApp/
├── docs/                      # setup-ios.md, setup-android.md, contract.md, scheduling.md
├── .github/workflows/         # ci.yml (build + tests), publish.yml (release → Maven Central)
├── gradle/libs.versions.toml
├── README.md, CHANGELOG.md, LICENSE (Apache 2.0), api/backupkit.api
```

- Targets: `android` (AGP KMP library plugin `com.android.kotlin.multiplatform.library`, `withHostTest {}`), `iosArm64`, `iosSimulatorArm64`, `iosX64`. `applyDefaultHierarchyTemplate()`.
- Toolchain mirrors Vocabloot so the code ports without skew: Kotlin 2.3.20,
  AGP 9.2.1, compileSdk 36, minSdk 24, JVM target 21 for host tests.
- Dependencies (all `implementation` unless noted):
  - `kotlinx-coroutines-core` (api, suspend functions in the public surface)
  - `kotlinx-serialization-json` (state file, Drive JSON)
  - `kotlinx-io-core` 0.9.1 (local file read/write by path on both platforms)
  - `ktor-client-core` 3.4.2 (api on Android, so apps may pass their own `HttpClient`); `ktor-client-okhttp` in androidMain
  - androidMain: `play-services-auth` 22.x (`Identity.getAuthorizationClient`)
  - Nothing else. No Napier, no Koin, no Compose, no Firebase.
- Plugins: kotlin-multiplatform, android KMP library, kotlinx-serialization,
  `com.vanniktech.maven.publish` 0.37.0, `org.jetbrains.kotlinx.binary-compatibility-validator`.
- Explicit API mode on (`kotlin { explicitApi() }`).

## 5. Public API (package `com.vocabloot.backupkit`)

### 5.1 Shared types

```kotlin
public enum class CloudProvider { ICloud, GoogleDrive }

public sealed interface CloudAvailability {
    public data object Available : CloudAvailability
    public data object NoAccount : CloudAvailability      // iOS: no iCloud login; Android: no Google account / Play Services failure
    public data object NeedsConsent : CloudAvailability   // Android only: the one-time Drive dialog has not been accepted
}

public enum class CloudError { NotAvailable, NeedsConsent, Offline, StorageFull, AuthRevoked, NotFound, Transport }

public class CloudStorageException(
    public val error: CloudError,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** One file as the cloud sees it. [remoteId] is Drive's file id, null on iCloud. [size] is -1 when unknown (iCloud placeholder not yet downloaded). */
public data class RemoteFile(val path: String, val size: Long, val remoteId: String?)
```

Paths are relative, forward-slash separated (`notes/2026-09.json`). iCloud
stores them as nested files. Drive app-data is flat, so the path string is
used verbatim as the Drive file name. Both directions round-trip.

### 5.2 Layer 1: `CloudStorage`

```kotlin
public interface CloudStorage {
    public val provider: CloudProvider

    public suspend fun availability(): CloudAvailability

    /** Stable per (device, cloud account) when the platform can tell; null on Drive, where the engine keys identity off the marker file's id. */
    public suspend fun identityKey(): String?

    /** Every file, deduplicated by path (newest wins). */
    public suspend fun list(): List<RemoteFile>

    public suspend fun exists(path: String): Boolean

    /** Create or replace. Returns the remote id on Drive, null on iCloud. Complete-or-absent from a reader's point of view. */
    public suspend fun writeFile(path: String, localPath: String, mimeType: String, existingRemoteId: String? = null): String?
    public suspend fun writeBytes(path: String, bytes: ByteArray, mimeType: String, existingRemoteId: String? = null): String?

    /** null when absent. Forces an iCloud download first. */
    public suspend fun readBytes(path: String, remoteId: String? = null): ByteArray?

    /** Throws [CloudStorageException] with [CloudError.NotFound] when absent. */
    public suspend fun downloadFile(path: String, toLocalPath: String, remoteId: String? = null)

    /** No-op when already absent. */
    public suspend fun delete(path: String, remoteId: String? = null)
}
```

`remoteId` parameters are optional hints that save a `list()` round trip on
Drive; iCloud ignores them. All functions are safe to call from any
dispatcher and never touch the main thread except where Apple requires it
(`ubiquityIdentityToken`, read on Main).

### 5.3 Platform constructors

iOS (`iosMain`):

```kotlin
public class ICloudStorage(
    folder: String = "backupkit",           // subfolder under the ubiquity container root, outside Documents/
    containerIdentifier: String? = null,    // null = the first container in the entitlement
) : CloudStorage
```

Android (`androidMain`):

```kotlin
public fun interface DriveTokenProvider { public suspend fun accessToken(): DriveToken }
public sealed interface DriveToken {
    public data class Value(val accessToken: String) : DriveToken
    public data object NeedsConsent : DriveToken
    public data class Failed(val cause: Throwable) : DriveToken
}

/** Default: silent Play Services AuthorizationClient token for drive.appdata. */
public class PlayServicesTokenProvider(context: Context) : DriveTokenProvider

public class GoogleDriveStorage(
    context: Context,
    tokenProvider: DriveTokenProvider = PlayServicesTokenProvider(context),
    httpClient: HttpClient = BackupKitHttp.default(),
) : CloudStorage

/** The one-time consent dialog. The app wires the Activity result itself (no Compose dependency). */
public class DriveConsent(context: Context) {
    public sealed interface Request {
        public data object AlreadyGranted : Request
        public data class Needed(val intentSender: IntentSender) : Request
        public data class Failed(val cause: Throwable) : Request
    }
    public suspend fun request(): Request
    public fun wasGranted(resultData: Intent?): Boolean
}
```

### 5.4 Layer 2: `SyncEngine`

```kotlin
public sealed interface SyncSource {
    public data class LocalFile(val path: String) : SyncSource
    public data class Bytes(val bytes: ByteArray) : SyncSource
    /** Declared but missing locally: never uploaded, and the remote copy is never deleted (it may be the last one). */
    public data object Absent : SyncSource
}

/** [hash] (sha256 hex) drives the diff for small generated files; null means "compare by size" (write-once media). */
public data class SyncEntry(val path: String, val source: SyncSource, val size: Long, val hash: String? = null)

public data class SyncSnapshot(val entries: List<SyncEntry>)

public data class SyncPolicy(
    /** Uploaded LAST, only after everything else succeeded; its presence means "the remote set is complete". */
    val markerPath: String,
    /** Hashed entries in upload order, marker excluded. Everything else (size-compared) uploads before them. */
    val orderedHashedPaths: List<String>,
    /** An empty snapshot on a device that never synced must not overwrite an existing remote set. */
    val guardEmptyOverExisting: Boolean = true,
    val mimeTypeOf: (path: String) -> String = ::defaultMimeType,
)

public sealed interface SyncOutcome {
    public data class Synced(val entryCount: Int) : SyncOutcome
    public data class Unavailable(val reason: UnavailableReason) : SyncOutcome
    public data class Failed(val error: CloudError, val cause: Throwable) : SyncOutcome
}
public enum class UnavailableReason { NoAccount, NeedsConsent, RestorePending }

public data class RemoteInspection(val files: List<RemoteFile>, val marker: ByteArray?)

public class SyncEngine(
    private val storage: CloudStorage,
    private val stateStore: SyncStateStore,
    private val policy: SyncPolicy,
    private val clock: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
) {
    public suspend fun sync(snapshot: SyncSnapshot, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): SyncOutcome
    /** For a restore offer on first launch: the remote listing plus the marker bytes, null marker when the remote set is incomplete or absent. */
    public suspend fun inspect(): RemoteInspection
    public fun lastState(): SyncState
}

@Serializable
public data class SyncState(
    val provider: String = "",
    val identityKey: String = "",
    val entries: Map<String, SyncedEntry> = emptyMap(),
    val lastSuccessEpochMs: Long? = null,
    val lastEntryCount: Int = 0,
)
@Serializable public data class SyncedEntry(val size: Long, val hash: String? = null, val remoteId: String? = null)

public interface SyncStateStore { public fun load(): SyncState; public fun save(state: SyncState); public fun clear() }
/** Atomic temp+rename JSON file. A corrupt or absent file is an empty state; the next sync self-heals from the remote listing. */
public class FileSyncStateStore(path: String) : SyncStateStore
```

### 5.5 Logging

```kotlin
public fun interface BackupKitLogger { public fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) }
public object BackupKit { public var logger: BackupKitLogger = BackupKitLogger { _, _, _, _ -> } }
```

### 5.6 The ten-line quickstart (README's first code block)

```kotlin
val storage: CloudStorage = platformCloudStorage()          // ICloudStorage() / GoogleDriveStorage(context)
val engine = SyncEngine(
    storage = storage,
    stateStore = FileSyncStateStore("$appFilesDir/backupkit-state.json"),
    policy = SyncPolicy(markerPath = "backup.json", orderedHashedPaths = listOf("notes.json")),
)
val outcome = engine.sync(
    SyncSnapshot(listOf(
        SyncEntry("notes.json", SyncSource.Bytes(notesJson), notesJson.size.toLong(), hash = sha256Hex(notesJson)),
        SyncEntry("backup.json", SyncSource.Bytes(headerJson), headerJson.size.toLong(), hash = sha256Hex(headerJson)),
    )),
)
```

## 6. Reconciliation contract (ported from Vocabloot spec 5.3, unchanged in semantics)

`SyncEngine.sync` runs these steps; each is a documented guarantee in
`docs/contract.md`:

1. **Availability gate.** `NoAccount` / `NeedsConsent` return `Unavailable` without touching the cloud.
2. **Remote truth.** `storage.list()` is the source of truth; the state file is a cache. Entries in state but not remote are dropped; entries remote but unknown are adopted (size trusted, hash unknown so hashed entries re-upload).
3. **Identity.** `storage.identityKey()`, falling back to the marker's remote id, falling back to `""`. A change from the stored identity resets state (account switch = full re-upload, never cross-account merging).
4. **Empty guard.** If the snapshot is empty, `lastSuccessEpochMs == null`, and the remote holds any hashed entry, return `Unavailable(RestorePending)`; nothing is written. (Vocabloot data-loss bug fixed 6a08950f.)
5. **Diff.** Size-compared entries upload when unknown, size mismatch, or remote size differs. Hashed entries upload when the stored hash differs. The marker uploads whenever anything else uploaded or it is missing remotely.
6. **Deletes.** State entries absent from the snapshot are deleted, except the marker and except entries whose snapshot source is `Absent`.
7. **Order.** Size-compared uploads, then hashed entries in policy order, then the marker. State is saved after every single put; a crash leaves a resumable, never inconsistent, state.
8. **Deletes after uploads.** A failed delete is logged and retried next run; it never fails the sync.
9. **Drive identity adoption.** When `identityKey()` is null (Drive), the marker's remote id becomes the identity after its first upload.
10. **Progress.** `onProgress(done, total)` before the first step and after every put/delete.

## 7. Platform behaviour and limits

| | iCloud Drive | Google Drive app-data |
|---|---|---|
| Location | `<container>/<folder>/…`, outside `Documents/` | `appDataFolder`, flat |
| Writes | temp file + `NSFileCoordinator` replace; the daemon uploads | multipart create / media update via Drive v3 REST over Ktor |
| Reads | force download, poll `NSURLUbiquitousItemDownloadingStatus`, timeout 10 s (small) / 30 s (large, > 256 KB) | GET `alt=media` |
| Listing | recursive walk, skips temp and hidden, reports `.icloud` placeholders with size -1 | `files.list?spaces=appDataFolder`, paged, newest duplicate wins and stragglers deleted best-effort |
| Identity | archived `ubiquityIdentityToken` sha256; `NSUbiquityIdentityDidChangeNotification` drops the cached container | none; marker file id |
| Auth | entitlement only | silent `AuthorizationClient` token; 401 clears the token once and retries; 5xx/429/rate-limit 403 retry 3× with 1/2/4 s backoff |
| Limits | container quota (user's iCloud plan) | 5 MB single-upload cap (documented; resumable upload is a future item); counts against the user's Drive quota, unlike Auto Backup |
| Simulator | needs an iCloud login on the simulator | works with a debug SHA-1 registered in the OAuth client |

Error mapping is a table in `docs/contract.md`: Cocoa 640/4354 →
`StorageFull`; Drive `storageQuotaExceeded` → `StorageFull`; `rateLimitExceeded`
exhausted → `Transport`; other 403 → `AuthRevoked`; connection failure →
`Offline`; missing file on download → `NotFound`.

## 8. Testing

- `commonTest` (kotlin-test, coroutines-test), runs on `testAndroidHostTest` and `iosSimulatorArm64Test` (no Firebase, so the simulator test links):
  - `SyncEngineTest`: ported from Vocabloot's `CloudBackupReconcilerTest` (308 lines) against a `FakeCloudStorage`: first sync uploads all in order, marker last; unchanged snapshot is a no-op; identity change resets; empty guard; `Absent` never deletes; delete failure does not fail the run; remote-unknown entries adopted; progress counts.
  - `DriveRestClientTest`: ported from Vocabloot, Ktor `MockEngine`: paging, multipart body shape, 401 retry-once, 403 quota → `StorageFull`, 429 backoff, 404 delete is ok.
  - `FileSyncStateStoreTest`: round trip, corrupt file → empty state, atomic replace.
- Real cloud behaviour: the sample app on the founder's devices. README states honestly which paths were device-verified (Android transport in Vocabloot on 2026-09-02; iOS pending).
- CI (`ci.yml`, macOS runner): build all targets, host tests, simulator tests, `apiCheck`.

## 9. Sample app

`sample/`: a Compose Multiplatform notes list. Add/delete a note → snapshot →
`engine.sync()`; a status row shows `SyncOutcome`; a first-launch dialog uses
`engine.inspect()` to offer restore; Android shows the `DriveConsent` flow
with `rememberLauncherForActivityResult`. It uses its own iCloud container
and its own Google Cloud project placeholders (never Vocabloot's; strict
project separation).

## 10. Documentation and publishing

- README: why (user-owned cloud, no server, no accounts), the quickstart,
  the two setup pages, the contract, limits, "bring your own scheduler",
  comparison with CloudBridge / react-native-cloud-storage, Vocabloot as the
  reference consumer.
- KDoc on every public declaration; `api/backupkit.api` committed.
- Publishing: vanniktech `publishToMavenCentral` + `signAllPublications`,
  POM with license/developer/scm, GitHub Actions `publish.yml` on release,
  macOS runner, `--no-configuration-cache`. Version `0.1.0`.
- klibs.io: automatic within a month once the POM carries the GitHub URL and
  the artifact has `kotlin-tooling-metadata.json`; file their indexing
  request issue to skip the wait.

## 11. Founder tasks (cannot be done by the assistant)

1. Porkbun: add the Maven Central TXT verification record for `vocabloot.com` (edit records, never delete).
2. Central Portal: create the account, verify `com.vocabloot`, generate a user token.
3. GitHub repo secrets: `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD`, `SIGNING_KEY_ID`, `SIGNING_PASSWORD`, `GPG_KEY_CONTENTS`. The assistant generates the GPG key locally and hands over the values.
4. Google Cloud: a project for the sample app's OAuth client (not Vocabloot's).

## 12. Sequence

1. Scaffold repo, Gradle, CI, README skeleton (day 1).
2. Port Layer 1 (iCloud, Drive REST, token provider, consent) with tests (day 1 to 2).
3. Port Layer 2 (engine, state store) with the ported test suite (day 2).
4. Sample app, setup docs, contract doc (day 3).
5. Public repo + Devpost link; then Maven Central once DNS is verified (day 3 to 4).

Later, separately: Vocabloot adopts `com.vocabloot:backupkit` and deletes its
private copy; the krop upstream PR; the UI-quality lint plugin.
