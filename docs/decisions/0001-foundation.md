# 0001 — Android-first local gallery and development control plane

Status: accepted for bootstrap, 2026-10-19. Android native was explicitly confirmed
by the owner. Native media access stays on the device; no API account is needed to
browse photos. Kotlin/Compose implements the first client.

The Node 24 TypeScript control plane uses built-in SQLite for durable local workflow
proofs and zero runtime dependencies. PostgreSQL and a deployable production
authorization system remain production work. The dev API is loopback-bound and
token-gated; do not expose it as an Internet service. Cloud capability admission
must report missing providers honestly. Opaque selected-input references, consent
revisions, budgets and task receipts are the only backend data in this milestone.

The static discovery page is an unpublished preview. It must not promise unbuilt
features, collect visitor details or imply a download exists.

No cleanup execution, real backup, paid provider calls, production release, new
analytics or additional sensitive data collection is included. These boundaries
allow useful local development while retaining the complete product plan.
