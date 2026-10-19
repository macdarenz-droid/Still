# Still

An Android gallery for finding, organising and enjoying your photos and videos.
The full direction is preserved in [architecture.md](architecture.md). Development
includes the local gallery, matching-copy review and a separate control plane;
guarded deletion, cloud creation and verified backup are later milestones.

Android native was selected by the owner. One supervisor coordinates frontend,
backend and SEO builders, with an independent reviewer and separate red team at
**every checkpoint**. See [task cards](docs/delivery/task-board.md) and
[checkpoint evidence](docs/delivery/acceptance.md) for status and limitations.

## Repository

- `apps/android`: Kotlin/Jetpack Compose phone gallery. Local media stays on the
  phone; no account or API connection is needed for this milestone.
- `services/api`, `services/workflows`: TypeScript development API and durable task
  orchestration; see its README for exact authentication and command contracts.
- `packages/contracts`: versioned job, consent and policy DTOs.
- `db/migrations`: SQLite schema for local development. PostgreSQL remains the
  production architecture choice, not implemented by this foundation.
- `apps/site`: unpublished static product preview; intentionally `noindex`.
- `docs/discovery`: draft store copy and publication checks.

## Backend and checks

Use Node **24** and Python 3. Install the development tools with:

```sh
npm ci --ignore-scripts
npm run check
```

`npm run check` typechecks TypeScript, runs required contract/fault suites,
validates discovery assets and runs real SQLite probes against the Android
catalogue's shared SQL assets. Missing or empty required suites/assets fail rather
than report a false pass. The API is a local development service; authentication, consent and provider
availability are described in `services/api/README.md`. No media upload service or
production login is included. Do not expose the API to the Internet.

## Android

Use JDK 21, Android SDK platform 37 and build-tools 36.0.0. Android Studio can
install these and open `apps/android` directly. The SDK manager package names are
`platforms;android-37.0` and `build-tools;36.0.0`. Alternatively configure
`ANDROID_HOME` to an existing SDK installation or use an untracked
`apps/android/local.properties` containing `sdk.dir=/absolute/path/to/sdk`.

```sh
npm run build:android
npm run test:android
```

The debug APK is generated at
`apps/android/app/build/outputs/apk/debug/app-debug.apk`.
Install a development build on an attached emulator or phone with:

```sh
adb install -r apps/android/app/build/outputs/apk/debug/app-debug.apk
```

The Gradle wrapper pins its distribution and checksum. Debug builds use the
standard development key only. Production signing and publication require an
explicit owner decision. Unit/lint/build checks do not replace the manual device
matrix in the acceptance record.

The development app includes optional whole-app lock in Settings, using Android
authentication and relocking when you leave Still. It protects access through
Still; other galleries can still open ordinary phone photos. This is not an
encrypted vault. Native authentication and screen-protection checks remain in the
[device matrix](apps/android/DEVICE_CHECKS.md); see [the design](docs/decisions/0003-app-lock.md).

The development app adds All/Photos/Videos filters, newest/oldest and
filename sorting, visible video Play/Pause controls, and an on-phone metadata
index. Fresh Android media access governs browsing; retained index records never
restore access to unavailable media. Older Favorites references stay saved and
are matched only when fresh provider evidence is unambiguous. See
[the catalogue design](docs/decisions/0004-local-catalogue.md) and checkpoint
evidence for acceptance status. Native playback, upgrade and performance checks
remain in the device matrix.

Cleanup can scan for matching readable copies, show its comparison coverage and
let you choose keepers or explicitly protect matching content on this phone.
Scans start manually, use fresh Android-authorized media and invalidate when
their source/access evidence changes. Protection applies to matching readable
content, rather than a filename or URI. This stage has no delete/trash command
or storage-savings claim. See [the review design](docs/decisions/0005-cleanup-review.md)
and [native checks](apps/android/DEVICE_CHECKS.md) for scope and remaining limits.

## Discovery preview

```sh
python3 -m http.server 4173 --bind 127.0.0.1 --directory apps/site
```

Open `http://127.0.0.1:4173`. There is no public deployment, download link, waitlist
or visitor data collection. Domain and brand checks precede publication.

## Planned next work

Further catalogue hardening, screenshot/similarity review, guarded deletion,
selective and automatic backup with verified restoration, search and
memories, reference-guided try-on, PDF/DOCX/text conversion and AI-directed video
editing remain in scope. Provider costs, data handling, proposed extensions and
production decisions are tracked in the architecture rather than silently assumed.
