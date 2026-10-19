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

### CP2 final development outcome (recorded by the next checkpoint)

The final CP2 source was `42432e65c4b14935de916913228da5f4e799f07c`.
Independent reviewer and red team both passed the exact local source/APK and
separately confirmed both hosted runs successful: [PR run39189212426](https://github.com/macdarenz-droid/Still/actions/runs/39189212426)
and [push run39189208299](https://github.com/macdarenz-droid/Still/actions/runs/39189208299).
All four PR checks passed. The locally delivered APK SHA-256 was
`69a9e318a8d5300a3af9affd9370d09976f69713982337ac8f44d5753bdb17fb`.
This promoted historical result preserves the exact gate after the PR description
moves to a later checkpoint. Hosted binary download/inspection was blocked;
native-device and production acceptance were excluded. Nothing was published.

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

### CP3 final development outcome (recorded by the next checkpoint)

Final source `e285a22d477a04f39fd7ea0d02758f44cc4486df` passed both independent
reviewer and red-team source/build/local-artifact gates, followed by each role's
separate confirmation of both successful hosted runs: [PR run39196615135](https://github.com/macdarenz-droid/Still/actions/runs/39196615135)
and [push run39196610661](https://github.com/macdarenz-droid/Still/actions/runs/39196610661).
All four PR checks were green. Root typecheck/site checks, all27 backend tests,
all33 Android JVM tests, assemble and strict lint passed on that exact head.
Audited local APK SHA-256:
`2604452f0ad0a7771388b4aed5a5f9b9f1d7e8a0892730f97df6fd85ce12037e`.
The local validation record independently retains both verdicts and hosted IDs.
No hosted binary inspection, native lock/device-security acceptance, main merge
or publication was inferred. This historical record remains after the PR moves
to CP4; all device limits above remain open.

## CP4 — Local catalogue and gallery controls

The owner requested continuing feature checkpoints after lock. CP4 implements
R01 / F01 / F02 as a local metadata catalogue plus media-type filters, stable date
and filename sorting, and explicit video Play/Pause controls. It adds no runtime
dependency, network permission, media-byte persistence, deletion or cloud job.
ADR0004 defines the data and race boundaries. Fresh authorized MediaStore reads
remain the sole authority for visible results; retained index rows never grant
access or imply that a missing source was deleted.

### Builder evidence and review findings

- S09 source `dcedbae637f915f3f31c8dd085dc30696d85a671`, integrated as `acb1f00`,
  adds versioned SQLite assets, namespace/incarnation history, opaque observation
  IDs, metadata revisions and transaction/scan fencing. Twelve new pure-policy
  JVM tests and eighteen real host-SQLite probes cover reset/handle separation,
  optimistic revisions, constraints, bound data, rollback and close/reopen.
  The adapter rejects destructive corruption repair and upgrade/downgrade.
- S09 builder assemble, all45 then-current JVM tests and strict lint passed.
  Root `npm run check` passed after integration, including all27 backend tests,
  TypeScript/site checks and the mandatory18 SQL probes; session evidence is
  `/tmp/still-cp4-s09-check.log`. These are component results, not final CP4 gates.
- The SQL probe executes the same SQL assets as the Android adapter against
  real temporary host databases. It does not execute Android SQLiteOpenHelper
  or prove OEM SQLite, device corruption handling or power-loss durability.
- During S10 preflight, both independent roles found a Favorites upgrade
  regression: old aggregate `external` URI references would not match new
  concrete-volume URIs. That candidate was not accepted or handed off. The fix
  retains raw keys, resolves eligible exact legacy URIs through current Android
  access, and uses only unambiguous ephemeral aliases. Unknown references stay
  saved with generic disclosure; explicit unfavorite removes proven keys only.
  Old preferences contain no historical birth/hash evidence, so compatibility
  cannot prove historical photo identity after legacy handle reuse.
- Follow-up `acefd77960f023fc7d09a728b9ba92be050367b4` passed the13 new
  Favorites regressions and all68 then-current JVM tests. Restoring the old
  raw-URI membership/removal behavior in an isolated worktree caused3 of31
  selected media tests to fail, proving visibility, reduced-access and targeted
  unfavorite coverage (`/tmp/still-gallery-favorites-before.log`). This is
  component evidence only: both roles then identified a second refresh race.
  Under unchanged full access, old viewer/grid media and callbacks remained
  usable during reload. The candidate was held for visibility/action fencing
  and a meaningful regression before final acceptance. The independent reviewer
  also required stale read-error publication to be fenced to its refresh.
- Red-team callback review then required a one-way gallery-disposal fence and
  current access checks before queued media or permission/settings actions.
  The supervisor approved only the AppLockRoot call-site change needed to pass
  a live controller-access predicate; authentication and durable lock policy
  remain unchanged. Playback start must consult that current eligibility for
  both native and visible controls. These are source-level callback traces,
  not a claim of reproduced Android event delivery or native exploitability.
- Frozen lifetime source `4ce5380cc1f9a65017646f1beeb931da9a964067` passed
  assemble and all72 JVM tests. An isolated missing-lifetime counterfactual
  failed exactly the2 new authority/disposal cases among35 selected media tests,
  with the older33 passing. Strict lint then rejected the custom VideoView's
  missing tools-compatible Context constructor (`ViewConstructor`); this
  candidate was not accepted. The bounded correction adds a deny-start tools
  constructor while production still supplies required live eligibility.
  The genuine failure remains in `/tmp/still-gallery-lifetime-final-build.log`;
  final corrected source requires fresh full checks and both independent gates.
- Backend owns the catalogue/schema contract; frontend owns live media queries,
  controls, playback and compatibility. Its helper
  `/root/frontend/android_tooling` owned only LibraryControls and then the new
  Favorites compatibility test file. SEO is read-only. Neither independent
  reviewer nor red team implemented these changes.

### Exact candidate and remaining acceptance

S10 final builder source `cc42e288cea57a9b7e9b56ccb2221de18b4af3fa` is integrated
through `d49513b`. Android source/tests/assets match that reviewed builder tree.
The constructor fix passed assemble/all72 JVM/strict lint in46s, and the final
documentation head repeated the full command successfully in10s with lint
"No issues found." Logs: `/tmp/still-gallery-final-green.log` and
`/tmp/still-gallery-final-exact-head.log`. Existing33 tests are retained unchanged;
new suites add catalogue12, library10, Favorites13 and refresh/lifetime4 cases.
SEO's precise current-reference/observed-token wording is included.

Meaningful counterfactuals in separate worktrees restored old behavior: URI-only
UI/preview rules failed3 of18 selected tests, raw Favorites membership/removal
failed3 of31, missing refresh eligibility failed2 of33, and missing lifetime/live
authority failed2 of35. Fixed code passes all corresponding suites. Session
evidence is `/tmp/still-gallery-{uri,favorites,refresh,lifetime}-before.log` and
matching before/after XML plus mutant patches. The earlier genuine lint failure
stays recorded above; no tests, detectors or checks were waived or weakened.

After this final candidate record is committed, run root `npm run check`, Android assemble, all JVM tests
and strict lint on that exact head; audit the local APK permissions/assets and
record its SHA-256. Both independent reviewer and separate red team must pass
the integrated source and artifact, including rechecks of the Favorites finding.
Record exact head, commands, counts, artifact hash, verdicts and subsequent hosted
CI in ignored `.artifacts/cp4-validation.json` and
[draft PR1](https://github.com/macdarenz-droid/Still/pull/1). That persistent PR
record avoids a self-referential final SHA in its own tree. Material changes
reopen corresponding checks and both gates. No acceptance is inferred here.

There is still no attached Android device or accelerated emulator. Native
MediaStore/reset/revocation, app-lock security, SQLite failure/restart, Favorites
upgrade, codec/player lifecycle, TalkBack/large text and large-library performance
remain blocked/unverified in the Android device matrix. Exact legacy Favorites
lookups are sequential and cancellation-aware; latency for large saved sets is
unmeasured. The supplied screenshots do not establish any of these results.
The earlier hosted binary download denial is unchanged: the handoff APK is built
and inspected locally, with no hosted-binary equivalence claim.

Rollback: revert CP4 development changes while preserving a copy of local app
data, or uninstall the development app, which clears Still-local settings,
Favorites and catalogue but preserves phone originals. No automatic database
drop/repair, main merge, release signing, publication, provider account or spending
forms part of this checkpoint.

### CP4 final development outcome (recorded by the next checkpoint)

Final source `1b4932b28d2afe9835cccd4f1d35783a713b5b6a` passed both independent
reviewer and separate red-team source/build/local-APK gates. Each independently
confirmed both hosted runs and all four checks green at that exact head:
[PR run39201559516](https://github.com/macdarenz-droid/Still/actions/runs/39201559516)
and [push run39201555444](https://github.com/macdarenz-droid/Still/actions/runs/39201555444).
Root assemble, all72 JVM tests (zero failures/errors/skips), strict lint,27 backend
tests,18 real host-SQL probes and TypeScript/site checks passed. Audited local APK:
`dc65fdb22e7072478220cf70681175a29538303a9bb2693a59484c3cdbd4532d`,12,229,446 bytes.
Signature, manifest/backup exclusions and all8 packaged SQL assets were checked
independently. This result is also retained in `.artifacts/cp4-validation.json`.
The versioned local delivery remains `.artifacts/deliveries/still-cp4-1b4932b.apk`.
Hosted binaries were not downloaded/inspected or presumed identical. Native
runtime/security/accessibility/performance, main merge and production publication
were excluded; the existing device limits remain open.

## CP5 — Exact readable-copy review and protected content

The owner requested continued feature checkpoints. CP5 implements the matching-copy
review/protection stage of R02/F03. User-started scans compare complete readable
representations, with honest candidate/omission/failure coverage. Manual keepers
are reversible and scan-bound. Explicit durable protection applies to all matching
readable content. No deletion/trash, reclaimed-space estimate, backup verification,
canonical pinned version or server authority is included; see ADR0005.

### Component evidence and ownership

- S13 source `9393d656252a4f841419c87bce247404d82bd8f9` and tests
  `a3b04a2a9d14204bb1db9e641addf6acbb8781a2` are integrated as `44638f0` and
  `b8f330d`. Three new source files provide scan-bound policy and strict durable
  content protection; three new suites add 27 cases (11 policy, 8 store,
  8 durability). Frozen component assemble, all 99 then-current JVM tests,
  strict lint and repository checks passed with no guard or assertion weakened.
- S13 tests exercise current/unknown favorites, multiple keepers, content identity,
  strict record parsing/capacity, real host-file close/reopen, competing-instance
  revision CAS, queued authority, revision exhaustion and a separate file/directory
  crash model. The bounded format accepts at most 4,096 protected-content keys
  and 512 KiB, with no pruning or silent reset. Uncertain/corrupt/newer
  record state is UNKNOWN and blocks extras/mutations. Tests do not prove the
  Android AtomicFile adapter or actual filesystem/power-loss behavior.
- Backend owns the new `cleanup/` implementation and tests. Its approved helper
  `/root/backend/protection_faults` authored only `ProtectionDurabilityTest.kt`.
  Frontend owns scanner/native UI integration and its scoped tests/docs; helper
  `/root/frontend/android_tooling` owns only `ui/CleanupScreen.kt`. SEO, reviewer
  and red team remain read-only; neither independent role implements changes.
- S14 scanner-only source `533dc5817789170ea2b65f2c1a59de75c4663053` passed
  16 focused streaming/scanner cases in 25s; session log
  `/tmp/still-cleanup-scanner-green.log`. An initial fixture returned a nonunique
  exact probe for duplicate enumeration, causing one test failure; the fixture
  was corrected to model a unique exact-row query, retaining the alias assertions.
  The real failure remains in `/tmp/still-cleanup-scanner-tests.log`. This is
  component evidence, not final UI/build/checkpoint acceptance.
- Frozen full S14 source `00a68868b56b52bc633273443508a40689cb5a2b` built
  successfully and passed all 122 JVM tests (99 existing/S13 plus 23 S14 cases).
  Strict lint then rejected six count-bearing strings with `PluralsCandidate`;
  this candidate was not accepted. The correction uses actual quantity resources
  and localized file-size formatting, without suppressing lint or changing tests.
  Preserve `/tmp/still-cleanup-first-full.log` and rerun the full command on the
  corrected frozen source before integration/acceptance.
- S15 read-only copy audit at that frozen source found no blocking claim issue
  after early scope/keeper/invalidation wording fixes. Native accessibility and
  component source/artifact acceptance remain separate from that copy audit.
- Red-team preflight found a deterministic Stop-before-dispatch race: a DEFAULT
  coroutine canceled before its body starts never enters the body's catch, so
  the screen could remain STOPPING without a reader. The candidate is held for
  completion-based terminal acknowledgement and a queued-dispatcher regression;
  real reader admission must still remain held until native work exits. This
  is source/coroutine evidence, not a claimed Android runtime reproduction.
- Reviewer also found Cleanup inherited a filtered gallery-count subtitle even
  though its scan uses the full authorized scope. That subtitle must be hidden
  or correctly scoped before the final candidate; scan controls do not silently
  apply a current Photos/type/search filter.
- Red-team callback review also found the protection dialog could dispatch a
  retained confirmation after dismissal or consume a newly opened identical-value
  request. Hold for an active-request reference-identity guard before clearing or
  invoking the mutation, plus cancel/reopen regression. Expected review/revision
  checks remain required but cannot represent dialog dismissal on their own.
  A further same-shaped-review trace showed structurally equal display rows could
  retain an old dialog while a new callback targeted a different review. Require
  an opaque per-review interaction identity in dialog state/admission as well;
  displayed names/lengths/status cannot serve as review identity.

### Exact candidate and remaining gates

Final S14 builder source `e327d8da782fa5d84cf92c613d518be6a5db0152` is
integrated through `49fc9e5`; root's Android tree byte-matches that source.
It includes final S13 `47852e5b98360219362ab55501172ab922a3d0c4`
(root `108828d`): a private per-snapshot reference-identity membership set
avoids repeated whole-group scans while retaining foreign-group rejection.
Its new 2,000-group identity test is functional coverage, not a performance benchmark.

The count correction at `54247522b9559a2d8cac9de8fba8a4947f3958c3`
passed assemble/all122 JVM/strict lint. The callback fixes at
`607f5b356dd4aee7f833c8ba94d59a3190e678af` add actual worker-completion
acknowledgement (including never-started cancellation), exact active single-use
dialog request identity and opaque current-review identity. They hide the
unrelated filtered header count. Six new coordinator/confirmation regressions
exercise queued dispatch, blocked read and close, disposal and same-shaped new
reviews; all128 then-current tests/build/lint passed. Both independent roles
rechecked these findings and found no remaining component blocker.

Final builder assemble/all129 JVM/strict lint passed in1m1s, and repository checks
passed TypeScript,27 backend tests,11 discovery checks and18 real host-SQL probes.
The129 cases comprise72 unchanged prior cases,28 S13 cases and29 S14 cases.
All8 baseline test files remain byte-identical to `0ff3db7`. Evidence:
`/tmp/still-cleanup-localized-full.log`, `/tmp/still-cleanup-callback-full.log`,
`/tmp/still-cleanup-final-full.log`, `/tmp/still-cleanup-final-root-check.log`
and `/tmp/still-cleanup-final-evidence.json`.

Three isolated narrow counterfactuals each selected all29 S14 cases. Restoring
body-only Stop handling fails2 cases; removing active request identity fails3;
removing review-scope identity fails1 (same-shaped replacement review). Other
selected cases pass. Fixed corresponding classes each pass3/0; full fixed source
passes129/0. Logs, patches and before/after XML are retained under
`/tmp/still-cleanup-{stop,confirmation,review-scope}-before.log`, matching
`-mutant.patch`, `-before.xml`, `-before-results/` and `-after.xml` paths.
The genuine earlier fixture and pluralization failures remain above; no tests,
detectors or guards were waived or weakened. These are source/JVM results, not
native event-order or filesystem guarantees.

After all final owned source, integration and evidence changes are committed,
run root `npm run check`, Android assemble/all JVM/strict lint on that exact head.
Audit the actual local APK, signature, permissions/backup exclusions and packaged
assets, then obtain separate independent reviewer AND red-team verdicts. Material
fixes require new checks and both rechecks. Record exact SHA/APK, results, findings,
verdicts and subsequent hosted runs in ignored `.artifacts/cp5-validation.json`
and [draft PR1](https://github.com/macdarenz-droid/Still/pull/1), preserving a
versioned local APK. The persistent PR record avoids self-referential source IDs;
the next checkpoint promotes this outcome here. No acceptance is inferred yet.

No Android device or accelerated emulator is attached. Native transformations,
blocked-read cancellation latency/closure, MediaStore scope changes, authentication,
AtomicFile durability, TalkBack/large text and large-library/video performance
remain blocked/unverified. A scan is evidence of observed readable bytes, not
identical underlying originals or present pinned content. Host unit/SQL/file tests
do not replace these native checks. Hosted binary download restrictions remain
unchanged: only normal CI metadata/logs are read; the offered APK is built and
audited locally, with no hosted-binary equivalence claim.

Rollback: stop scans and revert CP5 with a retained copy of local app data, or
uninstall the development app (clears Still-local annotations/settings/catalogue,
preserves phone originals). No automatic record repair/drop/pruning, native media
mutation, production signing, main merge, spending or publication forms part of
this checkpoint.
