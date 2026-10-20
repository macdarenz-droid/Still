# ADR0010 — Local incremental similarity index

Status: CP12 Stage 2 / R02 design after accepted CP11.

CP11 proves a bounded user-selected appearance review. Continue toward F11 by
letting the owner explicitly build a local index of the currently accessible
photo library, then search selected photos against indexed photos across dates.
This ADR authorizes a rebuildable, derived on-device analysis category consistent
with architecture sections7–9. It does not authorize a cloud transfer or a scan
on app launch, permission grant, gallery refresh, or selected-photo action.

## Stored information and lifecycle

Persist only the versioned perceptual hash, quantized color/layout descriptor,
aspect ratio, a non-content source identity digest, a source-revision marker and
pipeline version. A feature row means that one source revision was successfully
analyzed. Unsupported, low-information, unreadable, oversized and unfinished
outcomes are not persisted; a later explicit user run may try them again. This
avoids retaining per-photo failure history and never treats skipped files as
indexed. Do not persist a filename, display name, album, raw content URI/path,
provider handle, media bytes, original SHA-256, thumbnail, capture time, failure
reason or candidate-pair history. Keep the store in a dedicated application-private
SQLite database; the current manifest excludes every database from Android backup
and CP12 must verify that remains true. No network request or new permission is
introduced.

The namespace key is a SHA-256 digest over length-framed provider authority and
volume. Store a digest of provider version, a generation watermark when available
and a random opaque namespace incarnation. A first observation, provider-version
change or detected provider-generation rollback rotates that incarnation and
purges the old namespace's features. The opaque source key is SHA-256 over
length-framed provider authority, volume, a provider-version presence marker and
value, namespace incarnation, media kind, row handle and birth
marker. Prefer MediaStore generation-added for birth; on API 26–28 use date-added
as a best-effort fallback and keep a best-effort local incarnation when provider
version/generation signals are unavailable. Only digests are stored. The row
revision uses MediaStore generation-modified when available and a best-effort
metadata revision on older APIs. Changed revision or pipeline is a cache miss.
Absent namespaces in a complete current volume snapshot are removed before
results can be exposed. The index is never canonical AssetVersion identity,
immutable byte proof, backup verification, or deletion authority.

On every user-started indexing run, obtain one complete current authorized
MediaStore snapshot, bounded by the existing50,000-row limit. Only current static
JPEG/PNG photos are eligible. Stop, lock, lifecycle loss, cancellation, failed
physical close, stale provider generations, or a final source/scope mismatch
prevents the affected page from being committed. Already committed rows remain
rebuildable but are eligible for retrieval only when their opaque source key,
revision, namespace version and pipeline all match the fresh current snapshot.
At each fresh scope reconciliation, prune feature rows for sources no longer in
the authorized snapshot. A permission-scope reduction therefore removes hidden
feature data; permission loss never means the original was deleted. An empty
version-0 database may be initialized only when both its application ID and user
object set are empty. The opened store then carries a fixed application ID, exact
versioned DDL and app/schema markers; unknown databases and mismatches fail closed
without destructive repair.

All reads share the existing nonqueueing `cleanupProcessReads` lane. Each user tap
is capped at200 source reads per page,16MiB per representation and256MiB total
encoded bytes. Commit only a page whose every attempted source has closed and whose
provider scope remains current. Show indexed/current/skipped/unattempted counts.
Further progress requires a new user action; process restart does not auto-resume.
The screen stays in the foreground; no WorkManager, service, scheduled scan or
background read is added. Clear deletes only index rows, never the catalogue,
Favorites, protection choices, Cleanup journal or media files.

## Retrieval and user interface

The user starts indexing through an explicit Settings action with a short
explanation that Still stores visual descriptors on this phone, reads only current
authorized static photos, and can be stopped or cleared. Provide Start/Continue,
Stop and Clear actions. No scan starts merely because a photo was selected or the
similarity screen opened. If the app is locked, stopped, access is reduced or the
provider changes, withdraw visible results immediately and reconcile or pause the
index before resuming.

`Find similar` remains a selected-photo action. Fresh selected features query
only the current authorized indexed set using a bounded versioned candidate
retrieval index; selected sources are excluded from their own results. Return
direct candidate pairs with actual current filenames/dates and omissions. There is
no transitive grouping, duplicate certainty, quality winner, keeper/extras,
protection, deletion or backup action. Distances are retrieval signals, not
confidence percentages. Compare independently uses the existing CP10 fresh
pair-preparation flow.

The candidate retrieval index must preserve every pair within the declared
perceptual hash threshold or report a measured retrieval budget with explicit
omission; evaluator fixtures freeze before thresholds or retrieval behavior are
observed. Keep synthetic pixel evidence distinct from native codec/provider and
real-library retrieval acceptance. Meaningful document, expression, crop and
state changes can still collide. No broad quality claim is permitted.

## Acceptance and rollback

Preserve all prior tests/assertions and the CP11 selected-review path. Add tests
against the actual store/schema and production retrieval path: fresh reopen,
unknown/foreign schema refusal without mutation, deterministic source-key framing,
revision/pipeline invalidation, scope pruning, permission contraction, concurrent
clear/write, interrupted page commit, failed close, cancellation, process restart,
resource caps, candidate recall/false positives and omitted-work accounting.
Every material defect needs a retained failing old-policy proof and fixed rerun.
Reviewer and separate red team inspect the exact design, source, local APK and
hosted checks before CP12 acceptance.

The index is rebuildable, low-detail visual metadata. Clear removes its database
rows. Rollback keeps app media, metadata catalogue, protection store and CP9 journal
unchanged. No schema migration touches those stores. Android backup exclusions
remain in effect. Device generation behavior, provider revocation, actual library
runtime, battery/thermal cost, process death, codec/EXIF and accessibility require
identified hardware; host checks do not establish them. No main merge, production
signing, new provider, spending or publication is included.
