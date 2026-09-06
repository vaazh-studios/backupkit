# Contributing

Thanks for looking. BackupKit is small on purpose; the best contributions keep it that way.

**Bugs.** Open an issue with the platform, the `CloudError` you got, and the smallest snippet that reproduces it. Logs from `BackupKit.logger` help; never paste tokens or file contents.

**Changes.** Open an issue first for anything that touches the public API or the sync contract (`docs/contract.md`). For fixes, a pull request with a test is enough:

```bash
./gradlew :backupkit:testAndroidHostTest :backupkit:iosSimulatorArm64Test :backupkit:apiCheck
```

`apiCheck` fails when the public API changed; run `./gradlew :backupkit:apiDump` and commit the diff when the change is intended.

**Style.** Explicit API mode, KDoc on every public declaration, no new dependencies without an issue explaining why.

**Device verification.** iCloud and Drive behaviour can only be checked on real devices with the sample app; say in the pull request what you ran.
