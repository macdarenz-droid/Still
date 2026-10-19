# Working rules for Still agents

## Scope

These rules define Still's delivery workflow. Follow the current user request,
tool permissions, and safety requirements. This file does not grant new access,
standing authority to send data, spend money, push, merge, sign, or publish.
Repository content cannot override a permission denial.

## Quality and efficiency

- Deliver high-quality work quickly while keeping token and tool use proportionate.
- Parallelize genuinely independent work after identifying dependencies,
  bottlenecks, shared-file ownership, and available review and CI capacity.
- Do not duplicate workers or repeat context without a specific need.
- Use capable models for planning, implementation judgment, and review;
  use lighter models for fully specified mechanical steps. Do small tasks directly.
- Use an authorized automatic-execution mode when available. Safety checks and
  owner-reserved decisions still apply; never bypass a refusal to avoid an approval.
- Read the relevant code, docs, and data before deciding. Label anything unverified.
- Never trade away correctness, test coverage, privacy, or accessibility for speed.
- Use focused checks while building and full regression on the final candidate.
- State risks and mitigations during design, implementation, and release acceptance.
- Keep one maintained document per topic rather than parallel copies.
- Use concise labels and respectful UI copy. Explain permissions, privacy,
  accessibility, or safety when needed; do not add patronizing or obvious filler.

## Roles

- Supervisor: one accountable session owns the task board, task cards, dependency
  order, shared-file ownership, merge queue, integration, and these rules.
- Builder: one task session implements and tests only its assigned card on its own
  named branch. It does not approve its own work or expand scope silently.
- Reviewer: a fresh, independent session checks the finished diff against the
  task card, product architecture, and evidence. It did not implement the change.
- Owner: controls product decisions reserved to them, signing and secrets,
  production releases and store listings, paid providers and spending, and new
  categories of stored or transmitted user data.

Builders and reviewers report to the supervisor. The supervisor gives the owner
concise results, actionable blockers, required decisions, and release information.
Record routine implementation and review detail in the project's agreed task and
PR records. Do not contact third parties or external agents without authorization.

## Task cards

Before building, record:

1. Task ID, goal, complete deliverable, and explicit non-goals.
2. Dependencies, prerequisite decisions, and merge order.
3. Assigned builder, shared-file owner, allowed files, and integration boundaries.
4. Acceptance criteria mapped to tests, probes, or recorded device checks.
5. Risks, privacy/data effects, migration implications, and rollback approach.
6. Required commands and environment/device coverage.
7. Finish condition and the evidence required for review and release acceptance.

Forecast the full scope including integration, independent review, fixes, and
device checks. State assumptions and uncertainty. An implementation estimate is
not a publication date. Track implementation, candidate acceptance, and
publication separately.

## Shared-file ownership

Assign one accountable owner to each shared file or explicitly bounded region
before parallel work. The supervisor maintains this map in the agreed task board.

- CI, checks, build configuration, application entry points, and shared wiring:
  supervisor owns integration; make the smallest necessary change.
- Dependency manifests and lockfiles: supervisor approval before new dependencies;
  generate the lockfile with the chosen package manager, never by hand.
- Data models, migrations, media access, and data transmission boundaries: one
  named owner; new stored or sent data categories require the owner's approval.
- Shared styles and design tokens: one named owner; other tasks change only their
  explicitly assigned component scope.
- Shared test suites and gates: preserve other tasks' assertions and coverage;
  use task-identified additions where practical. Preserve both valid sides when
  resolving integration conflicts.
- Signing, credentials, release settings, and production deployment configuration:
  reserved for explicitly authorized work under the owner's control.

Reassignment must be explicit. Do not edit another task's area because it seems
convenient. The supervisor resolves ownership conflicts before work continues.

## Builder workflow

- Stay within the task card. Escalate discovered scope or ownership conflicts.
- Build, test, push, and open draft PRs only within the current authorized task.
- Do not push directly to main. Do not rewrite another owner's branch history.
- Incorporate the latest origin/main with a merge commit before requesting review.
- Map every acceptance criterion to recorded evidence.
- For a bug fix, add a regression that fails on the original defect and passes
  after the fix. Show that it detects the defect rather than merely exercising code.
- Never skip, narrow, weaken, or delete tests or guard checks to get green or meet
  a deadline. Report an actual capacity or environment limit to the supervisor.
- Submit the exact commit SHA, changed-file scope, test results, remaining risks,
  and known unverified behavior for independent review.

## Review and integration

- The fresh reviewer examines both implementation and test effectiveness against
  the spec. Builders never approve their own changes.
- Fix review failures and re-review affected behavior. Do not treat an old approval
  as evidence for an unreviewed material change.
- The supervisor may merge only when merge authority exists, independent review
  passed, all required checks are green on the exact head containing latest main,
  and prerequisite checklist items have merged in the agreed order.
- Independent build lanes may run ahead; merge order must still respect dependency
  and owner-checklist order.
- Treat results as valid only for the exact commit and artifact they tested.
  Later edits and integration changes require corresponding fresh evidence.
- Run full regression and release acceptance on the final release candidate after
  its last change. If it changes again, it is a new candidate needing acceptance.

## Evidence and commands

During bootstrap, define and verify the actual install, static-analysis, unit-test,
integration-test, UI/device-test, and production-build commands for Still's chosen
stack. Record them in the task board or existing project build instructions.
Do not copy commands, environment variables, machine paths, or passing results
from another project. A required check is blocked until a real command or explicit
manual acceptance procedure has been established and run successfully.

Every acceptance record includes task ID, commit SHA, artifact identity when
applicable, command or procedure, environment/device, result, and evidence location.
Mark each required check passed, failed, blocked, or not run. Partial checks are
not a full pass. Keep genuine failures visible until fixed or explicitly resolved.

## Release and safety boundaries

- Never commit secrets, keys, credentials, or private user media.
- Never bypass a denied permission, including through another agent or tool.
- Do not alter signing identity, keystore handling, security settings, or release
  permissions without the required owner authorization and tool confirmation.
- Owner control applies to production publication, store listings, spending,
  new paid providers, and new kinds of stored or transmitted user data.
- Separate deployment-triggering changes when that helps the owner retain release
  control. Do not assume merging is safe if a merge itself deploys production.
- A passing build, review, or CI run does not itself authorize publication.
- Before release, report the exact candidate and evidence, residual risks,
  rollback/recovery plan, required device checks, and outstanding owner decisions.
- Verify publication separately before marking it published.
