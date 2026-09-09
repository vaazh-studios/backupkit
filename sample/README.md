# BackupKit sample

A notes app in Compose Multiplatform that mirrors its notes into the user's own cloud and offers a restore on first launch. It is the README quickstart with a UI around it.

| What it shows | Where |
|---|---|
| `SyncEngine.sync` with a marker file, progress in the status line | `shared/.../NotesBackup.kt`, `App.kt` |
| `probe()` on launch, the restore dialog, `RestoreEngine.start` with a hold | `NotesBackup.restore` |
| Android's one-time Drive consent through `DriveConsent` | `androidApp/.../MainActivity.kt` |
| Recordings | pending: the sample needs a Drive OAuth client and an iCloud login to record |

## Run it

**Android.** Create a Google Cloud OAuth client for the package `com.vocabloot.backupkit.sample` with your debug signing SHA-1, enable the Drive API, and add the `drive.appdata` scope to the consent screen ([setup, Android](../docs/setup-android.md)). Then `./gradlew :sample:androidApp:installDebug`. The first "Sync" shows Google's consent dialog once.

**iOS.** Open `sample/iosApp` in Xcode, set your team, and keep the iCloud capability with the `iCloud.com.vocabloot.backupkit.sample` container (change it to one of yours if you are not on that team). Sign in to iCloud on the device or simulator. Run. `USE_CLOUDKIT` in `Platform.ios.kt` switches the transport to CloudKit; that path has not been run on a device yet.

Nothing in the sample talks to a server of ours. Delete the app and the files stay in your iCloud or Drive until you remove them there.
