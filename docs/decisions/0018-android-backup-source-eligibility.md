# ADR 0018: Android selected-photo backup source eligibility and acquisition

Status: CP21 design proposal only. No implementation, media read, product backup
capability, network, or device result is authorized by accepting this design.

## Context

Architecture sections 7.2 and 10.2 require an immutable authorized source version,
accurate representation/fidelity, and preservation of every required component
before a copy can become a verified backup. CP16 and CP18 demonstrate acquisition
and recovery for generated inventories. CP20 proves a generated-byte Drive wire
protocol. None establishes Android original fidelity or companion completeness.

`MediaItem`, `sourceUiToken`, and `DeviceMedia.cleanupProbe` describe observed
provider rows and namespaces. `AndroidCleanupSourceReader.open` obtains a readable
representation. CP10 stages exactly two readable representations for Compare;
its `PreparedSelectedInput` deliberately is not an original or AssetVersion.
Keep those contracts unchanged. Do not connect them directly to a backup upload.

## Decision

Design a separate, internal Android source-eligibility and acquisition boundary
for exactly one explicitly selected current photo. There is no UI action, automatic
work, remote destination, or transfer. Future implementation requires a separately
authorized task card with named owners and an exact allowed-file set.

### Eligibility precedes reading

Inspection distinguishes readable representation, evidenced original bytes, and
unknown/unsupported fidelity. It separately distinguishes complete, unknown,
partial, and unsupported component inventory. Only evidenced original bytes plus
a complete supported inventory may admit acquisition. Unknown fidelity OR unknown,
partial, unsupported, or changed inventory blocks the entire selection; no usable
partial snapshot is published. A missing companion is not silently optional.

A complete inventory identifies exactly one primary and every required motion,
sidecar, or other supported component, with roles, relationships and source/content
revision evidence. Embedded motion payloads must be retained intact in the primary
or described with explicit lossless boundaries; do not double-count or extract
and replace the primary silently. Formats requiring an unsupported component are
ineligible even when their preview is readable.

No caller-provided `original`, `complete`, MIME, filename, URI, hash, or UI token
may mint eligibility. Trusted adapter evidence must be internally owned,
instance-bound, immutable and bound to the exact selected source, namespace
incarnation, access/lifetime and whole inventory. Do not accept serialized or
structurally copied evidence as authority. Public descriptions are informational;
only an opaque live ticket may acquire or borrow bytes.

`MediaStore.setRequireOriginal` is a candidate documented API to investigate, not
proof of local availability, a stable immutable version, motion/sidecar discovery,
or complete inventory. Its permission and API-level behavior must be documented
and tested before admitting a supported path. Do not add permissions in this
checkpoint. Do not infer original fidelity from `openInputStream`, reported size,
provider generation, image appearance, or a successful readable-stream hash.

The existing generic MediaStore reader therefore remains readable-only with
unknown complete-original eligibility. It must deny before opening media when the
stronger evidence is absent. A provider-specific supported path may be enabled
only after official API guarantees and the relevant device evidence are recorded;
synthetic tests cannot turn that gate green. If no path qualifies, report the
concrete blocker instead of claiming Android source acquisition is complete.

### Proposed internal surface

- An inspection returns a detached fidelity/inventory description and structured
  blockers such as unknown fidelity, unknown/incomplete inventory, unsupported
  source/component, stale source/access, and resource excess. It grants no read
  or backup capability.
- Admission takes the exact current selection and genuine adapter evidence; it
  returns an opaque session ticket only when every required invariant is met.
- Acquisition reads only the internally bound inventory and returns one opaque
  snapshot plus detached component roles, actual byte lengths and SHA-256 values.
  A caller cannot supply component bytes, hashes, roles, paths, or complete flags.
- Borrowing requires the exact session/snapshot/member and current local-source
  authorization. Retention after cancellation may protect allocation ownership;
  it does not preserve read permission. Release, settle and cleanup return distinct
  clean, busy and failed outcomes. Old/copied/foreign handles never regain access.

This is a proposed shape, not a new shared API or a canonical AssetVersion record.
No catalogue migration, portable manifest, CP13 journal, CP15 ledger, or CP20
protocol modification is needed. Do not duplicate those proofs in Android here.

### Proposed implementation bounds

Freeze these conservative first-slice limits before a later build:

- Exactly one selected photo; no video, album, library enumeration or batch.
- One to eight required components, exactly one primary; unsupported or excess
  inventory blocks the whole item rather than truncating it.
- At most 32 MiB actual bytes per component and 64 MiB retained in aggregate,
  including partial, ready, closing and deletion-failed files across instances.
  These are proposed CP21 limits, not claims about all photo formats or originals.
- A single 64 KiB streaming buffer; sequential component processing; share the
  existing process-wide heavy-reader lane through actual source/readback closure.
- A 30-second acquisition deadline. The caller can finish promptly on timeout or
  cancellation while an unconfirmed read/close remains retained and blocks a new
  acquisition until actual settlement. No timeout may imply an I/O handle closed.
- One explicitly versioned app-private staging namespace, no public files or
  source filesystem paths, links, overwrites, recursive deletion, or silent repair.
  File/directory operations must remain descriptor-anchored across path replacement.

Acquisition counts actual reads to full EOF, hashes them and enforces bounds before
writing. A trustworthy declared readable-component length, when available, must
match; MediaStore.SIZE is not such a declaration. Truncation, extra data, empty
required components, malformed reads and failed/suppressed closes deny publication.
At an accepted byte limit, an EOF check may read at most one additional byte; a
returned byte denies acquisition and is never written. Account separately for at
most one such probe per component (eight per attempt). Accepted and retained bytes
stay within the limits; a probe is not hidden progress or an admitted source byte.

Sync and successfully close each staged output, then independently reopen/read it
to EOF and compare actual length/SHA-256. Require successful readback close before
publication. A correct staged hash proves captured bytes, not original fidelity or
inventory completeness. Keep format, embedded metadata and component roles intact;
no decode/re-encode, EXIF rewriting or image transformation is allowed.

Fence access, session, source identity/content revision and the complete inventory
before and after each component and after the entire selection, including early
components that could change while later ones stream. Metadata-only equality is
insufficient for a source capable of changing bytes without advancing that evidence.
The adapter must provide a genuine stable read snapshot or trustworthy byte/inventory
revision guarantees and revalidation; otherwise eligibility remains unknown. Tests
must retain the same-metadata mutation case, not make it pass by updating a revision
in the fixture. Independent staged readback cannot substitute for a source fence.

Publication is all-or-nothing and rechecks current authority at its final boundary.
Lock, foreground/lifetime loss, permission withdrawal, source reset and cancellation
invalidate immediately without waiting for a blocked worker. No late worker can
publish a usable snapshot. Process-wide ownership retains partial/ready/closing
files and failed handles across controller recreation. New orphan maintenance must
not delete a live old worker's files, release its quota or open a competing lane.
Delete only owned staging after all owned I/O settles; failed cleanup blocks new
admission with an honest recovery state. No secure-erasure claim is made.

### Evidence and acceptance separation

A later implementation must map each invariant to evidence:

| Evidence lane | Required coverage | What a pass establishes |
| --- | --- | --- |
| Pure Kotlin and fake-provider tests | Eligibility default denial; forged/copied evidence; complete primary/motion/sidecar roles; changed/missing/extra components; before/after and final fences; same-metadata byte mutation; actual length/hash and full EOF; stale/foreign handles; cancellation and late callbacks | Policy/acquisition behavior at controlled adapter seams, not Android original fidelity |
| Owned temporary-filesystem and fault tests | Disk full, truncation/corruption, sync/close/readback failures including suppressed close; stalled read/close timeout; quota; path replacement and no outside-root effects; blocked old owner, recreation/orphans, retained failed cleanup | The exercised host filesystem/ownership behavior, not Android OEM or power-loss guarantees |
| Android build and regression | Focused new JVM cases, every existing JVM case, debug assembly, strict lint; effective removed-guard counterfactuals with exact patches | Exact source compiles and retains established behavior; no device proof |
| Recorded physical-device matrix | Supported API/provider original-access contract and permissions; selected/full access and withdrawal; original vs location-redacted/transcoded/cloud-backed content; motion/sidecar completeness; edit/replace/reset races; storage and lifecycle behavior | Only the tested supported provider/API/format combinations; untested combinations remain blocked |

No device, media read, network, permission request or credentials are used while
preparing or reviewing this design. Device evidence is not run. Later testing must
use explicitly authorized disposable fixtures, record API/OEM/provider/build and
expected original component digests, and keep private media out of repository,
logs, screenshots and artifacts. Do not call a fixture-only pass a production
eligibility pass. Independent reviewer and separate red-team gates are required
on the exact design and on any later implementation.

## Privacy, migration and rollback

This proposal introduces no stored/transmitted user-data category now. A later
real-media staging task must first name its exact temporary data, retention,
storage location and approval boundary in its task card. Proposed staging holds
selected source bytes only for the bounded local task; no thumbnails, OCR, account
identity, OAuth material, source paths or media content belongs in operational
logs. No data is sent to Drive or a backend. No migration or runtime behavior changes
in this design-only checkpoint; rollback removes these planning documents.

## Later gates before selected-photo backup

Even a successful supported-source acquisition does not make a backup. Separate
checkpoints must establish encrypted durable staging/session recovery with Android
Keystore/key-loss behavior, durable cross-process source/component duplicate fencing,
a real constrained Drive adapter with same-session ambiguity reconciliation and
object revision/readback verification, exportable complete manifests, and restore
after source absence. Configured OAuth project/client/signing and explicit account
consent plus physical-device acceptance remain independently required. CP19 quota
or account access and CP20 generated protocol success do not satisfy these gates.

No backup action/status, Backed up claim, live provider call, upload/download,
automatic work, remote folder, restore capability, source removal, deletion or
free-space eligibility is added or authorized by CP21 design acceptance.

## References

- [Architecture identity and fidelity](../../architecture.md#72-identity-and-deduplication)
- [Architecture backup and recovery](../../architecture.md#10-backup-and-recovery)
- [CP10 readable selected-input staging](0008-selected-input-comparison.md)
- [CP16 generated snapshot proof](0014-bounded-source-snapshot-proof.md)
- [CP18 generated composition proof](0015-composed-backup-pipeline-proof.md)
- [CP20 generated Drive protocol](0017-drive-resumable-protocol-proof.md)
- Android original-access and component API guarantees remain required research;
  no unperformed API/device investigation is recorded as verified here.
