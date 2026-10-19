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
- Current Android dependency allowance (ADR0002): AGP 9.4.1, Gradle 9.6.0,
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
Store exact final SHA, commands, environment, results and debug APK SHA-256 in
docs/delivery/acceptance.md. Native device checks must be marked blocked if no
device is attached. Implemented, independently reviewed, candidate accepted and
published are separate statuses. No publication is part of this task.

## Current checkpoint state

CP0 scaffold/workflow: accepted with independent reviewer and red-team passes.
S01 backend and S03 discovery components: both roles passed exact source commits;
the original S03 reflow failure and regression fix remain in acceptance evidence.
S02 Android: initial APK compiled and 7 unit tests passed. Strict lint prompted
source fixes and a researched current dependency/toolchain update; final checks
are in progress.
Combined CP1 and final CP2 are pending. Nothing is published.

## Remaining product sequence

Retain all F01–F45 and required R01–R18 in architecture.md as the canonical scope.
After this milestone: app lock/local catalogue resilience; cleanup with protected
keepers/deletion journal; selected backup, verification and restore; screenshot
intelligence and meaningful memories; try-on with bounded review; PDF/DOCX/text;
AI-directed video plans/rendering; approved extensions and release hardening.
Provider/budget/account/vault/share decisions remain owner decisions before those
services are enabled. Proposed features remain proposals; no silent approval.
