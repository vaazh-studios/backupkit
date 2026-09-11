# Stability and versions

## Versioning

Semantic versioning from 1.0. Before 1.0, a minor version may change the API; the changelog says so in its first line and the binary API dump in `backupkit/api` shows the exact diff.

## Experimental APIs

`@ExperimentalRestoreApi` marks `RestoreEngine` and its types. Experimental means: the shape can change in a minor version. Opt in with `@OptIn(ExperimentalRestoreApi::class)`.

## Requirements

| | Minimum | Built and tested with |
|---|---|---|
| Kotlin | 2.3 | 2.3.20 |
| Android Gradle Plugin | 9.0 (`androidLibrary` KMP plugin) | 9.2.1 |
| Gradle | 9.0 | 9.4.1 |
| Android | minSdk 24, compileSdk 36 | Pixel 7 Pro, Android 16 |
| iOS | 16 | iPhone 16 Pro, iOS 26 |
| Xcode | 16 | 26 |
| Coroutines / Serialization / kotlinx-io | 1.10 / 1.8 / 0.8 | 1.10.2 / 1.8.1 |

## Migration guides

None yet. From the first breaking release on, each major version gets a guide here.
