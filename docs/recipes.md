# Recipes

## A SQLDelight or Room database

Do not upload the live database file: WAL pages may be in flight. Export a consistent copy first, then hand the copy to the engine as a size-compared local file:

```kotlin
// SQLite on both platforms: VACUUM INTO writes a compact, consistent copy.
driver.execute(null, "VACUUM INTO '$exportPath'", 0)   // SQLDelight; Room: db.openHelper.writableDatabase.execSQL("VACUUM INTO '$exportPath'")

val outcome = engine.sync(
    SyncSnapshot(
        listOf(
            SyncEntry("db.sqlite", SyncSource.LocalFile(exportPath), fileSize(exportPath), hash = null),   // size-compared, uploaded first
            SyncEntry("backup.json", SyncSource.Bytes(header), header.size.toLong(), hash = sha256Hex(header)),
        ),
        isEmpty = rowCount == 0L,
    ),
)
```

Restore: download `db.sqlite` as a required file to a temp path, close the app's database, move the file into place, reopen.

## A folder of photos

Photos are write-once: give them `hash = null` so they are compared by size and never re-uploaded, and keep them ahead of the hashed entries. Keep a manifest as the marker so a listing plus the marker tells you what is complete:

```kotlin
val photos = photoDir.listFiles().map { SyncEntry("photos/${it.name}", SyncSource.LocalFile(it.path), it.length(), hash = null) }
val manifest = manifestJson()
engine.sync(SyncSnapshot(photos + SyncEntry("manifest.json", SyncSource.Bytes(manifest), manifest.size.toLong(), hash = sha256Hex(manifest)), isEmpty = photos.isEmpty()))
```

## Encrypt before upload

BackupKit moves bytes; it does not encrypt. Encrypt each payload yourself, hash the ciphertext, and keep the key in the platform keystore. The marker file must also be encrypted or contain nothing sensitive, because `probe()` returns it to your restore offer before any key is available.

```kotlin
val cipher = encrypt(notesJson)
SyncEntry("notes.enc", SyncSource.Bytes(cipher), cipher.size.toLong(), hash = sha256Hex(cipher))
```

## Restore with per-record placement

Group files by the record they belong to and let the engine call you once per group:

```kotlin
RestoreEngine(engine, storage, FileRestoreRecordStore(path), placement = { group, files ->
    when (group) {
        "meta" -> importManifest(files.single().toLocalPath)
        else -> attachPhotos(recordId = group!!, files.map { it.toLocalPath })
    }
    PlacementResult.Placed
})
```

The group is an opaque string. BackupKit never learns what a record is.
