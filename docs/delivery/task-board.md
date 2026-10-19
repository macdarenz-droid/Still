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
