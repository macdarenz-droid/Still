# Still development control plane

Requires Node 24. From the repository root:

```sh
npm ci
npm run typecheck
npm test
npm run start:api
```

The API listens on `127.0.0.1:8787`. `PORT` changes its port.
`STILL_DB_PATH` changes the SQLite file (default `.local/still.sqlite`).
Back up the database with SQLite's backup tooling or copy it **after stopping the
service**, including any WAL files if present. Schema 1 is in
`db/migrations/001_control_plane.sql`; newer schemas are rejected. Production
PostgreSQL, remote authentication, media storage and provider workers are future
integrations. This service is for local development; do not expose it publicly.

Public routes:

| Method | Route | Result |
| --- | --- | --- |
| GET | `/health` | `{ "status": "ok", "schemaVersion": 1 }` |
| GET | `/v1/capabilities` | Local gallery needs no account; all four cloud providers are unavailable |

Private routes require `Authorization: Bearer <token>`. Configure a random token
of at least 32 characters in `STILL_API_TOKEN` through your shell or secret manager.
`STILL_OWNER_ID` selects the fixed local owner, default `local-development`.
Request bodies cannot select an owner. With no token, private routes return 503
`authentication_unconfigured`; local gallery use remains independent.

For an ephemeral local token without printing it:

```sh
export STILL_API_TOKEN="$(node -e 'process.stdout.write(require("node:crypto").randomBytes(32).toString("hex"))')"
npm run start:api
```

Keep the same environment in the client shell; avoid logging the token or adding
it to repository files. This token is development authentication, not a product
account or production login flow.

| Method | Route | Result |
| --- | --- | --- |
| GET / PUT | `/v1/consent` | Current consent / revision-checked replacement |
| GET / PUT | `/v1/backup-policy` | Automatic backup policy, off by default |
| GET / POST | `/v1/jobs` | Owner's latest 100 jobs / attempt admission |
| GET | `/v1/jobs/:id` | Owner's job, 404 for another owner |
| POST | `/v1/jobs/:id/cancel` | Revision-checked cancellation |

Writes require JSON (`Content-Type: application/json`), `schemaVersion: 1` and an
opaque `idempotencyKey`. The body limit is 32 KiB; input selection is at most 50
immutable versions. Reusing a key with different data returns 409
`idempotency_conflict`. Replaying the same request returns its original response;
GET the resource for current state. Revision fields protect concurrent changes.
Unknown fields are rejected. IDs accept letters, numbers, `_` and `-`; paths and
URLs are rejected. There is no library catalogue or media-upload endpoint.

Example consent request (records a choice; no upload starts):

```json
{
  "schemaVersion": 1,
  "idempotencyKey": "consent-choice-1",
  "expectedRevision": 0,
  "granted": false,
  "assetIds": [],
  "purposes": [],
  "providerIds": []
}
```

Example job admission request:

```json
{
  "schemaVersion": 1,
  "idempotencyKey": "document-request-1",
  "kind": "document_conversion",
  "inputs": [{ "assetId": "selected-asset", "versionId": "pinned-version" }],
  "consentRevision": 1,
  "policyRevision": 0,
  "budget": { "currency": "USD", "maxMinor": 100 },
  "parameters": { "format": "pdf" }
}
```

`maxMinor` is an integer number of USD cents (1–100,000), never a caller-supplied
estimate. Server admission uses a trusted provider estimate. In this milestone the
request returns 503 `provider_unconfigured`: no job, upload or cost is created.
No provider can be enabled using an environment variable or request body. Test
fixtures exercise the internal provider/source-verification seam without network
calls or generated outputs. Enabling real providers also needs owner-approved
retention, input pinning, pricing, credentials and a validated-output contract.

Cancellation body: `{ "schemaVersion": 1, "idempotencyKey": "cancel-1",
"expectedRevision": 1 }`. Queued cancellation releases its reservation. In-flight
cancellation blocks new stages, retains the reservation until reconciliation and
records actual incurred cost separately. Consent changes invalidate queued and
running snapshots; disabling automatic backup leaves manual jobs alone. Workers
must recheck the lease, inputs and consent before each transmission. Expired
attempts require reconciliation rather than a blind retry. A trusted internal
`reconcileExpiredAttempt` operation accepts a checked provider receipt, current
revision and attempt number; duplicate receipt operations are harmless, and
owner/stale-attempt mismatches fail. It has no HTTP route. Receipts exceeding the
approved budget retain their actual cost and flag `budget_overrun` rather than
hiding incurred usage. No success publication
path exists until real output validation/storage is implemented.

Automatic work additionally requires an adapter to prove current collection
membership, video eligibility and network constraints. That seam defaults to
denial and runs at admission, dispatch and before every transmission. The local
bootstrap does not install it. Internal worker leases contain their owner ID so a
worker uses the same owner boundary for all subsequent checks.

State and fact events commit together in the SQLite outbox. `readEvents(owner,
offset)` is an internal bounded, owner-scoped cursor; consumers must checkpoint
their offsets and deduplicate event IDs. No event webhook or worker endpoint is
public. Events contain only opaque references and state, not prompts or media.

Errors use `{ "error": { "code", "message", "retryable", "scope",
"recoveryAction" } }`. The HTTP adapter does not return internal exception text.
Contracts are in `packages/contracts/src/index.ts`; native Android currently uses
its local media access independently of this optional backend.

## Internal cleanup planning

`services/workflows/src/cleanup-plans.ts` is a separate development module with
contracts in `packages/contracts/src/cleanup.ts`. Normal API startup does not load
it or create its database. There is no cleanup HTTP route, worker, provider or
Android bridge. Production PostgreSQL and native trash/restore remain future work.

Construct `CleanupPlans` with an explicit, separate SQLite path. The cleanup
journal has its own application identity and schema1 in
`db/cleanup/001_journal.sql`; it must never share `STILL_DB_PATH`. Both modules
reject the other's identified database before writable setup. The cleanup module
rejects unsupported schemas and invalid records without resetting or repairing
them. Stop journal users and preserve the database and any WAL files before
rollback; recovery history must not be discarded to open an older build.

The module supports creating, reading, listing, revalidating, confirming and
cancelling plans, recording item uncertainty and reconciling verified no-change.
Commands contain strict opaque asset/version/source references, expected source
and plan/item revisions, bounded selections and owner-scoped idempotency keys.
The trusted caller supplies the owner outside the command body. Replayed commands
return their original result with `replayed: true`; read the current plan before
making a new decision.

The journal retains at most 1,000 plans, 10,000 command results and 20 uncertainty
entries per item; reaching a limit rejects additions instead of trimming history.
Reading current plans can persist expiry and advance revisions. Time observations
use a durable high-watermark so rolling back the host clock cannot renew an old
review. Use the revisions returned by the latest read for subsequent commands.

A constructor-only trusted evidence adapter must prove exact source binding,
original pinning, current access, protection/Favorites/dependencies and an
independent surviving copy with restore evidence. No adapter is installed by
default. Android catalogue observations and readable-copy matches cannot supply
these proofs. Evidence changes require another review; expiry and unresolved
uncertainty block confirmation, including related replacement plans.
An unresolved item whose physical identity is unknown conservatively blocks that
owner's other plans. Known physical identities stay bound to their exact source/
version/revision; changed identity requires a new source revision and review.

Every plan and item has `executionAvailable: false`, including
`confirmed_nonexecuting`. Confirmation records reviewed intent and grants no file
operation. `reconciled_no_change` records trusted evidence of no change; it does
not mean bytes were restored. Review and evidence deadlines are not OS restore
windows. This metadata journal stores no media bytes, filenames, paths or URLs
and makes no storage-savings claim. See
[ADR0007](../../docs/decisions/0007-cleanup-planning-journal.md) for the recovery
and acceptance boundaries.
