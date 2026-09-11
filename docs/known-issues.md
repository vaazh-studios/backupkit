# Known issues

Open items we know about, in the spirit of Alamofire's radar list. Each one has a workaround.

| Area | Issue | Workaround | Status |
|---|---|---|---|
| Google Drive | App-data counts against the user's quota | Tell users in your settings copy | By design (Google) |
| iCloud Drive | Placeholder files report `size == -1` until downloaded | `prefetch()` before reading | Documented |
| CloudKit | Production has no schema until deployed | Deploy from the console before the first TestFlight build | Documented in setup |
| CloudKit | One asset per save, container quota | Keep files under a few MB each | By design (Apple) |
| Simulator | No iCloud without a simulator login | Sign in on the simulator, or test on a device | By design (Apple) |
| RestoreEngine | Marked `@ExperimentalRestoreApi` | Opt in; the shape may change in a minor release | Documented in stability |

Found another? [Open an issue](https://github.com/vaazh-studios/backupkit/issues/new/choose).
