# BackupKit

Kotlin Multiplatform backup into the user's **own** cloud: iCloud on iOS (CloudKit or iCloud Drive), the Google Drive app-data folder on Android. No server, no account on your side, no OAuth client setup on iOS.

![BackupKit](assets/hero.png)

## Start here

1. [Setup, iOS](setup-ios.md): one entitlement, and the CloudKit schema step for Production builds.
2. [Setup, Android](setup-android.md): a Google Cloud OAuth client and the one-time consent dialog.
3. The quickstart on the [README](https://github.com/vaazh-studios/backupkit#backupkit-101), then [restore on first launch](restore.md).

## Guides

- [Support matrix](support-matrix.md): the three transports side by side.

- [The SyncEngine contract](contract.md): the ten guarantees.
- [Restore on first launch](restore.md): probe, hold, download, place, resume.
- [Android consent, once](consent-android.md).
- [Errors](errors.md): the seven `CloudError` values and the user copy for each.
- [Bring your own scheduler](scheduling.md).
- [Recipes](recipes.md): SQLDelight, Room, a folder of photos, encrypt before upload.
- [FAQ](faq.md) and [known issues](known-issues.md).
- [Stability and versions](stability.md).

## Reference

- [API reference](api/index.html) (Dokka), binary API tracked in `backupkit/api`.
- [Design](design.md) and [publishing](publishing.md), for maintainers.
