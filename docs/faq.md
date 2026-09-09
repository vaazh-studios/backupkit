# FAQ

**`availability()` says `NotAvailable` on my iPhone.** The iCloud entitlement is missing from the target, or the device has no iCloud account. Check `Signing & Capabilities` for iCloud with the container you pass, and Settings on the phone for a signed-in Apple Account.

**Backups worked in Xcode builds and fail in TestFlight with `Transport`.** CloudKit's Production environment has no schema until you deploy it. Xcode builds use Development, where record types are created on the fly. Open the CloudKit console, switch to Development, press "Deploy Schema Changes" and confirm. Once per record type change. See [setup, iOS](setup-ios.md).

**The simulator never finds iCloud.** The simulator needs an iCloud login in its own Settings app. Without one, `ICloudStorage` and `CloudKitStorage` answer `NotAvailable`.

**Android keeps asking for consent.** `NeedsConsent` comes back whenever the grant is missing or revoked. Launch `DriveConsent.request()` from a user tap, not from a loop; see [Android consent](consent-android.md).

**`sync()` answered `RestorePending` and uploaded nothing.** The snapshot was marked empty and the cloud already holds a backup. This is the guard against a fresh install wiping a user's backup. Offer a restore; see [restore](restore.md).

**A 6 MB photo fails on Drive.** Multipart uploads cap at 5 MB per file. Downscale before you back up, or split. Resumable uploads are on the roadmap.

**`list()` on iCloud Drive reports `size == -1`.** The file is a placeholder that has not been downloaded to this device yet. `prefetch(paths)` before `readBytes` or `downloadFile`.

**The user switched accounts.** `identityKey()` changed, so the engine answers `SourceChanged` on resume and refuses to merge two accounts. Ask the user which one they meant.

**Can I encrypt?** Yes, before you hand bytes to the engine; hash the ciphertext. See [recipes](recipes.md).

**Does it run in the background?** No. BackupKit does one sync per call. [Bring your own scheduler](scheduling.md).
