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

## CP5 final development outcome (recorded by the next checkpoint)

Final source `1cc49a6e3ca4a23adffbb1eaa52e33183c1aca4c` passed separate
reviewer and red-team exact local-source/APK gates. Each independently confirmed
[PR run39205892848](https://github.com/macdarenz-droid/Still/actions/runs/39205892848)
and [push run39205889018](https://github.com/macdarenz-droid/Still/actions/runs/39205889018),
both runs/all four checks green. Root129 JVM cases (zero failures/errors/skips),
assemble/strict lint,27 backend cases,18 host-SQL probes and TypeScript/discovery
checks passed. Actual audited local APK:
`8df7fe9b8dde196d06084fde07ed711d04ff1ebb9e8630247c19a314e34a6633`,12,250,577 bytes.
Signature, compiled manifest/backup exclusions, ZIP and all8 SQL assets passed.
The versioned delivery remains `.artifacts/deliveries/still-cp5-1cc49a6.apk`;
full evidence is `.artifacts/cp5-validation.json`. Hosted binaries were not
downloaded or presumed identical. Native/device/production limits remain open.

## CP6 — Selected Drift native photo experience

The owner selected the first DRIFT design on 19 October 2026. Two generated renders
and self-contained interactive browser samples are retained in the private
[design Page](https://chatgpt.com/space/page_c731a83d8b3c8191bec755c2c9575d18).
Selected final HTML hash:
`fec5ed6a5e668e6ff9f4e54f638325e968b882f2769e5c6f5cfcbde6e969f2d2`.
Both independent design roles passed; actual browser checks corrected keyboard
focus, enlarged-text reflow and live reduced-motion cancellation. This evidence
supports the design prototype only. No stock assets enter the native app.

During native implementation, the owner explicitly reiterated "no ai wordings"
and requested rereading the architecture. The supervisor reread the complete
1226-line canonical document. R12/section4.3 is a final copy gate: direct labels,
real metadata and no sample slogans or generic promotional headings. Frontend and
SEO were notified, and the native draft slogans were removed. Planned AI features
remain in scope; this correction concerns wording. Architecture bytes are retained.

### Component evidence and review boundaries

S17 builder `1652b12a7cd26f19fe1a542c03d4053e356b51db` is integrated as
`f6e06331cc5fcfb370d0660091d2711e42a0a2f1`. Exactly two new owned files add
current-view navigation and14 policy cases. Frozen builder assemble/all143 JVM
cases/strict lint and repository checks passed. Both independent source/test
preflights found no concrete policy blocker; neither approved the integrated UI
or the builder-only APK as a handoff. XML/lint evidence remains under
`/workspace/Still-backend/apps/android/app/build/test-results/testDebugUnitTest/`
and `app/build/reports/lint-results-debug.txt` in that worktree; completed command
output is retained in the session tool transcripts.

The14 cases cover exact/foreign/replaced snapshots, unchanged-looking refreshes,
URI reuse, mutable input ownership, duplicate tokens, bounded steps and paging,
timezone/midnight/DST/Unknown dates, unchanged NAME ordering and17,000-row
reachability. The model stores copied token/date indexes only; it is not native
access authority. The large input case proves functional bounds, not memory,
frame-time or device throughput. All129 earlier cases/assertions remain preserved.

S18 owns native UI/wiring. Its approved helper `/root/frontend/android_tooling`
owns only new `ui/DriftChrome.kt`; parent owns real action closures, strings,
theme, tests and commits. Reviewer and red team remain read-only. Preflight risks
include structural equality reusing an old projection, delayed gestures retargeting
new focus, animations retaining withdrawn media, live animation-scale changes and
old viewer callbacks acting on a new viewer instance. Require actual source/view/
snapshot/instance checks in production seams and meaningful regressions for fixes.

Initial S18 implementation `ddd3e54f1fe48c1f39930e5f0d402d60e3588899`
compiled and passed five focused interaction cases. A separate copy follow-up
split malformed date input from a valid date with no result and tightened labels.
These are component results, not a completed full native regression or acceptance.

Both independent source reviews then held S18 for two concrete callback traces:

- Token-only focus checking admits a retained old A action after deliberate
  A→B→A navigation in the same projection. Require an opaque identity for each
  accepted focus, captured in all actions and gesture/effect keys.
- A date picker keyed only by the projection allows a dismissed picker callback
  to navigate, or its late dismiss to close a newly reopened picker at the same
  focus. Require exact active per-opening identity plus current focus/projection,
  with guarded consume/dismiss and invalidation.

These are deterministic production-seam/source findings, not claimed native event
delivery or exploits. The candidate/full run was superseded before fixes; retain
genuine logs and meaningful original-fail/fixed-pass regression evidence. Final
source checks and both gates remain pending. The architecture reread also raised
retaining a separately reconciled UI focus intent across unchanged refresh/mode
changes; clearing action authority must not unnecessarily discard browsing position.

The next source review held `9d05174` for additional production wiring cases:
batch Share/Favorites must reject a callback from a replaced view even when the
new selection overlaps it; old date-page callbacks must not undo later paging;
opening a nonfirst Grid item must update the position intent before refresh or
switching to Ribbon. The separate red team also found that new Boolean-only
navigation/options overlays could accept callbacks after dismissal/reopening.
Require exact per-opening overlay identity without preventing intentional edits
inside a still-open options request. These findings concern newly introduced UI
intents, not a demonstrated permission bypass. Keep fixes bounded to those seams
and verify meaningful old-fail/fixed-pass cases before a new full freeze.

### Frozen native UI component

S18 final builder `c0a11be23a6de00b2776caae9adc722a4ec2e407` is integrated
through `768479b`; root's Android tree byte-matches that builder. S18's four
owned commits follow the already-integrated S17 policy without duplicating it.
All12 assigned UI/docs files stay within the task card. Product copy audit S19
passed at this exact builder head; no slogans, fictional captions or new claims
were introduced by the callback corrections. Both independent targeted source
rechecks found the reported corrections complete, with final artifact gates pending.

The corrected UI uses opaque identities for focus, each viewer, each date picker
and each Options/Menu opening. Valid rail paging renews intent even when its source
or offset later returns to the same value. Both batch handlers check the captured
view; Grid/Ribbon opening aligns focus and position memory. Cleanup keeps its
existing full-gallery source gate. Invalid duplicate-token projections show an
error/Retry before Grid; effect-initialized nonempty projections wait for readiness.
The independent red team found the duplicate-token fallback and checked readiness.
Position memory retains only a UI token, reconciles strong fresh continuity and
never restores action authority, rows, bitmaps or an authenticated lifetime.

The genuine full run at `9d05174` assembled and passed152 JVM cases, but strict
lint rejected `RememberReturnType` in the focus initialization. No suppression or
lint baseline was added: final source initializes through a snapshot-checked
`LaunchedEffect` and gates presentation on exact projection readiness. Its full
assemble/all155 JVM/strict lint passed in1m39s; repository checks also passed.
Logs: `/tmp/still-cp6-9d05174-full.log`,
`/tmp/still-cp6-c0a11be-full.log`, `/tmp/still-cp6-c0a11be-npm-check.log`.
The superseded `dfa3757` run is retained without a full-pass claim.

All129 prior JVM cases in16 files/assertions remain unchanged. S17 adds14 and
S18 adds12, for155 cases in18 suites, zero failures/errors/skips. The UI cases
exercise actual production intent helpers: viewer reopening, equal-looking
projection replacement, newer choices/live authority, five-card index bounds,
focus ABA, picker cancellation/reopening/disposal, strong position recovery from
a nonfirst Grid open, batch scope, page ABA and overlay request identity.
Source inspection additionally checks the actual bounded composition and wiring;
pure index/helper tests do not prove Android rendering or event delivery.

Eight isolated counterfactuals show that the new assertions detect the reported
old behavior. The four original9d UI mutants select9 cases each: source-only
viewer identity fails1, token-only focus fails1, missing active date request fails2,
and clearing position during loading fails1. Four final-source mutants select12
cases each; removing batch projection, page identity renewal, Grid focus alignment
or overlay request identity each fails its corresponding case. Final fixed source
passes all155. Patches, summaries and logs are retained in
`/tmp/still-cp6-evidence/`, `/tmp/still-cp6-mutant-summary.json`,
`/tmp/still-cp6-supplemental-mutant-summary.json` and matching
`/tmp/still-cp6-mutant-*.log`. Only the last supplemental mutant's XML was retained;
the earlier XML was overwritten before copying. Fixed18-suite XML/lint reports
are preserved in the evidence folder. No evidence was fabricated or checks rerun
solely to recreate those overwritten reports.

VideoPlayer/PlaybackVideoView bodies, security/lifecycle/storage policies,
dependencies, permissions and canonical architecture bytes remain unchanged.
Root must now validate its exact final head and actual local APK independently
of the builder APK; the component result is not checkpoint acceptance.

ADR0006 and cards S17–S20 define the native implementation. Record owned source,
meaningful current-view/date/identity tests and all actual review findings here.
After final source/docs are committed, run full root/native checks and audit the
local APK, then obtain independent reviewer AND separate red-team exact candidate
verdicts. Fixes require corresponding new checks and both rechecks. Keep final
source/hash/results/verdicts/hosted links in ignored `.artifacts/cp6-validation.json`
and draft PR1 to avoid a self-referential final SHA in its own tree. Component or
browser approval is not native checkpoint acceptance.

No Android device/accelerated emulator is attached. Native motion, touch, system
scale changes, TalkBack/keyboard focus, enlarged text/rotation, privacy surfaces,
playback lifecycle and large-library memory/frame performance are unverified.
Existing native matrix rows remain open and expand for Drift. No main merge,
release signing, spending, publication, deletion or data migration is included.

## CP6 final development outcome (recorded by the next checkpoint)

Final source `8f26a389f4e1748a3b6bdba09c0abf67f7c99ff3` passed separate
reviewer and red-team exact local-source/APK gates and each independent hosted gate.
[PR run39214095132](https://github.com/macdarenz-droid/Still/actions/runs/39214095132)
and [push run39214090136](https://github.com/macdarenz-droid/Still/actions/runs/39214090136)
completed successfully, all four checks green. Root155 JVM cases, assemble/strict
lint,27 backend cases,11 discovery checks,18 host-SQL probes and TypeScript passed.
Audited local APK: `24149903b88ec4a1d4839f708aef2f420ab00f2d3f78fc5c13d3d3e40160ac8f`,
12,259,089 bytes. Signature/manifest/permissions/backup/ZIP/8SQL audits passed.
Versioned delivery: `.artifacts/deliveries/still-cp6-8f26a38.apk`; full exact record:
`.artifacts/cp6-validation.json`. No hosted binary was downloaded or presumed equal.
Native/device/production checks remain open, not inferred from these passes.

## CP7 — Owner-requested screen capture

The owner asked to remove screenshot and screen-recording blocking to show the app.
This explicitly authorizes removing the capture-only secure-window and Recents
restrictions, superseding the earlier ADR0003 capture requirement. Optional app-lock
setting, authentication attempts, fail-closed storage, foreground/lifetime/media
fences, and player behavior remain unchanged. No capture toggle, recording service,
media mutation, permission, dependency, schema or new stored/transmitted data is added.
Screens and ordinary Recents previews become captureable as requested. Android may
independently protect its system authentication UI.

S21/S22 define the bounded implementation and independent gates. Preserve all155
existing cases/assertions; the policy removal is verified by source/API and actual
APK inspection, not a unit test mirroring deleted flag calls. Final frozen head
requires `npm run check`, Android assemble/all JVM/strict lint, APK signature/
manifest/backup/ZIP/assets audit, separate reviewer AND red-team exact source/local
APK passes, then all hosted checks and each independent hosted confirmation.
Record final immutable source/hash/results/verdicts in `.artifacts/cp7-validation.json`
and draft PR1, preserving a unique versioned local APK. The next checkpoint promotes
that outcome here rather than embedding a self-referential source SHA.

S21 builder `6e3f3a92e2d372583966d745a35cc697dc717f5a` changes exactly the
three assigned source/resource files. The source sweep finds no app-owned secure
flag, Recents suppression or capture callback. Controller comparison differs only
by removal of its callback parameter and invocation; full checks and actual APK
inspection follow on root's frozen integrated head. Source evidence:
`/tmp/still-cp7-s21-source-checks.json`. Platform libraries may still contain
general secure-window support; inspect app-owned compiled methods rather than
claiming every such library symbol must disappear from the APK.

The first integrated candidate `688f385` passed assemble/all155 JVM/strict lint
and repository checks. Independent red-team APK inspection confirmed removal of
the app-owned window calls, controller callback and old compiled Settings promise.
It found one stale inline comment still claiming secure flags were applied before
authentication. Builder follow-up `82695339233e0ad5f0bd7110dbc2a33296a84c93`
corrects that comment only; no functional failure was reported. Final candidate
checks and both exact-source/APK verdicts follow the correction. Earlier source
comparison's two-line callback delta is now accompanied by this comment change.

No attached phone or accelerated emulator is available. Actual capture/recording/
Recents surfaces, system prompt policy, authentication/lifecycle/accessibility and
performance remain blocked/unverified until observed on identified hardware.
Rollback by reverting CP7 and installing a compatible retained build with local
app data preserved. No main merge, production signing or publication is included.

## CP7 final development outcome (recorded by the next checkpoint)

Final source `e477f09667c6bf19fe747639b4b668762129a0b3` passed separate exact
local-source/APK reviewer and red-team gates and each independent hosted gate.
[PR run39216099433](https://github.com/macdarenz-droid/Still/actions/runs/39216099433)
and [push run39216092858](https://github.com/macdarenz-droid/Still/actions/runs/39216092858)
completed successfully, all four checks green. All155 unchanged JVM cases,
assemble/strict lint, TypeScript,27 backend cases,11 discovery and18 host-SQL
probes passed. Actual local APK:12,259,057 bytes, SHA-256
`f4b56261a9b675a90eccd9f5fe3b9fd574228de24e38e26f24655fe279057f38`.
Signature/compiled app-owned capture paths/manifest/permissions/backup/ZIP/8SQL
checks passed. Delivery `.artifacts/deliveries/still-cp7-e477f09.apk`; full record
`.artifacts/cp7-validation.json`. No hosted binary was downloaded or presumed equal.
Real capture/recording/Recents/upgrade and other native/device checks remained open.

## CP8 — Ribbon navigation freeze

The owner reports that a left swipe stops ribbon navigation, Next also stops,
and switching tabs restores it. Device/API and a native recording are not yet
available. The owner then asked to continue the architecture; responsive R01
browsing and section4.2 interaction behavior make this repair the current checkpoint.

The initial hypothesis was that a retained pointer job combined a current permission
check with an older movement action. Compiled Compose1.12.1 code restarts that job
when its focus key changes, so ordinary retention was not proven. Do not present
that conditional hypothesis as the established device cause or refactor the
pointer detector solely for it.

Both independent source investigations instead identified the shared gate defect.
StillApp supplies the local Kotlin reference `::focusedCurrent`. Distinct A/B
references compare equal under FunctionReference equality even though the compiled
objects capture different focus entries. DriftRibbon's structural-equality
`rememberUpdatedState(canInteract)` retains A after B is published. A then fails
the exact-focus check, blocking both swipe and Next until tab disposal recreates
the holder. Existing policy-only tests did not exercise this Compose publication
step; keep those155 cases and add production state/callback regressions.

Reviewer proof `/tmp/StillCapturedGateProbe.java` and
`/tmp/still-reviewer-cp8-captured-gate-probe.log` uses the actual generated reference
class and Compose state: distinct references compare equal and structural state
retains A. Its first probe uses null navigation/snapshot placeholders and inspects
the captured entry before calling the production interaction guard; it does not
invoke those incomplete compiled callbacks or reproduce Android touch delivery.
The supplemental `/tmp/StillCapturedGateFullProbe.java` and
`/tmp/still-reviewer-cp8-full-gate-probe.log` use three real media rows,
production source tokens and a real S17 snapshot. Direct generated-callback
invocation proves A is initially true, false after A→B, while B is true despite
equality. Structural Compose state retains A/denies; referential state publishes
B/allows. The separate red team independently reran this probe successfully.
This is actual compiled callback/state execution, not an Android gesture claim.
Final regression details follow.

S23/S24 require reference-identity callback publication while preserving the original
exact focus/source/action guards, pointer keys, finite motion and five-card bound.
No new permission/dependency/schema/IO/media mutation or capture-policy reversal.
New focused cases must reproduce equal-comparing real Kotlin captured references,
show current controls remain usable and prove old captured actions still fail.
An isolated structural-policy counterfactual must fail the real new regressions.

S23 builder `a1674993392a13bc7175fc0cbe72104fae6c8ccc` is integrated with
exactly three owned files: a tiny reference-policy state holder, the two-line
Ribbon adapter replacement and four new focused cases. StillApp, detector keys,
raw actions, source/focus policies and all155 prior cases remain byte-identical.
Focused cases pass4/0; the independent reviewer also ran those actual compiled
cases and inspected the adapter. The separate red-team source preflight is clear.

The isolated counterfactual changes only the production holder's import/call
from `referentialEqualityPolicy` to `structuralEqualityPolicy`. All four tests
then fail assertions (zero errors/skips), including the shared button/swipe
sequence failing after its first move. Fixed cases pass with the same tests;
no detector, assertions or guards were weakened. Evidence:
`/tmp/still-cp8-evidence/summary.json`, `fixed-4.xml`,
`structural-policy-mutant-4.xml`, `structural-policy-mutant.patch`,
`/tmp/still-cp8-gate-focused-fixed.log`,
`/tmp/still-cp8-gate-structural-mutant.log` and
`/tmp/still-reviewer-cp8-gate-junit.log`. These prove actual state/callback
behavior, not native gesture/frame behavior. Root full159 checks and artifact
gates remain required before development delivery.

After the final owned changes are committed, root must run full assemble/all
JVM/strict lint and `npm run check`, audit the actual local APK, obtain both
independent exact-source/APK passes and each hosted confirmation, and update draft
PR1. Record final immutable source/APK/results/verdicts in
`.artifacts/cp8-validation.json` and preserve a versioned local delivery. The next
checkpoint promotes that outcome here without a self-referential final SHA.
Native repeated touch/button sequences, interruptions, motion/accessibility and
hardware behavior remain blocked until observed; source/runtime proof does not
replace those observations. Restore a compatible retained build with local app
data preserved to roll back. No production signing, main merge or publication.

## CP8 final development outcome (recorded by the next checkpoint)

Final source `f3644e3709d21d0dd7dedbb515da5b9744618eb8` passed separate
reviewer/red-team exact local-source/APK and independent hosted gates.
[PR run39218795969](https://github.com/macdarenz-droid/Still/actions/runs/39218795969)
and [push run39218791098](https://github.com/macdarenz-droid/Still/actions/runs/39218791098)
completed successfully, all four checks green. Root159 JVM cases (155 preserved
plus4), assemble/strict lint,27 backend cases,11 discovery,18 SQL probes and
TypeScript passed. Actual local APK:12,599,181 bytes, SHA-256
`e9b776f50172f9886a9316116b22650c0e655b9250e9caa3d42d6cecb31cdd34`.
Compiled gate/adapter, signature, manifest/backup/ZIP/8SQL audits passed. Delivery
`.artifacts/deliveries/still-cp8-f3644e3.apk`; full evidence
`.artifacts/cp8-validation.json`. APK and updated draft PR record verified before
continuing. Actual owner-device symptom confirmation and native checks remain open;
no hosted binary was downloaded or presumed equal. The owner received the APK
while architecture work continues; device verification is not a blanket pause.

## CP9 — Cleanup planning and recovery foundation

The owner asked to continue the architecture. Section19.1 places exact source/
version contracts and recovery journaling before native removal and backup.
ADR0007 and S25–S28 define an internal local-development metadata foundation:
strict opaque references, trusted default-deny evidence, current reviewed intent,
revision/expiry/idempotency and a dedicated durable journal. Execution capability
remains false and there is no public/native/provider integration or mutation call.
Current Android observations/readable hashes remain insufficient original pinning.

Preserve all27 backend and159 Android cases; add meaningful contract/real SQLite
fault/reopen/concurrency/evidence/uncertainty coverage. Both independent roles must
check actual source boundaries and test effectiveness, then exact frozen root/
local APK and hosted gates. Record genuine failures/fixes here, with immutable
candidate/hash/verdicts in `.artifacts/cp9-validation.json` and draft PR1. No
source/component result is final checkpoint acceptance. Native mutation and
power-loss behavior remain unverified; production PostgreSQL is not implemented
by a separate development SQLite journal. Keep all canonical architecture bytes.

Source preflight identified a clock interleaving gap between the separately
committed time observation and the following plan transaction. The builder now
reads the persisted high-watermark again inside the actual plan transaction;
the separate observation still survives rejected commands. A controlled
two-instance regression and a counterfactual without that reread are required
before this finding can be closed. This is host SQLite evidence, not phone
power-loss or native removal evidence.

Independent preflight also found that an initialized foreign SQLite database
could look empty after its tables were dropped, even after VACUUM. Such files
must be rejected unchanged rather than initialized as a journal. The separate
red team identified a semantic history rollback: a shape-valid current row could
lose cancellation or uncertainty retained in an older operation result. Require
command/result binding and monotonic recovery history, with real tamper/reopen
regressions, before closing these findings. Component fixes remain pending the
frozen-source and final artifact gates.

Reviewer runtime fault evidence also showed a failed current-plan update could
roll back a later observed clock value, then reopen as reviewable after host
clock rollback. `/tmp/still-reviewer-cp9-read-clock-probe.json` records the
failure; the same diagnostic in
`/tmp/still-reviewer-cp9-read-clock-fixed.json` now preserves the high-watermark
and returns expired. The reusable probe is
`/tmp/still-reviewer-cp9-read-clock-probe.mjs`. Builder regression and frozen-head
checks remain required; this source fix is not final checkpoint approval.

The red team also reproduced a current row claiming `confirmed_nonexecuting`
without a retained confirmation command. Evidence:
`/tmp/still-redteam-cp9-confirmation.mjs` and
`/tmp/still-redteam-cp9-confirmation.log`. Require matching confirmation provenance
and a tamper/reopen regression; execution is false even in the malformed record.
Other independent probes reject missing creation/uncertainty history and
no-change evidence that expires during an adapter callback. Their logs are
`/tmp/still-redteam-cp9-semantic.log` and
`/tmp/still-redteam-cp9-reconcile-expiry.log`. Final frozen-head checks must cover
all corrections together.

S25 builder `ccd6745e0f1ce064d2a79fe4efa5f708a4c9f0c0` and S26 test builder
`802993022c2608ccaa90c93d61397e1b0bb7eb63` are integrated without owned-source
changes. S25's frozen full check passed50 backend cases (all27 preserved plus23
new journal cases), TypeScript,11 discovery and18 host-SQL probes. S26's20 focused
contract cases and typecheck passed separately. Root must now verify all70
together. Logs: `/tmp/still-s25-check.log`,
`/tmp/still-cp9-cleanup-contracts-focused.log` and
`/tmp/still-cp9-cleanup-contracts-typecheck.log`.

All seven isolated final-source counterfactuals detect the recorded defects:
transaction-local time, failed-read clock persistence, foreign-empty reuse,
cancellation/history rollback, confirmation provenance, constructor read snapshot
and callback expiry. Six fail assertions; the constructor-snapshot counterfactual
fails by rejecting a valid concurrent commit. Summary and individual logs:
`/tmp/still-s25-counterfactuals.json` and
`/tmp/still-s25-counterfactual-*.log`. These close component regressions, subject
to independent effectiveness review on the exact integrated source.

One new malformed-journal fixture initially assumed a read-only WAL connection
could not create SQLite coordination sidecars. SQLite can create those files;
the fixture now establishes DELETE mode before its baseline, preserving byte and
sidecar assertions that detect premature writable WAL setup. Existing tests are
unchanged. The accepted claim is no application PRAGMA/DDL/record repair before
validation, not sidecar-free reads of every WAL database.

The frozen integrated candidate still needs root full checks, actual local APK
audit and both independent exact-source/artifact and hosted verdicts. Final
immutable evidence belongs in `.artifacts/cp9-validation.json` and draft PR1;
the next checkpoint promotes that final outcome here. Native source pinning,
trash/restore, power loss and provider behavior remain unimplemented/unverified.

The first integrated candidate `d6e70c2` passed70 backend cases and Android159/
assemble/strict lint, but independent review blocked acceptance on a physical-
identity continuity defect. After uncertainty and a read-generated expiry revision,
a malformed current row could clear its known physical source identity; reopen
accepted it and a different opaque alias of the same physical source became
reviewable. Reviewer runtime proof:
`/tmp/still-reviewer-cp9-alias-history-probe.json`. Preserve a historically known
physical identity under the unchanged exact source/version/revision, reject
contradictory current evidence and corrupted reopen state, and add the exact
regression with old-policy failure before another final candidate. Initial full
logs are `/tmp/still-cp9-preflight-d6e70c2-check.log` and
`/tmp/still-cp9-preflight-d6e70c2-android.log`; their green tests did not cover this
defect and do not approve the checkpoint. A separate internal recovery-text
follow-up also clarifies what to do when all retained uncertainty history is full.

The separate red team reproduced a related case without database tampering:
record uncertainty while the physical identity is unknown, reopen with a trusted
adapter, then submit different opaque IDs for the same physical source. The old
unknown identity was treated as disjoint and the new plan became reviewable.
`/tmp/still-redteam-cp9-unknown-alias.mjs` and
`/tmp/still-redteam-cp9-unknown-alias.log` retain the failing evidence. Unresolved
unknown physical identity must conservatively fence that owner's other plans;
another owner remains isolated. Both alias findings block `d6e70c2` despite the
separate APK being unchanged and passing its artifact checks.

S25 follow-ups `4c3d3231497133bfdb96761f27c2f91fc182c1b6` (recovery wording)
and `22496a6bce60069263b584247981e1a522f3e68d` (physical identity) are integrated.
Four additional real journal cases preserve all23 earlier cases and cover known
identity tampering after read-generated revisions, identity-changing revalidation,
unknown-identity owner fencing through verified reconciliation, and valid first
null-to-known review. Builder full checks pass54 backend cases; integrated root
must check all74 including S26's20. Logs:
`/tmp/still-s25-physical-check.log` and
`/tmp/still-s25-physical-followup.log`.

All ten final-workflow counterfactuals now detect the old policies: the prior
seven plus physical-history binding, unknown-identity uncertainty and identity-
changing revalidation. The summary
`/tmp/still-s25-counterfactuals.json` binds workflow SHA-256
`f1fefecbe16ad7a90124185287fee5801b410328aa393983904163fe7675f054`.
Reproducible script `/tmp/still-s25-run-counterfactuals.py`, exact patches in
`/tmp/still-s25-counterfactual-patches/` and individual logs are retained.
These are component evidence; both independent roles must close their alias
blockers on the new exact integrated candidate and actual local artifact.

## CP9 final development outcome (recorded by the next checkpoint)

Source `1f475586d8b7802e33142f290e620be4d4beb5ba` passed separate reviewer and
red-team exact source/actual local-APK gates, then each hosted confirmation.
[PR run39224244184](https://github.com/macdarenz-droid/Still/actions/runs/39224244184)
and [push run39224236344](https://github.com/macdarenz-droid/Still/actions/runs/39224236344)
succeeded with all four checks green.74 backend cases (27 preserved,27 journal,
20 contracts),159 Android cases, TypeScript,11 discovery,18 SQL probes, assemble
and strict lint passed. Ten final-source counterfactuals detect unsafe removed
guards; prior candidate `d6e70c2` remains rejected as recorded above.

Actual local APK is12,599,181 bytes, SHA-256
`e9b776f50172f9886a9316116b22650c0e655b9250e9caa3d42d6cecb31cdd34`,
byte-identical to CP8; delivery `.artifacts/deliveries/still-cp9-1f47558.apk`.
Full record `.artifacts/cp9-validation.json` and updated draft PR1 were verified
against clean source/latest-main ancestry before continuation. No hosted binary
was downloaded. CP9 is internal, default-deny and nonexecuting; native pinning,
trash/restore/providers and hardware/power-loss acceptance remain outside it.

## CP10 — Selected inputs and photo comparison

ADR0008 and S29–S32 add explicit two-photo, bounded temporary task preparation
and useful native comparison under architecture7.2/19.1. Task-representation IDs
describe captured readable bytes only, never original fidelity or CP9 readiness.
The owner's Continue instruction applies the architecture's selected-task staging
option; no automatic/full-library copy or new provider transmission is authorized.

Preserve all74 backend/159 Android assertions and existing policies; add meaningful
production stream/file/source/coordinator/transform tests and fault evidence.
Both independent roles must approve the exact final source/local APK and each
hosted gate. Record real failures/fixes here and immutable candidate/hash/verdicts
in `.artifacts/cp10-validation.json` plus draft PR1. Device/provider/filesystem/
process-kill/power-loss/gesture/large-text/accessibility behavior is blocked until
actually observed; host proofs cannot replace those checks. No cleanup execution,
backup capability, production signing, main merge or publication is included.

Early independent source preflight identified a raw-path decoder draft bypassing
the proposed ticket-owned read boundary; S30 is converting all reads to owned
staged streams. It also found initialization completion could leave CLEANING
after intervening invalidation, and a retained unscoped Retry could clean a newer
comparison. Focused production coordinator regressions/counterfactuals are required,
including a worker cancelled before its body starts.

S29 preflight identified filesystem pathname checks that do not alone anchor
subsequent operations against root replacement, and source/output close failures
that could release admission/quota despite an unverified live handle. S33's
independent test builder also identified cross-ticket pair publication lacking a
pair-ticket identity check. These are open component findings until fixes and
meaningful fault/regression evidence are reviewed on the frozen source. No
component or design clearance is checkpoint acceptance.

Initial actual focused runs preserved two genuine failures: S30's linked zoom
returned signed negative-zero pan at1x instead of the reset transform; S33's
production owner accepted a foreign pair ticket. UI initial14 cases had13 passes
and1 failure in `/tmp/still-cp10-ui-focused.log`; fixed v2 has14 passes in
`/tmp/still-cp10-ui-focused-v2.log`. S33's initial21 cases had20 passes and1
binding failure, XML `/tmp/still-cp10-s33-evidence/pre-binding-21.xml`.
Final exact-source assertions, binding fixes and old-policy counterfactual evidence
remain required. The adapter's50,000 accessible-metadata-row snapshot limit also
needs explicit UI mapping; selecting two files does not bypass that scope bound.
