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

## Outstanding feature and release evidence

- No attached Android device or accelerated emulator: x86_64 worker has no
  /dev/kvm or KVM module. Manual runtime checks are blocked here.
- Real Android device: permission grants/denial/partial access/revocation/reselection,
  external library changes, shared media viewing, sharing, accessibility, rotation,
  background/foreground and large-library performance: **not run**.
- Cloud providers, paid AI, OCR/document output, video render, backup/restore,
  cleanup safety, vault/app lock and store entitlement: **not implemented or tested**.
- Production identity, PostgreSQL, worker deployment, provider configuration,
  release signing and store/public-site publication: **out of this milestone**.
