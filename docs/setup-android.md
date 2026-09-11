# Android setup

BackupKit uses the Google Drive **app-data folder** through Play Services' `AuthorizationClient`.
There is no sign-in screen: the user sees Google's permission dialog once, then tokens are silent.

1. **Google Cloud Console → create or pick a project** (a separate one from any other app of yours).
2. **APIs & Services → Library**: enable **Google Drive API**.
3. **APIs & Services → OAuth consent screen**: External, add the scope
   `https://www.googleapis.com/auth/drive.appdata` (non-sensitive, no verification needed).
4. **Credentials → Create credentials → OAuth client ID → Android**: package name plus the SHA-1 of
   every signing key you use (debug keystore, upload key, and the Play App Signing key from Play
   Console → App integrity). No client id goes into your code; Google matches by package + SHA-1.
5. `AndroidManifest.xml` needs `<uses-permission android:name="android.permission.INTERNET" />`.
6. In Kotlin: `GoogleDriveStorage(context)`. Before the first sync run the consent flow with
   `DriveConsent` (see the README) or `availability()` returns `NeedsConsent`.

**Uninstall** drops the grant; the next install asks once more. **Quota:** app-data counts against
the user's Drive storage. Files above 5 MB upload through Drive's resumable protocol; the quota is the only limit.
