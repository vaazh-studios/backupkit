# BackupKit

Kotlin Multiplatform backup into the user's **own** cloud: iCloud Drive on iOS, the Google Drive
app-data folder on Android. No server, no account on your side, no OAuth client setup on iOS.

[![Maven Central](https://img.shields.io/maven-central/v/com.vocabloot/backupkit)](https://central.sonatype.com/artifact/com.vocabloot/backupkit)
[![CI](https://github.com/vaazh-studios/backupkit/actions/workflows/ci.yml/badge.svg)](https://github.com/vaazh-studios/backupkit/actions/workflows/ci.yml)

## Why

Most apps that promise "backup" run a server and an account. Both platforms already give every
user a private, quota-backed folder that only your app can see: the iCloud ubiquity container and
Drive's `appDataFolder` (the one WhatsApp uses). BackupKit is the missing Kotlin layer over both,
extracted from [Vocabloot](https://vocabloot.com), where it ships in production.

## Install

```kotlin
commonMain.dependencies {
    implementation("com.vocabloot:backupkit:0.1.0")
}
```

Then do the platform setup once: [iOS](docs/setup-ios.md) (an entitlement), [Android](docs/setup-android.md) (a Google Cloud OAuth client).

## Quickstart

```kotlin
val storage: CloudStorage = platformCloudStorage()      // ICloudStorage() on iOS, GoogleDriveStorage(context) on Android

val engine = SyncEngine(
    storage = storage,
    stateStore = FileSyncStateStore("$appFilesDir/backupkit-state.json"),
    policy = SyncPolicy(markerPath = "backup.json"),
)

val notes = notesJson()          // ByteArray
val header = headerJson()        // ByteArray, uploaded last: its presence means "complete"
val outcome = engine.sync(
    SyncSnapshot(
        listOf(
            SyncEntry("notes.json", SyncSource.Bytes(notes), notes.size.toLong(), hash = sha256Hex(notes)),
            SyncEntry("backup.json", SyncSource.Bytes(header), header.size.toLong(), hash = sha256Hex(header)),
        ),
    ),
)
when (outcome) {
    is SyncOutcome.Synced -> showUpToDate()
    is SyncOutcome.Unavailable -> showWhy(outcome.reason)   // NoAccount, NeedsConsent, RestorePending
    is SyncOutcome.Failed -> showStuck(outcome.error)       // Offline, StorageFull, AuthRevoked, Transport, ...
}
```

Large write-once files (photos) go in as `SyncSource.LocalFile(path)` with `hash = null`: they are
compared by size, uploaded before the hashed entries, and never re-uploaded. Pass
`SyncSnapshot(entries, isEmpty = notes.isEmpty())` so a fresh install with no data never overwrites
an existing backup (the engine answers `RestorePending` instead).

## Two layers

| | Type | Use it when |
|---|---|---|
| Layer 1 | `CloudStorage` | You want files in the user's cloud and your own logic on top: `writeFile`, `writeBytes`, `readBytes`, `downloadFile`, `delete`, `list`, `exists`, `availability`. |
| Layer 2 | `SyncEngine` | You want "mirror this set of files, safely": diffing, ordering, resume after a kill, account-switch detection, a restore offer. See [the contract](docs/contract.md). |

Both implementations behave identically, with two documented differences: Drive has file ids
(`RemoteFile.remoteId`) and a 5 MB single-upload cap; iCloud reports not-yet-downloaded files with
`size == -1`.

## Restore on first launch

```kotlin
val remote = engine.inspect()
if (remote.marker != null) offerRestore(parseHeader(remote.marker))
```

Then `storage.downloadFile(path, toLocalPath)` for each `remote.files` entry you want. Importing the
files into your own data structures is your code; BackupKit never guesses your schema.

## Android consent, once

```kotlin
val consent = DriveConsent(context)
val launcher = rememberLauncherForActivityResult(StartIntentSenderForResult()) { result ->
    granted = consent.wasGranted(result.data)
}
scope.launch {
    when (val r = consent.request()) {
        is DriveConsent.Request.Needed -> launcher.launch(IntentSenderRequest.Builder(r.intentSender).build())
        DriveConsent.Request.AlreadyGranted -> granted = true
        is DriveConsent.Request.Failed -> showError(r.cause)
    }
}
```

Already running Google Sign-In? Pass your own `DriveTokenProvider` to `GoogleDriveStorage`.

## Scheduling

BackupKit does one sync per call and nothing in the background. A debounce plus a foreground
trigger is twelve lines: [docs/scheduling.md](docs/scheduling.md).

## Errors

Every failure is a `CloudStorageException` carrying one `CloudError`:
`NotAvailable`, `NeedsConsent`, `Offline`, `StorageFull`, `AuthRevoked`, `NotFound`, `Transport`.
`SyncEngine.sync` never throws for cloud failures; it returns `SyncOutcome.Failed(error)`.

## Limits and honesty

- Drive: multipart uploads are capped at 5 MB per file. Resumable uploads are on the roadmap.
- Drive app-data counts against the user's Drive quota (Android Auto Backup does not).
- iCloud on the simulator needs an iCloud login on the simulator.
- Verified on real devices: the Android transport, in Vocabloot (September 2026). The iOS transport
  ships in Vocabloot too; the sample app on a device is the reference check.
- Not included: scheduling, encryption, restore-into-your-model, any UI, Dropbox/OneDrive
  (see [CloudBridge](https://github.com/jacobras/CloudBridge) for those).

## Related work

[react-native-cloud-storage](https://github.com/kuatsu/react-native-cloud-storage) inspired the
Layer 1 verbs. [IceCream](https://github.com/caiyue1993/IceCream) and Apple's `CKSyncEngine`
inspired Layer 2's "engine owns the state" shape.

## License

Apache 2.0. Made by [Vaazh Studios](https://github.com/vaazh-studios).
