# ADR0011 — Provider-neutral backup and restore proof

Status: CP13 Stage 2 contract proof only; no storage provider selected.

Architecture §19.1 places selected backup, verification, and restore in Stage 2,
before screenshot organization and search. CP10's readable comparison snapshots
and CP12's appearance descriptors do not identify an immutable original version
or prove that it can be restored. This checkpoint defines and fault-tests the
recovery contract with synthetic bytes only.

## Scope and authority

The proof is an isolated development module. It has no public API route, Android
entry point, production bootstrap, scheduled work, provider adapter, network call,
account flow, real media input, or UI action. Production capability continues to
report `provider_unconfigured`. A test adapter may read and write generated test
bytes in temporary storage; no user photo or phone metadata may enter the tests.

Source identity and fidelity arrive only through an internal trusted verifier.
The verifier defaults to denial. A caller cannot assert that an ID or readable
representation is an immutable original. A portable manifest binds an opaque
source/version/component reference to the precise representation, byte length,
component role, and SHA-256 digest. It carries no path, URI, filename, media bytes,
credential, or cloud URL.

The separate journal uses versioned DDL and revision-checked, idempotent
transitions. It distinguishes requested/preparing/uploading, uploaded-but-
unverified, verified, restore-in-progress, restore-verified, and uncertain or
failed outcomes. Unknown provider outcomes require reconciliation; retries never
blindly create a second object. A restore is verified only after independent
readback matches the manifest's component set, lengths, and digests. Remote object
identity and revision are explicit; a changed revision, source version, or
component set invalidates earlier evidence.

## Explicit exclusions

This ADR does not authorize a Drive, OneDrive, Dropbox, Filen, TeraBox, or other
provider choice; account creation; OAuth registration; credentials; paid plans;
quotas; or transfer of personal photos. It does not implement automatic backup,
media deletion, “Free up space,” cleanup execution, sharing, user-facing backup
controls, or a claim that the phone's originals are protected. Provider choice,
account scopes, current terms, billing, upload semantics, and native integration
remain owner decisions before any real adapter.

A later user-authorized local export can be considered separately and must be
labelled as a local copy. It cannot be called a verified remote backup or authorize
removal of the source. No incomplete test adapter is exposed as a product action.

## Acceptance and rollback

Tests use synthetic byte fixtures and a temporary journal. They cover exact
multi-component restore without the original input remaining available, reopen
and resume, interrupted and duplicate requests, uncertain outcomes, stale object
revisions, truncated/corrupt data, missing components, changed source versions,
identity collisions, denied authority, disk-full and failed-close behavior, and
concurrent expected revisions. A successful fixture result is named test-only
and cannot be consumed as a production backup receipt.

No Android permissions, dependencies, existing SQL schemas, catalogue/cleanup
stores, control-plane routes, or production capabilities change. Rollback removes
the isolated proof module and its test wiring; user media and existing databases
are untouched. Before a provider adapter, the owner must choose the destination
and authorize its concrete account-connect/OAuth scope and credential setup.
