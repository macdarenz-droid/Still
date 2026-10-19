# Still

An Android gallery for finding, organising and enjoying your photos and videos.
The full direction is preserved in [architecture.md](architecture.md). This first
milestone builds the local gallery and a separate development control plane;
cloud creation, cleanup and verified backup are later milestones.

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

`npm run check` typechecks TypeScript, runs required contract/fault suites and
validates discovery assets. Missing test suites fail rather than report a false
pass. The API is a local development service; authentication, consent and provider
availability are described in `services/api/README.md`. No media upload service or
production login is included. Do not expose the API to the Internet.

## Android

Use JDK 21, Android SDK platform 37 and build-tools 36.0.0. Android Studio can
install these and open `apps/android` directly. Alternatively configure
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

## Discovery preview

```sh
python3 -m http.server 4173 --bind 127.0.0.1 --directory apps/site
```

Open `http://127.0.0.1:4173`. There is no public deployment, download link, waitlist
or visitor data collection. Domain and brand checks precede publication.

## Planned next work

App lock and local-catalogue resilience, duplicate/screenshot review, guarded
cleanup, selective and automatic backup with verified restoration, search and
memories, reference-guided try-on, PDF/DOCX/text conversion and AI-directed video
editing remain in scope. Provider costs, data handling, proposed extensions and
production decisions are tracked in the architecture rather than silently assumed.
