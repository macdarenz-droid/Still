# ADR0015 — Composed synthetic backup pipeline proof

Status: CP18 design; synthetic composition only, with no provider or product capability.

CP13 verifies and restores generated components in its synthetic journal. CP15
models account-bound asynchronous transfer identities and acknowledgements. CP16
captures a coherent generated source snapshot into private byte buffers. Passing
their tests separately does not establish that one component inventory and the
same bytes survive the complete sequence.

## Decision

Add a fixture-only composition harness for one generated asset containing one
primary component, an optional motion component, and an optional sidecar. The
accepted scenario includes all three components so the complete inventory has
meaningful lineage. One opaque source/version/component-set identity drives
acquisition, transfer binding, verification, and restore.

The pipeline may bridge the existing test modules only through unforgeable
in-process fixture authority. A copied handle, exported descriptor, caller hash,
or fixture metadata must not provide byte access. A narrow borrow operation reads
only the exact immutable bytes retained by an accepted CP16 snapshot; it returns
them to the composed harness under a private synthetic authority and does not
export them as a product API. Bytes presented to the transfer fixture are
captured as the actual contents of its generated provider object; acknowledgments
are not treated as integrity evidence.

Keep attempt identity bound to the account epoch and original source/component
revisions. A blocked, cancelled, stale, or uncertain transfer keeps ownership of
the snapshot until the original attempt is reconciled or safely abandoned. A
restart or epoch change never retargets the work. Ambiguous create/append/finalize
results reconcile the same provider effect before proceeding; they cannot mint a
replacement object. The verifier consumes the actual object bytes, rejects
swapped or altered bytes even when offsets and object identity look valid, then
uses CP13's manifest and restore verification path. After verification, remove
the generated source and release the snapshot before restoring all component
bytes and comparing them against the accepted manifest.

## Scope and exclusions

No Android source read, permission, user media, OAuth account, Google API call,
provider adapter, network, scheduler, runtime export, production bootstrap,
route, database migration, dependency, UI, backup toggle, or deletion/free-space
capability is added. Reuse the existing synthetic ledgers; do not alter their
schemas or weaken prior proof boundaries. New composition tests and narrow
fixture-only seams are the sole code scope. No AI wording or user-facing backup
claim is added.

This proof demonstrates generated-byte lineage only. It does not prove Android
component discovery or original fidelity, durable staging, Drive object semantics,
real upload/readback, recovery on a phone, or safe source removal. OAuth setup and
physical-device chooser consent remain independent blockers for the selected
Google Drive flow.

## Required evidence

- Complete generated primary/motion/sidecar inventory feeds acquisition and the
  same component revisions feed transfer and final manifest.
- Detached/foreign/copied handles and altered descriptors cannot borrow bytes.
- Byte lineage is checked per component; swapped contents, wrong contents, wrong
  component identity, missing/extra component, and source mutation fail closed.
- Snapshot ownership remains exclusive while a transfer is blocked or uncertain.
- Cancellation, account-epoch change, close/reopen, and late remote completion
  cannot retarget or publish a second object.
- Ambiguous object creation, append, and finalization reconcile by original
  operation identity; duplicate/replayed intents remain idempotent.
- A valid-offset but altered provider object fails verification.
- After source removal and snapshot release, CP13 restore returns every verified
  component byte-for-byte, preserving primary/motion/sidecar roles and manifest
  lineage.
- Required existing contracts remain intact; focused and full checks pass, and
  independent reviewer and red-team gates pass the exact integrated head.
