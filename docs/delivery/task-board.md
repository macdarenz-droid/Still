# Still foundation delivery

Supervisor: root Codex session. Repository: macdarenz-droid/Still. Base: b31a7ab.
User authorized implementation on 19 October 2026 and confirmed Android native first.
The architecture document is retained in full. This milestone begins its foundation;
required later capabilities are tracked below, not removed.

## Task cards and ownership

| Card | Builder / ownership | Deliverable and acceptance | Dependencies / order |
| --- | --- | --- | --- |
| S00 Bootstrap | Supervisor; root manifests, lockfile, AGENTS/CLAUDE, architecture, README, CI, scripts, docs/delivery and docs/decisions | Real install/typecheck/test/build commands; named task branches; independent review; reproducible setup and explicit limitations | First |
| S01 Control plane | backend; packages/contracts, services/api, services/workflows, db/migrations, tests/contracts, tests/faults | Versioned typed DTOs; durable SQLite jobs/consent/outbox; authenticated owner isolation; numeric bounded budget; revision/idempotency validation; no provider pretence; contract/fault tests including reopen | S00 then integrate before S02 cloud work |
| S02 Android gallery | frontend; apps/android (including build scripts and wrapper, supervisor approves dependency versions below) | Compose gallery using real MediaStore; full/limited/denied access and reselection; image/video viewer, system sharing, local favorites/selection, albums/search. Debug APK, unit and lint checks; manual device matrix explicitly pending when unavailable | S00; local gallery independent of API |
| S03 Discovery | seo; apps/site, docs/discovery | Accessible responsive early-development page, honest planned capabilities, no fake downloads; noindex until domain and brand checks. Store listing draft and launch checks. Static validation and browser review if available | S00; native app remains primary |
| S04 Independent acceptance | reviewer and red_team; review only, no implementation ownership | At every checkpoint, separate independent review AND adversarial red-team pass on exact SHA. Findings require fixes/recheck before acceptance. Final checks and artifact hashes in acceptance record | CP0 bootstrap, CP1 integrated foundation, CP2 final handoff |

All builders work on named branches in separate worktrees. No builder edits another
card’s files. Supervisor owns integration and new dependencies. Merge sequence is
S00 → S01/S02/S03 → S04; only task branches integrate, no direct main push or merge.

## Mandatory checkpoint gates

The owner requires an independent reviewer and a separate red team at **every
checkpoint before shipping** (added 19 October 2026). CP0 covers bootstrap; CP1
covers the integrated app/API/discovery foundation; CP2 covers the final reviewable
branch, checks and artifacts. Each records exact SHA, reviewer outcome, red-team
outcome, unresolved findings and validation. Neither builders nor supervisor
self-approve. Material fixes are rechecked by both roles. No checkpoint acceptance
or shipping while either required pass is absent or unresolved blocking findings
remain. A draft PR/debug artifact is a development handoff, not publication.

## Approved bootstrap choices

- Android native is confirmed. Kotlin/Compose, min SDK 26, compile/target SDK 37.
- Current Android dependency allowance (ADR0002): AGP 9.4.1, Gradle 9.8.1,
  built-in Kotlin with Kotlin/Compose 2.4.21, Compose BOM 2026.09.00,
  AndroidX core 1.19.1, activity 1.13.0, lifecycle 2.11.0 and JUnit 4.13.2.
  SDK platform 37/build-tools 36.0.0, JDK 21 and minSdk 26. The initial pins were
  superseded after strict lint identified current versions; no lint gate waived.
  Builder can choose Material3 and foundation within the approved Compose BOM.
- Backend development runtime Node 24 with built-in node:sqlite, TypeScript 5.9
  and @types/node 24; no third-party runtime dependencies. Use native type stripping
  and .ts imports; tsc verifies types separately. SQLite is local foundation storage,
  not an unannounced replacement for the architecture’s production PostgreSQL.
- Static SEO preview has no dependencies, no deployment or data collection.
- Local app catalogue remains on the phone; no full-library upload or metadata mirror.
  Backend accepts opaque per-job references and purpose/consent records only.

## Card boundaries, risks and finish conditions

S01 excludes provider connections, remote media storage, workers that imply output,
production login and a deployment. Risks: invalid consent/budget/retry and owner
isolation; prove admission/restart cases in tests. Schema migration is versioned SQL;
rollback is stopping the dev API and restoring a copied SQLite database, never
rewriting user media. Finish: committed paths, check results, known limits and review.

S02 excludes deletion, AI outputs, backup verification, vault/security guarantees,
app-store replacement claims and production signing. MediaStore reads are permission
scoped. Favorites are app metadata, not system favorites. No Internet permission or
cloud upload is needed for the local gallery. Risks: limited/revoked access, large
libraries, thumbnail lifecycle, external media changes, accessibility and device
performance; automated checks supplement rather than replace real-device checks.
Rollback: uninstall development build; originals are unchanged. Finish: APK compilation
and available local checks, source review, manual matrix clearly recorded.

S03 excludes publication, paid keyword tools, analytics, waitlist collection and
store submission. Main risk is claiming planned capabilities are shipping. Verify
robots restrictions, visible development status, metadata, semantic headings and
links. Rollback: remove local preview assets. Finish: valid local page, listing
draft and launch checklist; no public URL implied.

## Commands and evidence

Use npm ci; npm run typecheck; npm test; npm run check:site; npm run build:android;
npm run test:android. These commands become passed only when actually executed.
Android SDK provisioning and local.properties are machine-local; never commit keys.
Store checkpoint SHA, commands, environment, results and debug APK SHA-256 in
docs/delivery/acceptance.md. The final handoff head and CP2 verdicts are recorded
after that commit in .artifacts/cp2-validation.json and the draft PR description. Native device checks must be marked blocked if no
device is attached. Implemented, independently reviewed, candidate accepted and
published are separate statuses. No publication is part of this task.

## Current checkpoint state

CP0 scaffold/workflow: accepted with independent reviewer and red-team passes.
S01 backend and S03 discovery components: both roles passed exact source commits;
the original S03 reflow failure and regression fix remain in acceptance evidence.
S02 Android: current APK build, 8 JVM tests and strict lint pass. Both independent
roles passed the final preview-regression fix after reopening the earlier source
pass. All components are integrated; combined CP1 passed both roles on exact
1f35568d9cb42204b573b26d47636ccbfec16aed with all required code/build checks green.
CP2 verifies the final handoff head; its exact SHA/results/verdicts are retained in
the draft PR description and local validation record. Nothing is published.
CP3 adds optional whole-app lock; its exact final head, checks, APK identity and
both independent verdicts are recorded in the same draft PR and ignored
`.artifacts/cp3-validation.json`. Native lock device acceptance is separate.

## Remaining product sequence

Retain all F01–F45 and required R01–R18 in architecture.md as the canonical scope.
After CP3 implementation: local catalogue resilience; cleanup with protected
keepers/deletion journal; selected backup, verification and restore; screenshot
intelligence and meaningful memories; try-on with bounded review; PDF/DOCX/text;
AI-directed video plans/rendering; approved extensions and release hardening.
Provider/budget/account/vault/share decisions remain owner decisions before those
services are enabled. Proposed features remain proposals; no silent approval.

## CP3 app lock (R14 / F04)

Development continues from CP2 `42432e6`; physical-device release checks are a
separate gate. The owner supplied five phone screenshots on 19 October 2026.
They show gallery navigation, populated albums, an album grid, empty Favorites
and Settings, with about 17,000 visible-library items. They are visual observations,
not proof of playback, sharing, permissions, performance or this next checkpoint.
Device model, Android version and installed APK identity are unverified. Private
screenshots/media must not be committed or reused in discovery assets.

| Card | Owner / allowed files | Deliverable / acceptance | Dependencies |
| --- | --- | --- | --- |
| S05 Lock policy | backend; `apps/android/app/src/main/java/app/still/gallery/security/LockSession.kt`, matching `security/LockSessionTest.kt` in test tree | Pure Kotlin session state machine with default-locked startup when enabled, authenticated enable/disable/unlock, immediate background relock, fresh attempt fencing, explicit credential round-trip handling, canceled/late/failed results denied, persistence failure surfaced. Meaningful JVM tests for unauthorized transitions, restarts and lifecycle races | Base CP2; send interface to frontend before integration; no Android UI/dependency edits |
| S06 Native lock | frontend; other new files under Android `security/` and focused matching security tests (excluding S05 files), `ui/AppLock*.kt`, plus explicitly delegated `MainActivity.kt`, `StillApp.kt`, `strings.xml`, source manifest, Android README and device matrix | Platform biometric/device credential authentication (device credential fallback on API26+); Settings opt-in and authenticated removal; relock on leaving foreground; secure recents/screenshots while enabled; no gallery composition/media access behind lock; process/recreation starts locked. Build, all policy tests and strict lint; expanded manual matrix remains unverified until actually run | S05 interface; no new dependency approved/required: use platform facilities. Supervisor owns integration of delegated entry-point/manifest/settings changes |
| S07 Discovery audit | seo; review only for this checkpoint | Review supplied UI evidence and proposed lock wording for truthful capability/privacy/accessibility claims; return recommendations, no private media copies and no publication | Current code and task card; supervisor/frontend integrate applicable copy |
| S08 CP3 gates | reviewer and red_team; review only | Independent exact-SHA implementation/test/artifact review and adversarial authentication/lifecycle checks. Fix and recheck material findings before CP3 acceptance | S05/S06 integrated and full checks; no production release |

Supervisor owns this board, acceptance records, PR, build/dependency configuration,
and shared integration. Builders use new named branches in their existing separate
worktrees from CP2; latest origin/main is incorporated before final review.
Merge order S05 then S06, then CP3 review. No builder self-approves.
Frontend explicitly delegated only `ui/AppLock.kt` accessibility/copy polish to
`/root/frontend/android_tooling`; native wiring and all other S06 files stayed
with frontend. SEO remained read-only for this checkpoint.

Scope: a whole-app access gate only. No custom PIN, vault, encryption, account,
cloud transfer, new media metadata collection, deletion or production signing.
Only the local lock-enabled setting is durable (strict versioned platform AtomicFile
record; no silently reset or cached SharedPreferences security flag); authentication/session results
are memory-only and must never be restored from saved UI state. Existing backup
exclusions remain. An ordinary phone gallery can still access ordinary photos.
If device credentials become unavailable while enabled, remain locked and offer
Android security settings; do not silently disable protection. Backgrounding
includes leaving for sharing or system settings; reauthentication on return is
intentional. Screen-off and external entry must invalidate stale authorization.

Risk focus: biometric/device-credential callback races, background/rotation/process
restart, disable bypass, stale prompts, chooser return and app-switcher leakage.
Tests must exercise the state transitions, not duplicate their implementation.
No main merge, release claim or device-security guarantee follows from JVM checks.
Rollback: revert the CP3 development commits or uninstall the development build;
uninstall clears the app's settings, not the phone's media. No data schema changes.

Commands: `npm run check`; `npm run build:android -- --max-workers=4`;
`npm run test:android -- --max-workers=4`, with the established JDK21/SDK37
environment. Record results, exact source head and local APK SHA-256, separate
reviewer/red-team verdicts, and all blocked device checks before handing off CP3.

## Next checkpoint — CP4 local index and gallery controls (R01 / F01 / F02)

The owner authorized continuing other features after lock, retaining independent
reviewer and red-team gates. CP3 development acceptance passed at `e285a22`;
its physical-device lock/security checks remain open and do not block new work.

| Card | Owner / allowed files | Deliverable and acceptance | Dependencies / merge order |
| --- | --- | --- | --- |
| S09 Local catalogue | backend; new `apps/android/app/src/main/java/app/still/gallery/catalogue/`, matching new `catalogue/` JVM tests, `apps/android/app/src/main/assets/catalogue/`, `scripts/test-android-catalogue.py` | Versioned platform SQLite local index; durable opaque observation/source IDs, source namespace/incarnation and metadata revisions; transactional reconciliation/upsert with unchanged-row handling, restart retention, reset/reused-handle separation and stale-scan fencing. No missing-source deletion inference. Meaningful pure-policy tests plus real host-SQLite schema/rollback/reopen probes. Publish interface to frontend promptly | Base CP3; backend owns schema and index contract; no new runtime/test dependency. Root wires host probe into required checks |
| S10 Native gallery | frontend; `media/DeviceMedia.kt`, `media/MediaPolicy.kt`, scoped new media tests (preserve existing assertions), `ui/StillApp.kt`, `ui/MediaViewer.kt`, new `ui/LibraryControls*.kt` and matching focused tests, `res/values/strings.xml`, Android README/device matrix | Integrate local index only inside unlocked gallery on IO; fresh authorized MediaStore snapshot remains visibility authority. Add All/Photos/Videos filter, newest/oldest/name sort, stable ties and accessible selection state. Explicit visible video Play/Pause controls with existing seek/platform controls and lifecycle pausing. No autoplay; retain favorites, sharing, permission and lock behavior | Backend interface first; controls can build independently. Explicitly delegated shared UI/media regions, supervisor integrates. Merge S09 then S10 |
| S11 Copy audit | seo; read only | Review new index/status/filter/sort/playback wording for truthful scope and accessibility; no fake offline/cloud/deletion or immutable-version guarantees, no private-media reuse or publication | S09/S10 working UI and labels |
| S12 CP4 gates | reviewer and red_team; review only | Independent exact-source/artifact review plus adversarial stale-index, revocation, reset, source reuse, failure/restart, SQL boundary and player lifecycle checks; fixes require both rechecks | Final integrated checks and local APK; no production release |

Builders create new named branches in their existing separate worktrees from the
CP4 card commit. Supervisor owns CI, package scripts/manifests, task/evidence/ADR
records, final integration and PR. New dependencies require supervisor approval;
none are approved/needed here. Existing tests and lint guards remain mandatory.

Data scope is the already-authorized on-phone gallery catalogue from architecture
sections 7–8: metadata already read for browsing, local opaque IDs and observation
revisions. No media bytes, thumbnails, OCR, biometrics, cloud mirror, account data
or analytics are persisted or transmitted. Backup remains disabled. Do not label a
metadata fingerprint as a hash, immutable AssetVersion, byte equality or canonical
server-job input. Use namespaced provider/volume identity and source generations
when available; any older-platform continuity fallback is explicitly best effort.
No deletion/backup authorization may rely on these unpinned observations.

Fresh authorized provider reads govern every visible result. Cached records must
never expose withdrawn media or become fallback browsing after permission/query
failure. Missing rows in a partial/failed scan are not deletion evidence. Retain
catalogue records without claiming a file exists or is deleted. Stale/canceled
scan results cannot publish after a newer invalidation or lock disposal. Index
failure must be honest and recoverable; browsing authorized live media may remain
available, but no false successful indexing status or destructive capability.

Favorites remain in their existing app-private preference store for this bounded
checkpoint; no lossy or URI-only migration is introduced. Protection/deletion,
canonical pinned content versions and verified backup are subsequent work. Keep
the supplied architecture unchanged. No data-drop upgrade/downgrade or destructive
automatic repair; schema mismatch/corruption is surfaced. Rollback the CP4 changes
or uninstall the development app (clears local data, preserves phone originals).

Finish: committed owned paths, exact SHA, mappings from acceptance to tests,
`npm run check` including the new host SQLite probe, Android assemble/all JVM/strict
lint, actual local APK hash and both independent verdicts. Host SQLite probes
validate real SQL behavior but do not prove Android/OEM SQLite, MediaStore, codecs,
accessibility or performance. Expand the manual device matrix and mark all unrun
rows unverified. Hosted CI and draft PR updates follow local gates; no main merge,
release signing, publication, provider spending or deletion is authorized here.
