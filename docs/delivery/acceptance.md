# Checkpoint and acceptance evidence

This is development work, not a production release. No store or website publication
is authorized or performed. Every checkpoint needs separate reviewer and red-team
passes. Material changes reopen the affected checks. Source review does not imply
real-device, provider, backup or generated-output acceptance.

## CP0 — Bootstrap

Initial reviewed SHA: `6c3f22c53edfe41cd1692ded6d4caa939ef4d33c`.
Base: `b31a7ab`. Environment: managed Linux, Node 24.19.0, npm 11.9.0, Java 21.

- Independent reviewer: **changes requested**. Missing explicit every-checkpoint
  red-team gate; npm test incorrectly succeeds with zero files; command results
  need explicit pending/failure statuses.
- Independent red team: **conditional scaffolding pass only**. Same zero-test gap;
  no runtime API or device exists at this SHA to attack.
- Supervisor fixes: both roles now mandatory in AGENTS.md and task board;
  scripts/test-backend.mjs rejects missing or empty required suites; this evidence
  record distinguishes bootstrap checks from pending runtime checks.
- Re-review: **independent reviewer PASS** and **red-team PASS** at exact SHA
  `580ce7b60947f6f41ef0b4a4275c7fbec85afc5e`, scaffold/workflow scope only.
  Architecture byte equality was independently verified. The red team exercised
  copied guard code against missing/empty required suites, two passing suites and
  a failing suite; exit codes were correct in every case. No tracked files changed.
- CP0 accepted for scaffold/workflow only. App/API/site checks remain CP1 gates.

| Check on initial SHA | Result | Evidence / implication |
| --- | --- | --- |
| npm install --ignore-scripts | Passed | Lockfile generated, 3 development packages installed |
| npm test | Invalid success | Node exited 0 with zero tests; not a test pass |
| npm run typecheck | Failed / lane pending | TS18003, no backend sources yet |
| npm run check:site | Failed / lane pending | Site checker/page not present yet |
| npm run build:android | Failed / lane pending | Android project not present yet |
| npm run test:android | Failed / lane pending | Android project not present yet |
| npm test after guard fix | Expected failure | Required test suite missing: tests/contracts; exits 1 |
| Architecture retention | Passed | Root architecture.md is a byte-identical copy of supplied attachment |

Those initial failures remain visible. CP0 accepts the documented scaffold;
runtime implementation acceptance belongs to CP1 and final CP2.

## CP1 — Integrated foundation

Component evidence before combined acceptance:

| Component | Exact reviewed SHA | Independent reviewer | Red team | Result / limits |
| --- | --- | --- | --- | --- |
| S01 control plane | c118431e11e6303cd5026ae59ca61805ec7ecf65 | Pass | Pass | 27 tests + typecheck; development-only, no providers |
| S03 discovery (initial) | 3eb647ee4d507701a08e7c5ceed3ae5ffc1a9ed1 | Changes requested | Source pass | 200% text at 320 overflowed; not accepted |
| S03 discovery (fixed) | cc2d689c6e76b139ca8dd79e9b93f4ebbef212d5 | Pass | Pass | Full-element200% at 320/390 now fits; no publication |
| S02 native gallery (superseded) | 0699dec77a39907a8c8a8d5568b0a59a99f99d10 | Pass, then reopened | Pass, then reopened | Same-URI edited preview issue found before integration |
| S02 native gallery (fixed) | 29fae0e61fe9531c4d1c0c9c669fcfe500532204 | Pass | Pass | APK build, 8 JVM tests, strict lint; device checks blocked |

S01 preflight fixes were reproduced and independently rechecked: automatic backup
requires explicit collection/media/network eligibility at admission, dispatch and
transmission; expired attempts can reconcile actual costs after reopening the
SQLite file with owner/revision/attempt fences and idempotent receipts; internal
worker leases include their owner. Adversarial receipts preserve actual overspend
rather than inventing a cheaper result. Real provider proofs and billing remain
unimplemented. Default providers and input validation deny admission.

S03 regression evidence: full-element 200% at 320 initially had client 305/scroll 340;
fixed CSS gives 305/305 and 390 gives 375/375. Independent roles verified the served
assets match the reviewed SHA, no external resources/forms/scripts, correct
anchors, keyboard skip-link focus, reduced motion and honest future-feature copy.
The portable manual reproduction procedure is in docs/discovery/launch-readiness.md.
Screenshots/probe evidence for this session are /tmp/still-site-browser-evidence.json
and /tmp/still-site-text200-{320,390}.png. Screen-reader testing is not implied.

Integrated `npm run check` passed after S01 integration (27 tests, no skips) and
`npm run check:site` passed after the S03 fix integration. All three components now have separate independent reviewer and red-team passes.
Combined CP1: **independent reviewer PASS** and **red-team PASS** at exact
`1f35568d9cb42204b573b26d47636ccbfec16aed`, development scope only.
Root install, typecheck, 27 backend tests, discovery validation, Android assemble,
8 JVM tests (zero failures/errors/skips) and strict lint all passed. Both roles
verified component source trees were unchanged from their reviewed heads and
independently hashed/audited the root APK.

Integrated APK SHA-256:
`69a9e318a8d5300a3af9affd9370d09976f69713982337ac8f44d5753bdb17fb`.

Session evidence: `/tmp/still-cp1-check.log`, `/tmp/still-cp1-android.log`, native
JUnit/lint reports under `apps/android/app/build`, and ignored local exact-SHA
record `.artifacts/cp1-validation.json`.

Full baseline whitespace probe `git diff --check b31a7ab..1f35568` reports one
nonblocking inherited formatting warning: architecture.md:1226 has the supplied
final blank line. This command is **not a pass**. The document remains byte-identical
to the original attachment; no exclusion, stripping or guard change hid the warning.
The working-tree diff is clean. Compiler/API26 and plugin/Gradle10 deprecation
warnings also remain visible; strict Android lint itself reports no issues.

### Android lint and toolchain update

Initial native assemble and all 7 JVM tests passed with zero skips. Strict lint
failed with 13 issues: 8 source issues (API 27 theme separation, plurals, Android 12
backup extraction rules and AndroidX helpers) plus 5 new-version advisories for
AndroidX core/activity/lifecycle and Compose BOM. Source issues were fixed.
The supervisor researched official current metadata instead of suppressing the
warnings: core 1.19.1 requires compile SDK 37/AGP >= 9.1.0; activity 1.13.0 requires
compile SDK 36/AGP >= 8.9.1. AGP 9.4 supports SDK 37 and requires Gradle 9.6.0. Updated
versions and rationale are recorded in ADR0002. The upgraded stack compiled and
passed all 7 tests; its newer lint found 5 configuration-aware resource reads and a
Gradle current-version notice. Those source calls were fixed and the wrapper
updated to approved 9.8.1 with official checksum. Final component assemble,
all 8 JVM tests and strict lint pass; lint reports "No issues found." Old failures
remain visible here. All lint guards and prior unit assertions were preserved.

A same-URI edited-photo preview race reopened S02 after its first source pass,
before integration. A shared preview fingerprint now drives both memory cache and
Compose state/producer identity, using modification/orientation/dimensions/bytes
and requested size. The new regression failed on the prior URI+size behavior
(8 tests, 1 failure, prior 7 passing) then passed with the fix (8/0/0). Both roles
independently rechecked the new SHA. This metadata key is a local cache hint,
not a content hash or canonical immutable asset-version ID.

Component APK SHA-256:
`1210f4b55d1d53a8af40dbbc859e7e79c9a5f3b664f27c46365d5d00a9d568cb`.
Compiled manifest independently audited: media reads only, no Internet/write
permissions or services, app backup off and all nine cloud/transfer storage
categories excluded. Besides the launcher, AndroidX's profile receiver is exported
only behind the platform signature-level DUMP permission; startup provider is not
exported. No preview tooling activity is packaged. These checks are source/build
acceptance and do not establish physical-device behavior.

### Android environment failures and recovery

The first build failed because system Java 21 was a JRE without javac. A full
Temurin JDK 21 was installed outside the repository at /workspace/still-jdk.
That JDK initially lacked the managed proxy CA; the next build failed TLS
verification before app dependency resolution. A command-local system truststore
resolved this without disabling TLS verification. SDKs are installed outside the repository at /workspace/android-sdk. The initial
platform 35/build-tools 35.0.0 are superseded by platform 37/build-tools 36.0.0
for the final candidate (ADR0002).

Managed-environment build overrides (paths are session-local):

```sh
export JAVA_HOME=/workspace/still-jdk
export ANDROID_HOME=/workspace/android-sdk
export GRADLE_OPTS='-Dhttp.proxyHost=proxy -Dhttp.proxyPort=8080 -Dhttps.proxyHost=proxy -Dhttps.proxyPort=8080 -Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts'
```

Those overrides contain no credentials and are not needed on ordinary Android
Studio/GitHub Actions installations. No machine-local signing or SDK paths were
committed into app build configuration.

## CP2 — Final development handoff

CP2 local source/build handoff passed both roles at
`09d7a6d0b326d6b7c4933267b9478d371b3692f3`; the development branch was pushed and
[draft PR1](https://github.com/macdarenz-droid/Still/pull/1) opened. Hosted CI then
reopened the handoff gate: [initial run39188938802](https://github.com/macdarenz-droid/Still/actions/runs/39188938802)
passed backend/discovery but failed before the Android build. The setup action's
default `packages: tools platform-tools` requested obsolete SDK package `tools`.
The workflow now explicitly requests supported `platform-tools`; the following
step still installs the exact platform37.0/build-tools36.0.0. License logging is
quieted without disabling license acceptance or any build/test/lint guard.
A new exact-SHA reviewer and red-team pass plus hosted CI are required; the initial
hosted failure is not treated as green.

CP2 applies to the final committed handoff head after this acceptance record.
Run full checks on that exact head, then obtain separate independent reviewer and
red-team verdicts. The final SHA, command outcomes and artifact hash are recorded
in ignored `.artifacts/cp2-validation.json` and the draft pull request description
for `build/still-foundation`. Recording the final head outside its own tree avoids
a self-referential commit ID in this document. The PR description is the persistent
handoff record; session logs and local artifacts supplement it.

CP2 must preserve all CP1 source subtrees; any material source change reopens the
affected reviews and tests. Hosted CI results are separately visible on the PR.
Code/build acceptance cannot authorize publication or replace the blocked device,
real-provider, backup/restore, creation-output, cost or production-identity gates.

Rollback for this development milestone: close the draft PR/remove the feature
branch, uninstall `app.still.gallery.dev`, and stop the loopback API. Phone originals
were not modified. Preserve a copied development SQLite database before changing
its schema. No cloud copies, provider accounts or production deployment exist.

## CP2 outstanding feature and release evidence (historical)

- No attached Android device or accelerated emulator: x86_64 worker has no
  /dev/kvm or KVM module. Manual runtime checks are blocked here.
- Real Android device: permission grants/denial/partial access/revocation/reselection,
  external library changes, shared media viewing, sharing, accessibility, rotation,
  background/foreground and large-library performance: **not run**.
- Cloud providers, paid AI, OCR/document output, video render, backup/restore,
  cleanup safety, vault/app lock and store entitlement: **not implemented or tested**.
- Production identity, PostgreSQL, worker deployment, provider configuration,
  release signing and store/public-site publication: **out of this milestone**.

## CP3 — Whole-app lock development continuation

Development continues under the owner's original implementation request while
release-device verification remains open. R14 / F04 has an optional access gate using
platform biometric/device credentials, fresh authenticated enable/disable,
foreground-loss relock and screen-off/new-entry invalidation. Only a local lock
setting and uncertain-write marker are durable; unlocked sessions and challenge
identities are never restored. The gallery is absent while locked/authenticating.
Activity and inherited dialog screen protection is requested, with additional
recents protection on API33+. This is not encryption or a vault and does not hide
ordinary phone photos from other apps. See ADR0003 and the Android README.

### Source, ownership and builder evidence

- S05 policy source: `3ab60f947c04a1c259cca2b41276ed7107ad7d06` plus test-only
  follow-up `d6f2e0ca8e444970da5ce81e682dfe6946ca06fd`; integrated as `7ccc047`
  and `fa201f0`. Fourteen policy tests cover fresh/purpose-bound operations,
  cross-session identity collisions, stale/duplicate/canceled results, both
  credential callback/resume orders, background/screen-off/entry and false/throw
  persistence failures. Root integration ran all22 then-current JVM tests green
  at `fa201f0`; log `/tmp/still-cp3-policy.log` is session-local evidence.
- S06 native source: `e69c95ff8ee54592619ed3c0aa5608a5c8ff0d29`, integrated as
  `ea9b4f8`. Platform-only integration adds no dependencies or network access.
  Single-task launcher and fresh per-Activity sessions prevent stale authorizations;
  random per-attempt credential registrations do not restore old callbacks.
- Backend owns the policy/tests. Frontend owns native integration, storage, UI,
  strings and Android docs; its bounded helper `/root/frontend/android_tooling`
  polished only `ui/AppLock.kt`. SEO performed a read-only truthful-copy audit.
  Reviewer and red team had no implementation ownership.
- Builder stable-source assemble, all29 JVM tests (14 policy + 7 setting fault
  tests + 8 existing media tests) and strict lint passed at `e69c95f` in39s;
  lint says "No issues found." Logs: `/tmp/still-lock-final-build.log` and native
  reports. Initial build/29tests passed, but lint stalled while source changed;
  that client was canceled. Fresh stable-source full lint passed. No detector,
  assertion or guard was disabled, narrowed or suppressed.
- Storage tests include off bytes written before an exception plus failed rollback,
  readback failure, marker-removal failure, corruption and reopen recovery.
  File sync/readback/marker protocol is source/JVM evidence, not power-loss or OEM
  durability proof. Exact integrated artifact and final checks are recorded below.
- An additional backend read-only audit found a directory-ordering durability gap
  before final CP3 review: file-byte sync alone does not durably publish the marker
  or replacement rename. Fix-only source `72169ba` adds directory sync after the
  marker and replacement, before readback/removal, using public Android descriptor
  validation and fsync. All4 new simulated-crash regressions failed with the old
  ordering (`/tmp/still-lock-durability-before.log` and saved XML); they exercise
  published-marker, replacement, failed-disable and successful-enable durability
  independently of Android. Existing29 tests/guards are retained. Final integrated
  checks include these4 cases (33 Android tests total). These simulations do not
  establish actual filesystem/power-loss results. Marker deletion stays last; a
  crash can conservatively restore that marker and require authentication again.
- The first directory-sync candidate built and passed all33 JVM tests but strict
  lint rejected `O_CLOEXEC` on minSdk26 (the public constant requires27). That
  failed integration run at `5a475e8` is retained in
  `/tmp/still-cp3-android-initial.log`. An intermediate builder attempt `b8f1838`
  used `fcntlInt`, which strict lint also rejected because that public method
  requires30. Neither failed candidate is accepted. The final correction keeps
  optional close-on-exec behind its API27 guard; API26 retains read-only/no-follow
  open, directory validation, fsync and guaranteed closure. Final checks and
  independent verdicts apply only to the corrected source, with every guard kept.
  Final builder source `b60dd772e7987443e3ba6ece06a7f86f35612031` passed assemble,
  all33 JVM tests and strict lint in37s. The root Android source tree matches it
  exactly. Session evidence: `/tmp/still-lock-durability-final.log`, four passing
  regression results `/tmp/still-lock-durability-after.xml`, and preserved initial
  API27/API30 error excerpts `/tmp/still-lock-api27-cloexec-failure.log` and
  `/tmp/still-lock-api30-fcntl-failure.log`.

### Device observations and remaining gates

Five owner-supplied phone screenshots on19October2026 show gallery navigation,
populated Albums, an album grid, empty Favorites and Settings with approximately
17,000 visible items. Those are limited visual observations. Model, Android API,
installed source/APK identity and performance are unverified. No private screenshot
or media is committed or reused in public discovery assets.

No device is attached to this execution environment (`adb devices -l` empty), and
no accelerated emulator is available. The screenshots do not test this new lock.
All biometric/device-credential, lifecycle, rotation/restart, screen-off, snapshot,
TalkBack/large-text and storage-failure rows in `apps/android/DEVICE_CHECKS.md`
remain **blocked/unverified**. The earlier CP2 gallery runtime checklist remains
open apart from the limited observations above. Local catalogue identity/migration,
cleanup, backup, creative tools and production release work remain later tasks.

### Exact candidate gate

After this record is committed, run root `npm run check`, Android assemble, all
JVM tests and strict lint on that exact head, audit the local APK/manifest and
record its SHA-256. Obtain independent reviewer AND red-team verdicts; material
changes require new checks and both rechecks. Store final source/artifact identity,
command outcomes, verdicts and hosted CI results in ignored
`.artifacts/cp3-validation.json` and the existing
[draft PR1](https://github.com/macdarenz-droid/Still/pull/1). The persistent PR record
avoids a self-referential final hash in its own tree. A source/build pass is a
development handoff; it cannot authorize a production release or replace device
security checks. The earlier hosted binary download denial remains unchanged;
the offered APK is built/audited locally, and hosted binaries are not assumed
identical or inspected.

Rollback: revert the CP3 development commits or uninstall the development app.
Phone originals are unchanged; uninstall clears Still-local favorites/settings.
No production signing, account/provider connection, main merge or publication
forms part of CP3.
