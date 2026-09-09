# Android consent, once

Google Drive app-data needs the user to agree once. BackupKit asks through Play Services' authorization dialog and stores nothing itself; the token is silent afterwards.

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

## When the dialog comes back

`CloudStorage.availability()` answers `NeedsConsent` whenever the grant is missing or was revoked from the Google account settings. Treat it as a state to render, not an error: show the one button that launches `DriveConsent.request()` again.

## Bringing your own sign-in

If the app already runs Google Sign-In, implement `DriveTokenProvider` over your existing token and pass it to `GoogleDriveStorage(context, tokenProvider = yours)`. The `drive.appdata` scope must be in your consent screen's scope list.
