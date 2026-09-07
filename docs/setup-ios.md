# iOS setup

Two transports, one entitlement each. Pick **CloudKit** (`CloudKitStorage`) for app data the user never opens as files; pick **iCloud Drive** (`ICloudStorage`) when the files must show in the Files app.

## CloudKit (`CloudKitStorage`)

1. **Apple Developer portal → Identifiers → your App ID → Capabilities**: enable **iCloud** with CloudKit support and create or select a container, for example `iCloud.com.example.myapp`.
2. **Xcode → target → Signing & Capabilities → + Capability → iCloud**: tick **CloudKit** and select the container. Xcode writes:
   ```xml
   <key>com.apple.developer.icloud-container-identifiers</key>
   <array><string>iCloud.com.example.myapp</string></array>
   <key>com.apple.developer.icloud-services</key>
   <array><string>CloudKit</string></array>
   ```
3. In Kotlin:
   ```kotlin
   val storage = CloudKitStorage(
       checkpointPath = "$filesDir/backupkit/cloudkit-checkpoint.json",
       cacheDirectory = "$filesDir/backupkit/cloudkit-cache",
       containerIdentifier = "iCloud.com.example.myapp",   // null = the default container
       environment = if (isDebugBuild) "development" else "production",
   )
   ```
   `environment` only salts the checkpoint so a debug build (Development container) and a TestFlight build (Production) on one device never share a change token.
4. **Before the first TestFlight or App Store build**, open the CloudKit console and **deploy the schema to Production**: the `BackupEntry` record type and the zone are created automatically the first time a debug build saves, but only in Development. A Production build against a missing schema fails its first save.

Data shape: one custom zone (`backupkit` by default), one `BackupEntry` record per file with fields `path`, `size` and the bytes in a `content` asset. Record names are the path with `/` replaced by `__`. Everything lives in the user's private database and counts against their iCloud quota.

## iCloud Drive (`ICloudStorage`)

BackupKit writes into your app's iCloud ubiquity container. No client id, no token, no sign-in
code: the entitlement is the whole setup.

1. **Apple Developer portal → Identifiers → your App ID → Capabilities**: enable **iCloud**, then
   **Edit** and create or select an iCloud Container, for example `iCloud.com.example.myapp`.
2. **Xcode → target → Signing & Capabilities → + Capability → iCloud**: tick **iCloud Documents**
   and select the same container. Xcode writes it into your `.entitlements`:
   ```xml
   <key>com.apple.developer.icloud-container-identifiers</key>
   <array><string>iCloud.com.example.myapp</string></array>
   <key>com.apple.developer.icloud-services</key>
   <array><string>CloudDocuments</string></array>
   <key>com.apple.developer.ubiquity-container-identifiers</key>
   <array><string>iCloud.com.example.myapp</string></array>
   ```
3. Regenerate provisioning profiles (automatic signing does it on the next build).
4. In Kotlin: `ICloudStorage()` uses the first container; pass `containerIdentifier = "iCloud.com.example.myapp"` to pick one.

Files live under `<container>/backupkit/` (change the folder with `ICloudStorage(folder = ...)`),
outside `Documents/`, so they never show in the Files app.

**Simulator (both transports):** sign the simulator into an iCloud account (Settings → Sign in) or `availability()`
returns `NoAccount`. **Account switch:** the container is re-resolved automatically; `SyncEngine`
detects the new identity and starts a fresh set rather than merging two accounts.
