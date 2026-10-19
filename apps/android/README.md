# Still Android development app

Native Kotlin/Compose phone gallery, Android 8.0 (API 26) and later. This is the
S02 foundation, not a production release. It reads real permitted MediaStore
photos/videos, groups them by month and album, searches filenames, opens images
with zoom and videos with platform controls, and shares selected media through
Android. Favorites are app-private metadata persisted on this phone, not system
favorites. Original media is never written or deleted.

## Build

Use a full JDK 21 (including `javac`), Android SDK platform 37 and build-tools 36.0.0. Set
`ANDROID_HOME` or put `sdk.dir=/your/android-sdk` in untracked `local.properties`.
The SDK manager's platform package is named `platforms;android-37.0`.

```sh
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest :app:lintDebug
```

The wrapper verifies its Gradle distribution against the official SHA-256 recorded
in `gradle/wrapper/gradle-wrapper.properties`.
The Gradle 9.8.1 wrapper JAR's official SHA-256 is
`3b8a25775a69158b5ad2b1d17a80a88dc7b40a352a73eb27d06c612a7ce68e98`.
The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. Standard local debug
signing is used; no production signing identity or release distribution is set up.

## Media and privacy boundary

Android 13+ image/video grants and Android 14+ selected-media grants are handled
separately. Partial access shows only accessible items and offers reselection.
Permissions are reconciled on foregrounding and permission results, including
same-bit selected-set changes. A reduced grant clears the visible viewer/selection
before querying again. Cached thumbnails are memory-only; generation fencing
rejects late decoding from an invalidated access epoch. Revocation changes
visibility, never the recorded existence of an original.

`MediaItem.id` is an Android content URI used only as this client's transient
source/UI key. It is not the architecture's canonical opaque asset or version ID.
A later persistent catalogue will map source handles to those IDs; these URIs
must never be submitted as backend job asset references.

This build declares no internet or write/delete permission. There are no cloud
transfers, AI providers, analytics, app lock, deletion, verified backup or generated
outputs. Those remain in the product plan and are described as future work in
Settings. Favorites and search do not infer anything about inaccessible media.
Android auto-backup is off so local preference data is not silently backed up.

## Verification limits

Unit tests exercise API-specific permission policy, mixed-media sharing,
selection visibility and late-thumbnail invalidation. They do not prove Android
permission dialogs, URI grants, image formats, lifecycle, codecs or accessibility
on hardware. Run the [manual device matrix](DEVICE_CHECKS.md) before accepting this
as a release candidate. Large-library indexing and performance remain unmeasured;
the current foundation reads permitted metadata in the background into memory.

The two-frame logo and Still name remain provisional until brand/store checks.
