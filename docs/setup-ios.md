# iOS setup

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

**Simulator:** sign the simulator into an iCloud account (Settings → Sign in) or `availability()`
returns `NoAccount`. **Account switch:** the container is re-resolved automatically; `SyncEngine`
detects the new identity and starts a fresh set rather than merging two accounts.
