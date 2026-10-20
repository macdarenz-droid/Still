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
- Current Android dependency allowance (ADR0002): AGP 9.4.1, Gradle 9.8.2,
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
S02 Android: foundation APK build, 8 then-current JVM tests and strict lint passed. Both independent
roles passed the final preview-regression fix after reopening the earlier source
pass. All components are integrated; combined CP1 passed both roles on exact
1f35568d9cb42204b573b26d47636ccbfec16aed with all required code/build checks green.
CP2 verifies the final handoff head; its exact SHA/results/verdicts are retained in
the draft PR description and local validation record. Nothing is published.
CP3 optional whole-app lock passed both independent development gates at
`e285a22d477a04f39fd7ea0d02758f44cc4486df`, with local checks and all four hosted
checks green. Its exact APK/evidence is retained in acceptance.md and ignored
`.artifacts/cp3-validation.json`. Native lock device acceptance is separate.
CP4 catalogue/controls passed both independent development gates at
`1b4932b28d2afe9835cccd4f1d35783a713b5b6a`, with all72 JVM tests, build/lint,
repository and all four hosted checks green. Its exact local APK/verdicts are
retained in acceptance.md and `.artifacts/cp4-validation.json`; native checks remain open.
CP5 matching-copy review/protection passed both independent development gates at
`1cc49a6e3ca4a23adffbb1eaa52e33183c1aca4c`, with129 JVM tests, build/lint,
repository checks and all four hosted checks green. Exact local APK/verdicts are
retained in acceptance.md and `.artifacts/cp5-validation.json`; native checks remain open.
The owner then selected the first DRIFT design sample for native implementation.
CP6 cards below define that work; browser design approval is separate from native acceptance.

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

## CP4 local index and gallery controls (R01 / F01 / F02)

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
Backend explicitly delegated only `scripts/test-android-catalogue.py` to
`/root/backend/catalogue_sql_probe`. Frontend delegated only the new
`ui/LibraryControls.kt` to `/root/frontend/android_tooling`. Parent builders retain
their remaining source ownership, tests, commits and integration accountability.

After the controls handoff, the supervisor approved reassigning that same helper
only the new `media/MediaFavoriteCompatibilityTest.kt` regression suite. Frontend
retained all compatibility implementation, native queries, UI and commits.

The supervisor subsequently delegated only the `AppLockRoot` → `StillApp` call
in `ui/AppLock.kt` to frontend, to forward current controller access authority.
No authentication/controller/policy change is included. A non-saveable, one-way
gallery lifetime fence combines with that live authority for queued actions,
external permission/settings launches and playback starts. This closes the
reviewed pre-disposal/background and removed-gallery callback paths; native event
timing remains a device check.

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
S10 must preserve pre-CP4 aggregate-URI Favorites when rows become concrete-volume
URIs. A fresh provider-resolved, unique alias proof may support compatibility in
memory without rewriting saved keys. Ambiguous/unavailable references remain
retained and honestly surfaced; never guess a volume or attach an inaccessible
favorite. Explicit unfavorite removes only the proven matching saved references.

Finish: committed owned paths, exact SHA, mappings from acceptance to tests,
`npm run check` including the new host SQLite probe, Android assemble/all JVM/strict
lint, actual local APK hash and both independent verdicts. Host SQLite probes
validate real SQL behavior but do not prove Android/OEM SQLite, MediaStore, codecs,
accessibility or performance. Expand the manual device matrix and mark all unrun
rows unverified. Hosted CI and draft PR updates follow local gates; no main merge,
release signing, publication, provider spending or deletion is authorized here.

## CP5 exact-match review and protected content (R02 / F03)

The owner requested continued feature work after CP4. CP4 development acceptance
passed at `1b4932b28d2afe9835cccd4f1d35783a713b5b6a`; its native/device gates remain
open. CP5 completes matching-copy review and protected-content choices. Native
trash/deletion, deletion journals, backup and canonical pinned versions follow
in later checkpoints; this checkpoint exposes no destructive command or pretend
storage savings. ADR0005 records the read/protection contracts.

| Card | Builder / allowed files | Complete deliverable and evidence | Dependencies / order |
| --- | --- | --- | --- |
| S13 Review/protection | backend; new Android `cleanup/` source and matching new `cleanup/` JVM tests only | Pure scan-bound exact-content grouping/review contract; current Favorite/NotFavorite/Unknown status, multiple reversible keeper choices, READY/UNKNOWN revisioned content-protection snapshot. Strict bounded durable on-phone content-key store with expected-revision writes, pending-write/directory-sync/readback fencing, no silent repair; fault/reopen/durability tests. Publish contract promptly | New named branch from CP5 card commit; no catalogue/schema/security implementation edits. Integrate before S14 |
| S14 Native cleanup | frontend; new `media/Cleanup*.kt` and focused new media tests; new `ui/Cleanup*.kt`; delegated `StillApp.kt`, minimal `DeviceMedia.kt` snapshot/probe bridge, `StillTheme.kt` Mark enum/new cleanup icon case only, `strings.xml`, Android README/device matrix | User-started sequential exact-readable-byte scanning with progress/cancel/coverage; fresh authorized scope, complete EOF hashing, bounded worker/streaming resources, provenance/namespace/lifetime fences. Group UI with open member, manual keepers and explicit protect/unprotect-all-matching-content through S13. No auto scan, native mutation, bytes persistence or claim of originals/space freed. Preserve all existing gallery/lock/favorites/share/playback behavior | S13 contract first; scan/UI can develop in parallel with storage. Supervisor owns integration; no new dependencies, permissions or build configuration approved |
| S15 Copy audit | seo; read only | Audit scan coverage, readable representation, protection scope/failure, keeper and cancellation wording/accessibility. No duplicate-quality guesses, whole-library claim, guaranteed cancellation/identity/recovery or destructive action | Design and then frozen S13/S14 source; no private media reuse or publication |
| S16 CP5 gates | reviewer and red_team; read only | Separate exact integrated SHA/local APK verdicts, meaningful streaming/source-reuse/cancellation/protection tests and rechecks of findings; independently confirm hosted runs after local gates | Final root full checks and artifact audit; no production release/main merge |

Supervisor owns task/evidence/ADR/root README/PR/CI and dependencies. Builders use
separate existing worktrees with new named branches. Frontend may delegate only
new `ui/CleanupScreen.kt` to `/root/frontend/android_tooling`; parent owns models,
native IO, all shared wiring/tests/strings/docs and commits. Any further ownership
change must be approved before editing. Preserve all72 prior JVM tests and their
assertions, all27 backend tests,18 host-SQL probes and existing lint/CI guards.
S14 also owns matching new `ui/Cleanup*Test.kt` JVM files for actual coordinator
and confirmation-state regressions; no unrelated shared test files are delegated.
Backend may delegate only the new `cleanup/ProtectionDurabilityTest.kt` to
`/root/backend/protection_faults` after publishing its storage interface; parent
retains implementation, other tests, commits and integration responsibility.

Acceptance: exact matches require SHA-256/version + actual complete-read byte
count; type/reported-size prefilter is explicitly incomplete coverage. Reject
partial/failed/changed/canceled hashes and duplicate source aliases. All scanning
is user-initiated, IO-only, sequential with bounded buffers/counts; a shared scan
lane must remain bounded across Cancel→Start, lock and UI recreation while an old
provider read blocks. Cancellation fences output immediately but does not promise
instant provider return. No bytes, previews or hashes are logged or transmitted.

Final source checks cover the entire scan: normal provider-generation advance,
reset, rollback, volume/access changes and current live authority. An early-hashed
file changing before the final file cannot yield a current result. Where Android
lacks generation evidence, final per-member provenance rechecks are best effort;
these are observed readable-byte matches, never pinned immutable originals.
Refresh/revocation/lock/disposal invalidates progress/results/actions. Current
favorites, protection revision and keeper choices must recompute eligibility;
unknown protection blocks all extras and unknown favorite evidence blocks its
group. No extras without an explicit/favorite/protected keeper; preserve all such
keepers. "Extra" means a review choice, never authorization to remove a source.

Protection persists algorithm/version + digest + actual length only, applies to
all matching readable copies and survives missing sources/resets. Different fresh
bytes at a reused URI cannot inherit it; a new URI can match only after hashing.
Unprotect explicitly affects all matching content and requires known current
revision. Failed/uncertain/corrupt/newer-format storage is UNKNOWN, never an empty
set or successful removal. Retain raw records and unmatched protections; no auto
repair, reset, pruning or lost-data migration. Retry may reread a valid store;
uncertain/corrupt records may remain blocked pending later explicit recovery.

Tests must exercise complete/zero/short/error/canceled/close-failure reads, actual
length and overflow, late source change, alias deduplication, blocked-reader
admission, old/new scan and lifetime rejection, fresh-byte protection binding,
current favorites/unknown state, reversible multiple keepers, and store fault/
corruption/reopen/revision/directory ordering. Required final commands remain
`npm run check` and Android assemble/all JVM/strict lint with the established
JDK21/SDK37 toolchain. Native provider transformations, cancellation latency,
AtomicFile/filesystem durability, auth/lifecycle/accessibility and large-library/
video performance stay explicitly unverified until measured on actual devices.

Risk/rollback: this adds only architecture-authorized local content digests and
protection annotations, under existing backup exclusions, with no network or media
mutation. Stop scans and revert CP5 while retaining a copy of app data; uninstall
clears Still-local annotations/settings/catalogue but preserves phone originals.
Finish requires owned source committed, acceptance-to-test mapping, exact root
head/APK hash, both independent passes, hosted CI and updated draft PR/development
APK. Production signing, main merge, spending and publication remain excluded.

## CP6 selected Drift native picture experience (R01 / F01 / F02)

The owner chose A/first DRIFT on 19 October 2026 after reviewing two design renders
and interactive samples. Implement the selected picture experience under ADR0006,
then resume the remaining product sequence in later checkpoints.

| Card | Owner and allowed files | Deliverable and acceptance | Dependencies |
| --- | --- | --- | --- |
| S17 Drift navigation | backend; new `media/DriftPolicy.kt` and matching new `media/DriftPolicyTest.kt` only | Pure current-view token/index/local-date projection with instance/snapshot invalidation, bounded seven-target day rail, honest Unknown dates and unchanged NAME ordering. No IO/persistence/access authority. Meaningful timezone/DST/stale/duplicate/reuse/boundary/17k-row cases; publish interface promptly | Named branch from this card; no existing media/security/catalogue/cleanup edits. Integrate before S18 |
| S18 Drift Android | frontend; new `ui/Drift*.kt` and focused matching new UI tests; delegated UI/wiring in `StillApp.kt`, theme tokens/icons in `StillTheme.kt`, photo-viewer function/layout in `MediaViewer.kt`, scoped layout in `LibraryControls.kt`, `strings.xml`, `res/drawable/ic_still.xml`, Android README/device matrix; only MainActivity imports/enableEdgeToEdge styling call | Ink/coral layered bounded photo ribbon, explicit navigation, real date rail/jump, view-scoped focus and Grid escape, responsive chrome/search/menu, coherent typography/dock and photo opening motion. Keep all existing live-source/permission/lifetime/favorites/share/playback/selection/Cleanup gates and error states. Existing VideoPlayer/PlaybackVideoView/security/lifecycle bodies unchanged. No new IO/dependency/permission/schema; real native checks marked unrun when unavailable | S17 interface first; UI can develop in parallel. Supervisor owns integration. Helper scope is recorded below |
| S19 Copy/design audit | seo; read only | Real current-library text, scoped counts, honest observed dates/Unknown dates, current filter/sort state, labelled Settings and non-gesture controls. No shipped stock/demo labels, invented places/memories/AI/capture/privacy/performance claims | Design then frozen source; no edits/media reuse/publication |
| S20 CP6 gates | reviewer and red_team; read only | Separate design/source preflight and exact integrated source/local-APK verdicts; stale animation/navigation/source/lock/refresh checks, test effectiveness and final hosted confirmations | Root full checks and artifact audit, fixes/rechecks; no release/main merge |

Root owns task board/ADR/acceptance/root README/CI/PR/dependency decisions. Backend
and frontend use their separate worktrees on new named branches from this card.
Frontend may delegate only the new `ui/DriftChrome.kt` to
`/root/frontend/android_tooling` after publishing stateless props. The helper owns
that file only; parent owns strings, theme, all native action closures, wiring,
tests and commits. No helper edits existing shared files or IO/security logic.
Shared file regions above are explicitly delegated; MainActivity authorization is
only system-bar styling, not authentication/secure-window/lifecycle behavior.
Neither independent role implements changes. Preserve all129 existing JVM cases,
27 backend tests,18 host-SQL probes, lint/CI guards and the original architecture.

Acceptance follows ADR0006: at most five thumbnail cards, bounded date rail and
lazy grid, no automatic slideshow, live reduced motion, all essential controls
reachable at small/enlarged layouts, immediate invalidation rather than an exit
animation retaining withdrawn media. Native handlers recheck the exact current
source token and live authority; navigation model validity alone grants no access.
No photos, hashes or user media are logged/transmitted/packaged. All stock sample
assets remain ignored design artifacts; native app displays only authorized media.

Required final evidence: root `npm run check`, Android assemble/all JVM/strict lint
on frozen head containing latest origin/main, signature/manifest/backup/assets audit,
local APK hash, both independent passes, hosted checks and updated draft PR1.
Native gesture/font/animator-scale/TalkBack/frame/memory/security/playback checks
remain open until actually run on identified hardware. Rollback the UI checkpoint
with app-data copy retained; no database migration, original deletion, provider
spending, production signing, publication or main merge is included.

CP6 component status: S17 and S18 are integrated from their frozen owned sources;
S19 copy clearance holds at final builder `c0a11be`. S20 exact integrated
source/APK checks, both independent final verdicts and hosted confirmations remain
required before development handoff. The final immutable candidate, APK hash and
verdicts will be recorded in ignored `.artifacts/cp6-validation.json` and draft PR1;
the next checkpoint promotes that completed outcome into the acceptance history.

## CP7 allow screen capture (owner request)

The owner explicitly requested removing screenshot and screen-recording blocking
so they can show the app. This authorizes the capture-policy change, including
ordinary Recents previews; app-lock authentication and relocking remain required.

| Card | Owner and allowed files | Deliverable and acceptance | Dependencies |
| --- | --- | --- | --- |
| S21 Capture policy | frontend; `MainActivity.kt`, `security/AppLockController.kt`, `res/values/strings.xml` only | Remove `FLAG_SECURE`, Recents screenshot suppression and the capture-only controller callback. Replace the old Settings promise with direct wording that capture is allowed. Keep all authentication/session/storage/lifecycle/player behavior and all155 tests intact. No capture toggle, dependency, permission, schema, IO or signing changes | Named branch from this task-card commit; no helper delegation. Supervisor integrates |
| S22 CP7 gates | reviewer and red_team; read only | Independently inspect the exact source/local APK, removal of capture restrictions and preservation of the lock gate, then confirm hosted checks | Root full checks and artifact audit; no production publication |

Supervisor owns Android README/device matrix, ADR0003, root README, acceptance,
integration/CI/PR and the versioned APK. Existing task-card limits apply outside
the explicitly authorized capture-policy delta. This small platform-policy removal
needs source/API and APK inspection, existing155 JVM cases, strict lint, assemble
and `npm run check`; do not add a test that merely mirrors removed flag calls.
Screenshot/recording/Recents behavior on identified hardware remains unverified
until actually observed. Android may separately protect system authentication UI.

Screen content and Recents previews can now be captured by the phone's tools, as
requested. No screen recording is started, saved or uploaded by Still. Roll back
this source change and reinstall a compatible retained build to restore the prior
capture policy, preserving app data. No media mutation or migration is involved.
Finish requires both independent exact-source/local-APK passes, all hosted checks,
updated draft PR1 and `.artifacts/cp7-validation.json` with a uniquely saved APK.

## CP8 ribbon navigation repair (architecture R01 / section4.2)

The owner reports that swiping left stops the ribbon and that Next also stops;
switching tabs restores it. Continue the architecture with this browsing repair
before the next feature checkpoint. Independent compiled-code/runtime investigation
proves that successive local `::focusedCurrent` references compare equal despite
different captured focus entries. Compose structural-equality updated state keeps
the first gate, rejecting both controls after the first focus change. This is a
reproduced source/runtime mechanism, not a native touch reproduction.

| Card | Owner and allowed files | Deliverable and acceptance | Dependencies |
| --- | --- | --- | --- |
| S23 Ribbon gate publication | frontend; `ui/DriftRibbon.kt`, new `ui/DriftRibbonGate.kt` and matching new `ui/DriftRibbonGateTest.kt`; only the canInteract argument in StillApp.kt if needed | Publish the latest exact permission callback by reference identity, preserving captured-focus/source guards. Shared button/swipe permission remains usable across repeated focus changes despite equal-comparing callbacks. Actual production gate-state regression must fail under the old structural policy and pass fixed; alternate swipe/button forward/back, source/authority changes and focus ABA | New named branch from this card; no helper delegation, dependencies or existing155 test edits. Supervisor integrates |
| S24 CP8 gates | reviewer and red_team; read only | Independently check actual compiled-reference/state defect reproduction, adapter/helper wiring and regression effectiveness; preserve all source/access/lock/capture/player/data boundaries and exact integrated source/local APK, then hosted confirmation | Root full155+new cases/assemble/strict lint/npm, actual APK audit and versioned delivery |

Root owns docs, acceptance, Android README/device matrix, integration/CI/PR and
all dependency decisions. Preserve chosen visuals, finite motion, bounded five-card
composition, focus-keyed pointer cancellation, date/viewer/overlay identities and
current-source actions. Do not refactor the detector for an unproven old-handler
lifetime hypothesis. No media/security/catalogue/cleanup/navigation-policy/build/
permission/schema/IO changes; CP7 capture permission remains.

Preserve all155 baseline tests/assertions. New cases use the real production gate
holder and actual Kotlin local function-reference semantics or the actual compiled
reference class; no invented comparator as sole defect proof. Show old-policy
failure and fixed passes, including current-UI availability AND rejection of old
captured actions. Mere callback invocation without state publication is insufficient.
No attached device/emulator exists; model/source/compiled evidence cannot establish
the owner's exact native gesture behavior. Expand the native matrix with repeated
swipe and Next/Previous sequences without tab switches and interrupt checks.

Risk/rollback: only transient callback publication changes, no saved-state migration,
media mutation, new stored/transmitted data or automatic playback. Restore a
compatible retained build with app data preserved if needed. Finish requires exact
root build/all tests/lint/npm, actual APK audit, both independent local and hosted
passes, draft PR1 and `.artifacts/cp8-validation.json`. No production/main merge.

## CP9 version-aware cleanup planning and journal (Stage1/2 / R02)

The owner asked to continue the architecture. CP8 is complete; the next dependency
is exact source/version review and recovery records under ADR0007, before native
trash/restore or selected backup. Implement the usable internal foundation without
pretending current Android observations pin immutable source versions.

| Card | Owner and allowed files | Deliverable and acceptance | Dependencies |
| --- | --- | --- | --- |
| S25 Cleanup contracts/journal | backend; new `packages/contracts/src/cleanup.ts`, `services/workflows/src/cleanup-plans.ts`, `db/cleanup/001_journal.sql`, `tests/faults/cleanup-journal.test.ts`; existing `services/workflows/src/store.ts` only foreign application-ID guard before writable PRAGMAs | Strict bounded opaque references, trusted default-deny current-evidence seam, reviewed fingerprint/revisions/expiry, constant executionAvailable:false, owner-scoped transactional/idempotent plans/items, durable uncertainty and trusted verified-no-change reconciliation. Dedicated SQLite identity/schema/reopen checks; no destructive calls or success states. Meaningful real SQLite fault/concurrency/reopen/foreign-DB cases | New named branch from this card. Publish interface promptly. No helper delegation, new dependency, existing test edits or API/Android integration |
| S26 Contract tests/client audit | frontend; new `tests/contracts/cleanup-planning.test.ts` only; client audit read only | Meaningful strict parser/contract cases against S25's published interface. Review whether future Cleanup UI can show exact scope, blockers, stale review, uncertainty and recovery without treating confirmation as removal authority. No UI or native mutation edits | Published S25 interface; explicit test-path reassignment from S25, no helper delegation or existing test edits. Supervisor integrates after S25 |
| S27 Copy/claims audit | seo; read only | Direct reviewed wording/codes, no storage-savings/trash/restore/pinning/production claims unsupported by actual capability. Existing planned features retained | Frozen source/docs; no publication |
| S28 CP9 gates | reviewer and red_team; read only | Separate design/source/test-effectiveness and exact integrated source/local-APK verdicts, all baseline assertions preserved, adversarial identity/evidence/TOCTOU/replay/recovery checks, each hosted confirmation | Root checks/audit, fix/recheck; no production publication |

Root owns ADR/task board/acceptance/root and API README, integration/CI/PR and all
dependency decisions. The new module imports contracts directly; no index re-export,
HTTP route, bootstrap, worker/executor, Android source/storage, old schema or paid
provider change is authorized. Existing store change is ONLY the foreign nonzero
application-ID check, closing the reverse database-path hazard introduced by a new
identified journal. Preserve all27 backend and159 Android cases/assertions.

Before confirmation require the current owner/exact ref/source revision, real
pin/fidelity/access, known protection/Favorites/keepers/dependencies and required
copy/restore evidence; unknown/changed/expired facts block. Revalidation must not
silently approve unseen evidence, and uncertainty cannot reset through refresh,
reopen, cancellation, repeated commands or a new confirmation. A required surviving
copy must exclude every selected target and its physical aliases.
Fence unresolved source uncertainty across replacement plans and trusted aliases;
bounded history must reject additions rather than drop unresolved/old evidence.
Reopen rejects foreign schema objects and semantically impossible records before
writable setup. No saved record or
proof identifier alone grants access, execution or recovery. No media bytes/hashes,
filenames, URLs or private media are needed in this development metadata journal.

Required checks: strict TypeScript, all old+new backend suites,11 discovery and18
host-SQL probes; Android assemble/all159/strict lint; exact SQL schema/foreign-path
nonmutation/reopen tests; actual APK/signature/permissions/backup/assets audit;
independent reviewer AND red-team exact local and hosted passes; draft PR1 and
`.artifacts/cp9-validation.json`. APK Android behavior should byte-match CP8 aside
from packaging; do not infer phone/native journal integration. Real native trash/
restore, power loss, capture/motion/accessibility and provider operations remain
unverified/unimplemented as recorded. Roll back with journal/WAL retained; do not
drop/reset records. No main merge, production signing, spending or publication.

S25 and S26 frozen components are integrated from `ccd6745` and `8029930`, with
S25 recovery-copy `4c3d323` and physical-identity `22496a6` follow-ups. All27 prior
backend cases/assertions remain unchanged, with27 new journal cases and20 parser
cases. Component checks and ten defect counterfactuals pass their intended gates.
S27 final copy recheck and S28 exact root/local-APK/hosted gates
remain pending. Record the final immutable candidate in
`.artifacts/cp9-validation.json` and draft PR1 before development handoff.

## CP10 selected-input preparation and native comparison (Stage1/2 / R02)

Continue the architecture from accepted CP9 with ADR0008. Its selected-task staging
option authorizes bounded temporary readable-copy bytes only after Compare on two
current selected photos; no original fidelity, canonical version, backup, removal
or CP9 execution capability is implied. Preparation and visual review are one
end-to-end checkpoint, followed by both independent gates.

| Card | Owner and allowed files | Deliverable and acceptance | Dependencies |
| --- | --- | --- | --- |
| S29 Selected-input preparation | backend; new `inputs/SelectedInputPolicy.kt`, `inputs/SelectedInputStore.kt`, `inputs/SelectedInputPreparation.kt`, `inputs/AndroidSelectedInputs.kt` under Android main package and matching new `inputs/*Test.kt` except S33's file | Exactly2 current photos, bounded32MiB each/64MiB pair/64KiB buffer, complete close/sync/readback, opaque task representation identity with honest fidelity, whole-pair source fencing, shared Cleanup heavy-read admission, session ownership/quota, safe own-cache cleanup/orphans/failure. Production stream/filesystem/source seam and real fault tests; no originals/IO outside own namespace | New named branch from this card. Publish interface promptly. Existing media/catalogue/security/player/backend/schema/dependency files unchanged; no helper delegation |
| S30 Native comparison | frontend; new `ui/Compare*.kt` and matching new `ui/Compare*Test.kt`; existing `StillApp.kt` only comparison initialization, selection action, rendering/back/lifecycle/source/scope invalidation wiring; `res/values/strings.xml` only new comparison strings | Select2 photos→prepare→responsive read-only pair, linked bounded zoom/pan with essential buttons, Reset/Close, direct progress/failure/cleanup states, finite/reduced motion and bounded file-only decode with caches disabled. Immediate invalidation and stale-callback rejection; no saved tickets/paths, removed media lingering, mutation/export actions or auto video | S29 interface first; stateless UI can proceed in parallel. No helper, theme/security/player/media/old test edits. Root integrates S29 before S30 |
| S31 Copy/claims audit | seo; read only | Honest temporary storage/retention/size errors, readable-representation vs originals, selection and current-source scope; no quality/backup/pinning/removal/secure-erasure claims. Existing full roadmap retained | Published interface/design and final source/docs; no edits/publication |
| S32 CP10 gates | reviewer and red_team; read only | Separate design/source/test-effectiveness and exact integrated source/local-APK verdicts, quota/IO/admission/orphan/cache/revocation/whole-pair/stale-action probes, all baseline assertions, each hosted confirmation | Root full checks/audit, fixes/rechecks and new exact head as needed; no production publication |
| S33 Input contract tests | input_policy_tests; new `apps/android/app/src/test/java/app/still/gallery/inputs/SelectedInputContractTest.kt` only | Meaningful production selection/count/ownership/immutable task contract cases against S29's published policy, including stale/foreign identities and current first/replaced authority. No implementation or other test edits | Explicit test-path reassignment from S29; separate root-owned builder branch. May copy WIP sources only as uncommitted compile scaffolding; supervisor integrates after S29. No helpers or self-approval |

Root owns ADR/task board/acceptance/root and Android README/device matrix, all
shared build/dependency/CI/PR decisions and integration. All74
backend and159 Android cases/assertions must remain intact. No old schema, Android
permission, MainActivity/lock/capture policy, MediaViewer/player, cleanup source
semantics, API/bootstrap/provider, catalogue or CP9 contract/journal changes.

Risks, staging/retention bounds and rollback follow ADR0008. Source metadata and
readable snapshots are not pinned originals; never silently broaden selected scope
or resume across recreation. A closing/blocked worker retains actual admission;
failed cleanup blocks new staging. Root npm check, full159+new JVM/assemble/strict
lint, actual APK/signature/permissions/backup/8SQL audit, both independent local
and hosted passes, draft PR1 and `.artifacts/cp10-validation.json` finish the
development checkpoint. Native storage/MediaStore/power-loss/UI hardware behavior
stays unverified until observed. No paid calls, main merge, release signing or
publication.

CP10 dependency exception: the supervisor approves only pinned
`androidx.exifinterface:exifinterface:1.4.2` in the root-owned Android app build
file. Strict lint rejected the platform parser for known older-Android security
issues; preserve the gate and replace its import with AndroidX. No builder edits
dependency files or adds another library. Google Maven metadata identifies1.4.2
as the current release; no lockfile is hand-written.

S29 is integrated from `58c58026` + `a7b7e49`; S33 from `bf8e2ec`; S30's own
series is `731d636` → `5f8f784` → `2947c03`. Builder implementation readiness,
source preflight and S31 copy clearance are recorded in acceptance; neither is
checkpoint approval. Root now freezes the combined225-case candidate for full
checks, actual APK audit and S32's separate local and hosted gates. The final
immutable source/artifact outcome is `.artifacts/cp10-validation.json` and PR1.

## CP11 selected-photo appearance candidates (Stage2 / R02)

Continue accepted CP10 under ADR0009. User selects2–200 current visible photos in
Grid and taps Find similar; all selected photos are considered across dates,
without silent truncation. Show review-only direct appearance pairs and an explicit
Compare action that independently prepares the chosen pair with CP10. Durable
full-library incremental similarity indexing remains planned; no permanent
narrowing of R02 or other roadmap features. No deletion/protection/keeper authority,
new cloud connection, dependency, persisted features or file staging in analysis.

| Card | Owner and allowed files | Deliverable and acceptance | Dependencies |
| --- | --- | --- | --- |
| S34 Visual analysis | backend; new `similarity/SimilarityPolicy.kt`, `SimilarityReadOwnership.kt`, `SimilarityScanner.kt`, `AndroidSimilarityFeatures.kt` under Android main package and matching new `similarity/*Test.kt` except S36 | Versioned source-derived DCT pHash64 plus aspect/color/spatial checks, low-information rejection, exact-pair omission; immutable direct pairs/coverage, opaque ticket/result/pair identities. Selected2–200,16MiB/input,256MiB/run,64KiB chunks,32MiB explicit encoded buffers,edge256/1Mpixel/4MiB single feature bitmap,200 descriptors/19,900 pair checks/50 results with omitted count. Fresh metadata cap50k, per-read and whole-selection final fences, alias rejection, complete EOF/close before extraction. Shared nonqueueing Cleanup admission, process-retained failed closes and retry across recreation. Real stream/fault/source/production-pixel tests, no transitive clustering or similarity confidence claims | New named branch from this card; publish compile-ready API promptly. No helper, old source/test/dependency/schema/security/provider edits. Supervisor integrates first |
| S35 Similarity review UI | frontend; new `ui/Similarity*.kt` and matching new tests; existing `StillApp.kt` only selection action/start/render/lifecycle/source/scope/Compare integration; `strings.xml` only new strings | Grid selected batch→Find similar→read-only lazy direct pair cards, actual names/dates and coverage, Stop/Close/error/partial/failed-close Retry states. Responsive accessible essential buttons, existing palette/capture behavior. Exact result/pair-choice origin helper resolves current authorized pair and starts existing CP10 Compare freshly; no fake gallery selection, saved capabilities, old-action retarget, analysis bitmap cache, exact-policy/keeper/protection/mutation actions. Fence selection ABA, source withdrawal, before-start cancellation and actual worker settlement. Meaningful production coordinator/binding regressions | S34 API first; stateless UI in parallel. Existing Compare/input/media/security/player/cleanup bodies unchanged. No helpers/dependencies/old test edits. Root integrates after S34 |
| S36 Independent pixel evaluations | input_policy_tests; new `apps/android/app/src/test/java/app/still/gallery/similarity/SimilarityEvaluationTest.kt` only | Independently generated labelled synthetic scene/document/state/color/pattern/tiny/flat/alpha/EXIF1–8 fixtures against production pixel helper; actual host ImageIO re-encoding where useful. Separate calibration vs evaluation evidence, held-out false positives/misses and limits. Direct A–B/B–C/notA–C, cross-year metadata and no authority escalation; bounded count/result overflow. Preserve existing assertions. No implementation or other test edits | Explicit one-file test reassignment from S34; named branch, copy dependency WIP only as uncommitted scaffold. No helper/self-approval; report API/evaluation defects to root |
| S37 Copy/claims audit | seo; read only | Honest selection2–200, resource/metadata limits and actual coverage, uncertain appearance/detail differences and exact-vs-visual separation; no quality winner/duplicate certainty/original/backup/removal/memory/latency guarantee. Plain R12 copy; full roadmap retained | Design then frozen integrated source/docs; no edits/publication |
| S38 CP11 independent gates | reviewer and red_team; read only | Separate design/source/test-effectiveness and exact root/local-APK verdicts, real scope/alias/close/budget/low-information/direct-pair/source-withdrawal/stale Compare probes, preserved baselines and each hosted confirmation | Root final full checks/audit and fix/recheck. No production publication |

Supervisor owns ADR/task board/acceptance/root and Android README/device matrix,
shared build/dependency/CI/PR decisions and integration. Preserve every225 Android
and74 backend case/assertion, all CP10 inputs/Compare semantics and previous ribbon,
lock/capture, viewer/playback, exact Cleanup, catalogue and CP9 contracts/journal.
The new module may import existing media/read admission interfaces but not change
them. No dependency, Android permission, schema, persisted/transmitted data category,
source mutation, file staging or production configuration change is approved.

DCT pHash is the root-selected initial implementation for versioned candidate
retrieval, with thresholds chosen using declared calibration fixtures and independent
held-out evaluation; no general accuracy claim. Low-detail/documents/state changes
can still collide and always remain manual appearance review. Capture time is not
a filter. Known animation/motion cases are omitted honestly when unsupported.

Risks/rollback/retention and command environment follow ADR0009. Root must run
`npm run check`, Android assemble/all225+new JVM/strict lint and actual signature/
permissions/manifest/backup/8SQL/DEX audit. Both independent exact local AND hosted
passes, all four exact-head CI checks, unique CP11 APK, ignored validation record
and updated draft PR1 finish development acceptance. Hardware provider/codec/EXIF/
gesture/font/TalkBack/memory/thermal/large-library/process/power-loss checks remain
Blocked until observed. Keep genuine failures and effective old-policy regression
proofs. No main merge, release signing, paid provider or publication.

CP11 final development outcome: PASS at exact source
`20dd35358302363ad2efccad5c3f636e73acf845` and local APK SHA-256
`2b8b5decd6fe94482c3ad98dc56046f24bd718f76d79a0f546e67b4998eadf69`. Both
independent local reviewer/red-team gates, both hosted role confirmations, and all
four hosted checks passed. See the final outcome and retained ignored record in
`docs/delivery/acceptance.md` and `.artifacts/cp11-validation.json`.

## CP12 persistent incremental similarity index (Stage2 / R02 / architecture 8.2)

Continue from the accepted CP11 selected-photo review toward F11's accessible-
library scope. Add an explicitly user-started local index of versioned visual
descriptors for current authorized static photos. Preserve the CP11 selected
workflow and candidate-only language. The index is rebuildable app-local analysis
data, never a source/version hash, original, keeper, or deletion decision. It is
excluded from Android backup, never uploaded, and has clear stop, access-change
purge and user clear controls. Opening the app, granting media access, or running
selected-photo Find similar does not start a whole-library analysis.

| Card | Owner and allowed files | Deliverable and acceptance | Dependencies |
| --- | --- | --- | --- |
| S39 Index contract and local store | backend; new files under Android `similarity/` and new fixed SQL asset `similarity/001_schema.sql`; matching new `similarity/*Test.kt` | Strict, versioned compact descriptor representation and separate app-local SQLite store; source key is opaque and no URI/name/path/bytes/content digest are persisted. Rows include namespace incarnation, MediaStore generation-added birth (or API26–28 date-added fallback), generation-modified revision (or best-effort legacy metadata revision), and algorithm version. Provider-version change or detected generation rollback rotates the namespace incarnation; older APIs retain best-effort continuity. Stale generations/pipeline versions never reuse features. Reopen, malformed schema/row, update/prune, bounded size and clear behavior. Unknown application IDs/objects and exact-DDL mismatch fail closed. No dependency or shared catalogue/cleanup/schema edits | New named task branch; ADR0010 freeze first; supervisor integrates |
| S40 Full-scope indexing work | backend; new `similarity/SimilarityLibraryIndexWork.kt`, matching focused tests; existing `SimilarityScanner.kt` only a new bounded analyzed-source hook after complete read/close | Explicit start on current full authorized photo snapshot; bounded 200-photo pages, ≤16MiB/input and ≤256MiB per tap; static JPEG/PNG only; commit a page only after complete source/scope fences. Resume only on a later user action after process interruption. No WorkManager/service/background scan, automatic resume or image/file cache. User cancel stops admission; failed-close lane retained. Only successful descriptor rows are retained; unsupported, unreadable, low-information and budget-omitted outcomes are retried on a later explicit run, not persisted. Counts separate indexed/current/skipped/unattempted | S39 interface first; use same `cleanupProcessReads`; scope≤50,000 current rows; no old test edits |
| S41 Query and UI | frontend; new `ui/SimilarityIndex*.kt` and matching tests; `StillApp.kt` and `SimilarityScreen.kt` only explicitly assigned controls/state/wiring; `strings.xml` new plain strings | Settings explains local derived descriptors and supported formats; explicit “Index photos”, Stop, Continue and Clear actions. Search selected photos against indexed current library via bounded direct candidates; exact source/choice fences and fresh CP10 Compare. Partial coverage is visible. Never index on selection or app open. No AI wording, progress certainty or deletion/protection action | S39/S40 APIs; preserve existing accessibility, lock, lifecycle, selected comparison, all source fences |
| S42 Independent evaluation and copy | input_policy_tests owns new independent feature-index retrieval/evaluation test only; seo read-only | Freeze new unseen v3 corpus before evaluation; measure candidate retrieval recall, false positives, collision cases, persistence/source invalidation and omitted-work bounds. Plain copy accurately states on-device storage, user start/stop/clear, coverage and limits. No broad accuracy claim | Exact S39/S40 API and frozen UI copy; no implementation or threshold edits by evaluators |
| S43 CP12 independent gates | reviewer and red_team; read only | Separate design, source, persistent-data/privacy, migration/fault, retrieval/test-effectiveness and exact integrated source/local-APK reviews; hosted confirmation by each role. Recheck every material fix | Root full checks, actual APK/backup/database audit, both roles and all required hosted checks; no production publication |

Root owns ADR/task board/acceptance/CI/PR/build/manifest and integration. Preserve
all295 Android and74 backend cases, the complete existing catalogue, protection,
Cleanup journal, Compare, player, lock, ribbon and capture behavior. The new index
is a new local derived-data category authorized by architecture 7/8; no media bytes,
source path/name, content SHA, cloud transfer, backup inclusion, inference model,
new dependency or destructive capability is part of CP12. Index results are
appearance candidates only and can include meaningful differences. Persisted
coverage never implies analysis of inaccessible or omitted photos. Rollback keeps
library media untouched and removes only rebuildable feature records through the
Clear action or a versioned local-store reset; do not alter catalogue or CP9 journal.

Finish requires exact-source reviewer and separate red-team passes, root TypeScript/
backend/SQL/Android full checks, strict lint, local APK signature/manifest/backup/
DB/DEX audit, exact hosted checks and updated draft PR1. Physical-device codec,
provider generations, large-library runtime, process death, battery/thermal, storage,
access revocation and accessibility behavior remain unverified unless actually
observed. No main merge, production signing, provider account or publication.

S42 froze `tabletop-retrieval-v3` before output: seed `0xC012730B5`, generator
SHA-256 `abed66abe7e03d80b64e7894fbe5a5c49fc932e35606f12834a71427f334fef9`,
22 labelled pairs (14 same-appearance, 8 meaningfully different). The independent
test-only commit is `2db2f2ae6850a253330d59b16ff183f140f2ced6`; its nine tests pass
against the fixed retrieval source. The retained pre-fix run records both actual
regressions: searched-photo count equalled pair checks, and access loss during an
empty candidate lookup was not fenced. The fixed v3 evaluation retrieved all15
matcher-eligible pairs, reported three different-content candidates (receipt
digit/page and screen state), and missed crop and unsupported-alpha cases. It makes
no broad accuracy claim. Corpus, labels, old-policy proof and fixed run are retained
under `/tmp/still-cp12-s42-evidence/`.

The exact local candidate `99e1e119da2921d66e5d74fd1918f12c7c3f30d0` passes the
full Android build,327 JVM cases, strict lint, instrumentation-source compilation
and instrumentation APK packaging; `npm run check` passes TypeScript,74 backend
tests,11 discovery checks,18 catalogue probes and7 similarity-index SQL probes.
The first hosted attempt on `2f9819ce1f223ef9e6a7a6d6bc585e9bdd6b5278` failed
Android strict lint because the current rule requires Gradle9.8.2; the verified
9.8.2 wrapper correction kept lint enabled. Both corrected hosted workflows
passed on exact head `3dfae315289f629d64ccb6a4b9d6c2b682c6ef39`: push run
`39429711727` and PR run `39429718367`, with Android and contracts/discovery
passing in both. Reviewer and red team passed the exact local source/APK and
separately confirmed the exact hosted head. CP12 development checkpoint outcome:
PASS. The APK and test APK are compiled and packaged, but Android SQLite
instrumentation was not executed because no device/emulator is attached; hardware
runtime checks remain open. Exact commands, logs, review evidence and artifact
hashes are recorded in `docs/delivery/acceptance.md` and
`.artifacts/cp12-validation.json`. The branch remains a draft; no main merge,
production signing or publication is authorized.


## CP13 provider-neutral backup and restore proof (Stage 2 / architecture §§10, 19.1)

Continue in architecture order after CP12. Selected backup, verified restore and
the find→review→backup→remove-local→restore journey are Stage 2 prerequisites to
Stage 3 screenshot/search work. Existing backup policy/job contracts remain
unconfigured; CP10 temporary readable copies and CP12 appearance descriptors do
not prove original bytes are recoverable. ADR0011 freezes this checkpoint as an
isolated contract and recovery-journal proof using synthetic bytes only.

| Card | Owner and allowed files | Deliverable and acceptance | Dependencies |
| --- | --- | --- | --- |
| S44 Backup identity and transfer contract | backend; new `packages/contracts/src/backup-recovery.ts`, `services/backup/`, `db/backup/001_recovery.sql`, and `tests/backup/` only | Trusted immutable-input/fidelity verifier defaults to denial; typed opaque source/version/component references; portable versioned manifest binds representation, component roles, byte lengths and SHA-256. New separate revisioned/idempotent journal distinguishes uploaded-unverified, verified, restore-verified and uncertain outcomes; current object identity/revision and reconciliation required. Test adapter accepts synthetic bytes only. No HTTP/native route, provider, account, network, real media, or production bootstrap | CP12; ADR0011 first; no edits to existing control-plane/catalogue/cleanup schema or production provider seams |
| S45 Recovery fault evidence | backend; matching new backup tests and one new `scripts/test-backup-recovery.mjs` only | Prove exact synthetic multi-component restore when original source is absent; reopen/resume and duplicate intent; ambiguous outcome blocks blind retry; stale revision, changed source/components, wrong length/hash, missing component, collision, disk-full, close failure and concurrent revisions fail closed. Test receipts explicitly test-only; preserve existing assertions | S44 contract; temp databases/fixtures only; no user files/media |
| S46 Interface and copy review | frontend and seo; read-only | Define later selected-photo review, destination/status, cancellation/retry, verification, restore/conflict and accessibility states; review truthful plain labels and existing discovery claims. No UI controls or implementation promise while provider remains unconfigured; no AI wording, provider, quota, free-storage, verified-backup or space-savings claims | S44 contract shape and ADR0011; no publication or edits |
| S47 CP13 independent gates | reviewer and red_team; read only | Exact source/test review, adversarial identity/fidelity/manifest/journal/reopen/retry/corruption/concurrency probes, ensure test-only adapter cannot produce production authority, and both role verdicts on hosted checks | Root integration and full checks; provider/account/device action remains excluded |

Root owns task board, ADR, package check wiring, integration and acceptance. Backend
works on a named branch from the exact CP12 accepted development head and returns
an isolated new-file diff; root owns any shared entry-point or scripts changes.
No schema migration may touch existing stores. No new runtime dependency is allowed.
No selected provider, OAuth app, credential, account, payment, cloud transfer or
personal-photo test is included. The synthetic contract does not complete Stage 2
backup acceptance and does not enable deletion or “Free up space.”

The next user-facing backup action waits for a provider decision and authorization
of its account/scopes/credentials; a local export probe, if later chosen, must be
labelled as a local copy. Screenshot OCR stays a later Stage 3 design item with
its own engine/language, source scope, sensitive-text retention, exclusions and
correction decisions. All CP13 code changes require the repository checks, exact
reviewer and red-team passes, and an open development-only PR; no main merge,
production release or publication is authorized.

CP13 code is integrated at `cb166a987163aa56dd1a479282d6bc159528dfeb`. The seven
new files remain isolated generated-byte proof code; no provider, user media,
production bootstrap, Android UI, route or dependency was added. Root wired
`tests/backup/` into `npm test` and added the focused `npm run test:backup` runner.
S46's read-only frontend/SEO review outlined later backup/recovery and access states;
it confirmed filename-only search and no current screenshot extraction or provider
promise. No UI or discovery copy changed.
The full local `npm run check` passes all 120 backend cases, 11 discovery checks,
18 catalogue SQL probes and 7 similarity-index SQL probes; the focused backup
runner passes 46/46. Reviewer and red team independently passed the exact component commit.
Final integrated-head and hosted results are tracked on the draft PR and in its
ignored validation artifact before any later release step.

## CP14 — Connect a chosen Google Drive account (Stage 2 / architecture §10)

The owner has chosen the architecture's recommended first destination, Google
Drive, and specified that Still must let them choose and sign into the Google
account they want to use. CP14 is only the account-connection step. It does not
start a backup or transfer media. ADR0012 selects the narrow `drive.file` scope,
a visible Drive folder for later Still-created files, explicit account switching
and disconnect, and a connection epoch so later jobs cannot change accounts.

| Card | Owner and allowed files | Deliverable and acceptance | Dependencies |
| --- | --- | --- | --- |
| S48 Account and OAuth design | root; `docs/decisions/0012-google-drive-account-connection.md`, this section | Select Google Drive; use a supported account chooser and Drive authorization flow; request only `drive.file`; distinguish Drive files from Google Photos; define account switch/disconnect, token handling, OAuth project setup and no-transfer boundary | CP13 synthetic proof; owner specified Drive account choice |
| S49 Native account connection | frontend; new Android `backup/drive/` source and matching tests, narrow `StillApp.kt` Settings wiring, new strings, required manifest/build configuration, Android README OAuth setup | Explicitly choose/sign in to a Google account, confirm the selected account, switch account and locally disconnect. Tokens are transient. Clearly say local disconnect does not revoke Google's account-level grant. Do not auto-backup, read user media, upload/download, create files, schedule work or delete anything. Missing OAuth configuration is clearly unavailable, never a fake connection | S48; official Android APIs and currently valid dependency verified; no user credentials or cloud transfer |
| S50 Account binding checks | backend; focused pure contract/tests only if S49 needs a shared account epoch contract | Account change fences the old connection; any future job is bound to its creation account/epoch; late or cancelled authorization cannot overwrite newer state; email is display-only, not the stable account key | S49 interface first; no Drive API or provider writes |
| S51 Copy review | seo; read only | Plain labels for choosing/signing into an account, scope, selected account, switch/disconnect and account-vs-backup distinction. State that connecting does not start backup and Drive files are not Google Photos items. No storage, verification, privacy or upload promise | Frozen source and strings; no publication |
| S52 CP14 independent gates | reviewer and red_team; read only | Review exact source, OAuth scope and account-binding flow, token persistence/logging, cancellation/revocation, configuration gate, lock/lifecycle return, tests and hosted checks. No real account or media required for automated gates | Root full checks and exact-head hosted runs; physical-device OAuth check only after OAuth configuration is supplied |

OAuth cannot complete until the owner configures a Google Cloud project, registers
the app package and signing SHA-1, enables the Drive API and adds the required
client ID through local build configuration. No OAuth secret is to be committed.
Until then, connection must show that this build needs setup. Real backup/restore,
transfer recovery, deletion and free-space actions remain later, separately gated
work.
This proof does not authorize source deletion or claim original fidelity, provider
recovery, cross-process recovery, phone durability or new-device restore.

CP14 implementation is integrated at `565a68dc17759ee66c274001c81bea03bcfc7402`
on the existing draft branch/PR1; it is not merged. Local `assembleDebug`, all 341
Android JVM tests, and strict lint pass. Push run `39453758006` and draft PR run
`39453764889` pass both Android and contracts/discovery jobs. The independent
reviewer and red team passed this exact commit and local APK; see
`docs/delivery/acceptance.md` for findings, rechecks, artifact hash and evidence.
OAuth client setup and physical-device Google chooser/consent testing remain open;
the default build reports setup required. Disconnect is local-only and states that
the Google account-level grant remains until the owner removes it in Google Account
settings. No media is uploaded, restored or removed.

## CP15 — Account-bound asynchronous transfer recovery proof (Stage 2 / architecture §10)

CP14 establishes the Google account-selection and narrow authorization flow, but
does not transfer files. CP13's synchronous synthetic journal does not model
provider work that remains in flight after cancellation, account switch, or
process death. ADR0013 freezes the next dependency as a provider-neutral protocol
and durable-ledger proof using synthetic identities only. This prepares the Drive
adapter without claiming that backup is operational.

| Card | Owner and allowed files | Deliverable and acceptance | Dependencies |
| --- | --- | --- | --- |
| S53 Async transfer identity and protocol | backend; new `packages/contracts/src/backup-transfer-recovery.ts`, `services/backup-transfer/`, `db/backup/002_transfer_attempts.sql`, and `tests/backup-transfer/` only | Persist attempt ID/revision, opaque connection epoch, immutable source/version/component-set revisions, destination and operation identity. Monotonic provider acknowledgements require matching attempt/session/offset. Late work, account switches, cancellation and process restart cannot retarget an attempt. Ambiguous create/append/finalize outcomes require reconciliation before retry; unknown status remains uncertain. Email, media bytes, paths/URIs, OAuth tokens and resumable URLs are forbidden. Synthetic-only, no production capability | CP13 and CP14; ADR0013; preserve existing backup journal/schema and production seams |
| S54 Async fault evidence and focused runner | backend; matching new tests and one new `scripts/test-backup-transfer-recovery.mjs` only | Prove late completion after cancellation/restart, epoch switch/disconnect, idempotent replay, ambiguous create/finalize, unknown-offset reconciliation, wrong session/offset, skipped/backwards acknowledgement, lease expiry with live request, changed source/component revisions and concurrent stale writes all fail closed or reconcile without duplicate object creation | S53; temporary/generated fixtures only; no user files/media |
| S55 Copy and boundary audit | frontend and seo; read only | Identify later truthful selected-photo/album, destination, progress, uncertainty, cancellation and recovery states. No UI/API/provider implementation or claim of backup/restore/verification/quota. No AI wording | Frozen S53 contract shape; no publication or edits |
| S56 CP15 independent gates | reviewer and red_team; read only | Exact source/test review; adversarial account epoch, in-flight late completion, ambiguous provider response, lease/restart, idempotency, offset/revision and persistent-data probes; ensure test-only code cannot authorize production work; both roles confirm exact hosted checks | Root integration/full checks; no real account, provider, network, personal media or device transfer |

Root owns this card, ADR0013, package/check wiring, integration, acceptance and
PR. The S53 builder returns an isolated new-file diff from the accepted CP14 code
head; root alone edits shared scripts/manifests. No runtime dependencies, existing
database changes, Android UI, provider adapter, OAuth change, WorkManager job,
account API call, real media read, network, upload/download, restore, automatic
policy, deletion or free-space action. The connection epoch is a fence, not an
account identifier; reauthorization gets a fresh epoch and old work stays bound
to its original one. CP15 does not complete selected backup or Stage 2.

The recovery ledger permits only one attempt per owner and exact immutable source,
version, source revision, component-set revision, component ID and component
revision—even across destination IDs or connection epochs—because account continuity
is not proven after reauthorization. A confirmed `not_applied` result resumes the
original attempt/operation. New explicit duplicate-copy intent is outside CP15.

Finish requires the repository full check, Android build/tests/strict lint on the
integrated head, a local audit that no new production capability or sensitive
fields are exposed, exact reviewer and red-team passes, and hosted checks on the
same head. Record exact source/test identities and any remaining blocked device,
OAuth and provider checks. No main merge, production signing or publication.

S55 confirms later transfer UX must separate provider-acknowledged progress,
uploaded-but-unverified, verification, and uncertain outcome. “Check status” must
reconcile the original attempt; “Retry” cannot create another object while prior
completion is ambiguous. Reauthorization creates a new epoch even for the same
account, so old attempts cannot resume by matching email. Before an Android Drive
adapter, separately resolve stable original/component authority, visible folder
creation under `drive.file`, selected-album snapshot semantics, cancellation of
in-flight requests, quota reporting and foreground/background execution. These
are later provider-gate questions; CP15 must not turn them into current UI or
capability claims.
Within the synthetic model, `cancelled` and `fenced` describe local transfer
intent; a later remote observation may still report that an already-issued
request finalized. Keep that historical outcome separate from active authority.
`acknowledgedOffset` advances only for the current active intent and is not a
universal total of provider bytes after cancellation/fencing. All modelled objects,
offsets and outcomes remain synthetic identities, not Drive evidence. Model
`ready` means only that a next protocol step may be issued; it does not mean
verified, restorable or protected.

CP15 source was independently accepted by reviewer and red team on exact source
`60952bfc78e5b7e8d61780bd6163583b136f9d31` after three P2 findings were fixed:
cross-attempt duplicate admission, impossible persisted dispatch/fence/reconcile
history, and mutable attested response objects. Root integrated the source and
wired the focused suite into `npm test`. The integrated local repository and
Android checks pass (173 Node tests, 53 focused transfer cases, 11 discovery
checks, 18 catalogue SQL probes, 7 similarity SQL probes, 341 Android JVM tests,
strict lint and debug APK build). Reviewer and red-team also passed integrated
local head `c9f0d79b3b27a72228a5a79de759d2ae471aa7ff`; hosted run `39462791526`
was pending at last check. Provider/OAuth/device transfers remain excluded.
