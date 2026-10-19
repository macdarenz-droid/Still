# Still Android development app

Native Kotlin/Compose phone gallery, Android 8.0 (API 26) and later. This is the
development foundation, not a production release. It reads real permitted MediaStore
photos/videos, groups them by month and album, searches filenames, opens images
with zoom and videos with explicit Play/Pause and platform seek controls, and shares selected media through
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

Both the memory cache and Compose image state use a local preview fingerprint
including the source URI, MediaStore modification time, orientation, dimensions,
byte length, provider/volume/birth/revision provenance and requested preview size. Reconciled edits and rotations therefore
replace a same-URI preview. This metadata key is not a content hash, proof of
equality or canonical immutable asset-version identity.

`MediaItem.id` is an Android content URI used only as this client's transient
source/UI key. It is not the architecture's canonical opaque asset or version ID.
The local catalogue has opaque source/observation IDs and metadata revisions;
these also are not pinned immutable AssetVersions. Canonical asset integration
remains future work. Neither URIs nor these observations may authorize backend
jobs, deletion or verified backup.

This build declares no internet or write/delete permission. There are no cloud
transfers, AI providers, analytics, deletion, verified backup or generated
outputs. Those remain in the product plan and are described as future work in
Settings. Favorites and search do not infer anything about inaccessible media.
Android auto-backup is off so local preference data is not silently backed up.

## Gallery controls and local catalogue (S09/S10)

All, Photos and Videos apply to the current library, album, filename search or
Favorites view. Date sorting offers newest/oldest with unknown dates last;
Filename A–Z uses case-insensitive filename order, then original case and source
URI for deterministic ties. Filename order is global, with month headers omitted.
At the album root it orders cards by their first matching filename, rather than
sorting album titles. Changing type/search clears selection, and both sharing
and batch Favorites use only items in the visible view. Sorting retains selection.

Android 29+ reads concrete external volumes; bucket IDs include provider and
volume so identical album IDs cannot merge removable storage with the primary
volume. Android 30+ supplies per-volume provider version/generation and row
added/modified generations. Versions and generation rollback are checked around
the entire enumeration, including successfully queried empty volumes. Android
26–28 uses the legacy external namespace; Android 29 has no row birth generation.
Older-provider continuity is explicitly best effort.

Selection/viewer UI tokens include observed source identity, metadata revision
and a provider generation watermark. A reset, reused handle, edited row or
generation rollback clears the old pending action. Unchanged strongly evidenced
rows can retain it while advancing their watermark; unknown legacy continuity
is cleared on refresh. These local keys do not prove byte identity. Existing
URI-based Favorites preferences remain unchanged; no migration is attempted.

The versioned platform SQLite index stores permitted browsing metadata only,
never thumbnails or media bytes. It opens and reconciles on IO inside the
unlocked gallery. A scan token starts before querying MediaStore; refresh,
permission changes and lock disposal invalidate it without waiting for SQLite.
Live authorized rows publish before database persistence, and the index has no
browsing/read fallback. A failed/null provider read clears the live result; an
index failure instead shows an honest Retry notice alongside live browsing.
Retained rows establish neither present access nor existence/deletion. A tiny
commit-window race can retain stale metadata, but cannot report index success or
publish it as visible media. Schema mismatch/corruption is surfaced without
automatic deletion or destructive repair.

Video opens paused with a visible labelled Play/Pause button and native seek
controls. Button state follows both its own and the platform controller's actions,
completion and errors. Seeking after completion retains the user's position.
Background pauses, disposal stops/releases, source revision changes replace the
player, and returning/recreating never automatically resumes playback.

## Optional app lock (S06)

Settings can require Android device authentication before opening the whole
gallery. Enabling and disabling each require fresh authentication. API 28+
uses platform biometrics when available, with a phone-screen-lock fallback;
API 26–27 uses the phone screen lock. This protects access through Still. It
does not encrypt, move or hide the phone's original media from other apps.

An enabled session starts locked after every Activity creation. Unlock results,
attempt identities and session state exist only in memory. Leaving the
foreground, opening a new external entry or turning the screen off invalidates
access. Sharing and Android Settings therefore require authentication on return.
The gallery is absent from composition while locked or authenticating, so its
MediaStore query, thumbnails and video viewer do not run behind the gate. Only
gallery navigation and selection UI state use the saveable registry.

Each external phone-screen-lock launch has a unique result registration capturing
the exact memory-only attempt. Its single expected lifecycle handoff keeps only
that challenge; gallery access stays blocked. Recreated Activities do not restore
the registration or accept old results. Biometric mismatches permit another scan;
terminal errors, cancellation, screen-off and new entry fence stale callbacks.
If the phone screen lock is unavailable, an enabled Still stays locked and offers
Android security settings.

The only new durable data is a strict, versioned local on/off setting and a
pending-write marker. The adapter syncs marker file bytes and its parent directory
before touching the setting. It then syncs the AtomicFile record and replacement
directory entry before verification and marker removal. Directory descriptors
are opened through public Android APIs and checked with `fstat`. No fallible
operation follows marker removal; a crash that restores an unsynced deletion can
conservatively restore the marker and require authentication again. A failed
or interrupted update, malformed record or read error is treated as locked; a
marker remains until an authenticated setting update succeeds. A failed enable
is explicitly reported as unsaved, never as enabled. This protocol has JVM fault
coverage, including a model separating volatile file visibility from durable
directory entries. Actual power-loss durability and platform/OEM behavior remain
device checks.

Still requests Android screenshot/recents protection with `FLAG_SECURE` while
enabled, authenticating or recovering a failed setting. Viewer and information
dialogs inherit the Activity's secure policy; API 33+ also disables recents
screenshots. Actual screenshot/recording/recents behavior needs hardware checks.
No custom PIN, credential storage, vault or encryption is implemented.

## Verification limits

Unit tests exercise API-specific permission policy, mixed-media sharing,
selection visibility, late-thumbnail invalidation, lock-policy transitions and
setting-write failures, filter/sort order and provenance/UI continuity. The
host SQLite probe runs the real schema/statement files for rollback and reopen
checks; it does not prove Android SQLite. These checks do not prove authentication prompts,
permission dialogs, URI grants, image formats, lifecycle, codecs or accessibility
on hardware. Run the [manual device matrix](DEVICE_CHECKS.md) before accepting this
as a release candidate. Large-library indexing and performance remain unmeasured;
the current foundation reads permitted metadata in the background into memory.

The two-frame logo and Still name remain provisional until brand/store checks.
