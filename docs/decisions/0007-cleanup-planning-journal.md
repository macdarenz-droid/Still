# ADR0007 — Cleanup planning and recovery journal

Status: architecture continuation CP9, after the accepted CP8 ribbon repair.

Architecture sections7.2,9.3 and19.1 place exact source/version contracts and a
durable per-item journal before native removal. Current Android catalogue records
are observations; readable-stream matches are not pinned immutable originals.
Keep that boundary explicit while implementing the next shared foundation.

Add an internal local-development TypeScript planner and dedicated SQLite journal.
No HTTP/native/provider integration or media mutation is part of this checkpoint.
The Android app remains independent of the development control plane. Production
PostgreSQL and actual source pinning remain separate integrations.

## Contracts and authority

Commands contain schema version, owner-scoped idempotency key, opaque asset/version/
source references with expected source revisions, bounded target/keeper selections,
and expiry/revision expectations. The owner comes from the trusted caller, never
the command body. Reject paths, URLs, extra fields, duplicate/aliased selections,
unsafe numbers and caller-supplied evidence. Freeze the complete reviewed intent;
confirmation cannot broaden it or silently bind newer evidence.

A constructor-only trusted evidence adapter must establish owner/ref/revision,
pinning and representation fidelity, current access, protection/Favorites,
keeper/dependency exclusions and required independent-copy/restore facts. Missing,
unknown, expired, malformed or changed evidence blocks the review. Its opaque proof
IDs and revisions are metadata, not a capability token. Default installation has
no adapter and denies readiness. Do not equate a readable hash or catalogue row
with a pinned original. No media bytes, URIs, prompts, secrets or staging are stored.

Every plan/item remains `executionAvailable: false`. Confirmation records reviewed
intent only, with no lease, dispatcher, API route, native call or executor. No state
may claim deletion, trash, restored bytes or recovered storage. Plans expose only
honest blocker codes and scoped metadata; clients own human action labels later.

## Journal and recovery

Use a separate explicitly supplied development database path, schema1 and a distinct
application identity. Do not upgrade or reuse the existing control-plane schema1.
Check identity/version/expected schema before writable PRAGMAs or schema changes;
initialize only a genuinely empty database. Foreign, corrupt, unsupported or
malformed journals fail closed without drop, repair, reset, pruning or mutation.
Valid uncertainty records stay retained and change only through guarded commands.
SQLite itself may create WAL coordination sidecars during a read-only open;
preflight performs no writable PRAGMAs, schema changes or record repair.
Reject foreign triggers/views/indexes and shape-valid but impossible stored states;
an application ID and parseable JSON alone do not establish a supported journal.
The existing ControlPlane gets only a nonzero foreign-application-ID rejection
before its writable PRAGMAs, so the two databases reject reverse path mix-ups too.

Plan/item revisions, per-owner command fingerprints/results and state transitions
commit atomically. Validate complete evidence immediately before confirmation and
compare against the reviewed snapshot; changed facts require a new review rather
than approval of unseen changes. Expired/cancelled/uncertain intent cannot revive.
Idempotency replay is an original result, not fresh current authorization; callers
must read/revalidate current state. Never turn stale replay into executable work.

Uncertainty persists per item across process/reopen and blocks the whole plan.
It also fences replacement plans for the same exact owner/source/version/revision
and trusted physical aliases. A new plan or different opaque alias cannot erase
an unresolved outcome. Required surviving-copy/keeper evidence excludes the entire
selected target set and physical aliases, preventing circular A-preserves-B and
B-preserves-A reasoning. Opaque distinct IDs alone do not prove independence.
An unresolved unknown physical identity conservatively blocks that owner's other
plans. Once known, physical identity cannot be cleared or rebound under the same
exact source/version/revision, including after resolution or read-generated expiry.
Reconciliation consults the trusted adapter and can only establish verified
no-change; caller-provided outcomes and blind destructive retries are absent.
Clock rollback, failed transactions, concurrent instances and interrupted writes
must not yield a false success or partial reviewed intent. SQLite tests establish
local host behavior, not phone/filesystem power-loss or native mutation guarantees.

## Acceptance and rollback

Preserve all27 backend cases,159 Android cases and existing SQL/discovery/CI guards.
Add real contract and SQLite reopen/fault/ownership/evidence/expiry/idempotency/
concurrency/uncertainty cases that detect unsafe original/counterfactual behavior.
Independent reviewer and red team must approve the exact source/local APK and
each hosted gate. Native trash/restore requires a later adapter, journal-before-call,
immediate source revalidation, OS consent/capability handling and device evidence.

No new dependency, permission, provider, spending, media staging, Android storage
category, signing or public endpoint. The new database stores only architecture-
specified plan/journal metadata when an internal developer explicitly uses it;
normal app/API startup does not create it. Stop users of the journal and retain its
database/WAL before rolling back; never discard recovery records to make old code
open them. Existing control-plane and phone data remain on their prior schemas.
