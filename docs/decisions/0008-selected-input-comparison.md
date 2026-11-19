# ADR0008 — Selected inputs and photo comparison

Status: CP10 development design, following accepted CP9.

Architecture sections7.2 and19.1 require selected inputs with stable byte identity
before backup and creation. Stage2/R02 also needs useful visual review. Implement
an Android two-photo comparison that explicitly prepares bounded temporary
readable representations. This follows the architecture's selected-task staging
option; it introduces no whole-library copy, automatic preparation or cloud use.
The owner's instruction to continue the architecture authorizes this local task
implementation. Compare is the user's explicit selected-task action in the app.

## Input boundary

Accept exactly two distinct current photo/source tokens selected from the current
authorized gallery view. Recheck live authority, access, provider namespaces and
exact source observations before/after each read and after the whole pair. On
older providers retain the explicit best-effort observation limit. A provider may
return a redacted, transformed or cloud-backed representation. Never call these
bytes an original, canonical AssetVersion or verified backup/deletion input.

Each prepared representation receives a task-scoped opaque identity, actual
positive byte length and SHA-256 after complete EOF and successful source/output
closure. Verify synced published bytes through an independent complete readback.
An immutable task representation describes only the bytes successfully captured;
it does not establish point-in-time source stability or original fidelity. No
prepared ID/proof may feed CP9 readiness. Existing catalogue observations stay
observations, and CP9 remains a separate internal development module.

Use a 64KiB buffer, at most32MiB per input and64MiB for the complete pair, counted
from actual reads rather than metadata. Reject empty, stalled, malformed,
oversized, short/failed/close-failed reads and any sync/readback/source failure.
An exact declared readable-representation length, when available, or the staged
readback establishes a short read; MediaStore's reported original size does not.
Unknown readable length stays unknown while actual reads still enforce quotas.
The current source adapter also enumerates permitted metadata through Cleanup's
50,000-row snapshot cap. Selecting two inputs does not remove that scope limit;
surface it honestly and do not claim measured large-library latency.
All-or-nothing publication: if the second input fails, publish neither and clear
both temporary inputs. One nonqueueing heavy-reader admission is shared with
Cleanup, held through actual provider closure even after cancellation. Staging
ownership also prevents multiple ready/closing sessions from exceeding the quota.

## Retention and filesystem boundary

Store only this explicitly selected pair in an opaque app-private cache namespace,
excluded from backup by the existing application/backup policy. No filenames,
paths, source URIs, hashes, tickets or media go into saved UI state, logs, network,
the catalogue or another durable metadata store. Only the temporary representation
files contain media bytes. No FileProvider/export/share or original-file changes.

The comparison is session-only. Close, ON_STOP, refresh, source/access change,
lock, navigation and composition disposal immediately hide/fence the old session;
finish admitted IO/closure and clean its files on a worker before releasing its
ownership. Do not resume after recreation or process death. Interrupted remnants
are cleared on the next gallery initialization/before new preparation, under the
same process ownership rules; a recreated Activity must not sweep a live worker.
Android may evict cache earlier. Failed deletion is visible and blocks additional
staging rather than allowing retained bytes to accumulate. There is no promise of
secure erasure or power-loss cleanup while the process is absent.

Use a dedicated owned directory, generated names, no symlink following and no
recursive traversal into unknown files. Reject unexpected roots/entries instead
of touching originals or unrelated cache. Separate host filesystem fault evidence
from Android/OEM filesystem and provider verification.

## Native flow

Show Compare for exactly two selected photos; preserve existing Share/Favorites,
Drift, Grid and album actions. Show preparation/cancellation/busy/size/storage/
source-change/cleanup failure states with direct centralized strings. Explain the
temporary64MiB bound and readable-copy scope where useful. Do not add an approval
dialog solely for this reversible action.

The read-only comparison shows two bounded previews and real filenames, responsive
paired panes, linked zoom/pan with buttons plus gestures, Reset and Close. Decode
only prepared files, with sampled pixel/allocation bounds and no new image-loading
dependency or disk/memory cache retaining copies after the session. The decoder
receives only ticket/member-bound staged streams, never paths or source fallback.
Honor orientation and fail honestly for
unsupported decoding; no auto-playing video/animation or quality recommendation.
All essential controls remain reachable with large text and TalkBack. Any motion
is finite and follows reduced motion; withdrawn images have no exit animation.
No keeper/Favorite/share/export/delete/restore action is introduced in this flow.

## Validation and rollback

Preserve all159 Android and74 backend cases/assertions. Add real file/stream/fault
tests for quotas/unknown lengths/EOF/close/sync/readback/cancellation, second-item
failure, symlinks/foreign paths, cleanup failure/orphans/recreation, shared blocked
reader admission and complete-pair current-source publication. Exercise production
UI/coordinator identities and linked-transform bounds, including stale callbacks,
lock/revoke/refresh/disposal and availability after failure. Show meaningful
old-policy failure for any corrected defect; do not weaken existing tests.

Root must run full npm/Android assemble/JVM/strict lint on the final integrated
head, audit actual APK/signature/permissions/backup/assets, obtain separate reviewer
and red-team exact local and hosted passes, update draft PR1 and retain a unique
APK plus `.artifacts/cp10-validation.json`. Real provider/transcoding/low-storage/
filesystem/process-kill/gesture/font/TalkBack/performance evidence remains open
until run on identified hardware. Roll back with app data retained; comparison
cache is disposable, while catalogue/protection/CP9 journals are never reset.
No permission, dependency, schema, paid provider, production signing/publication
or main merge is included, except the supervisor-approved EXIF dependency below.

Strict lint rejects the platform `android.media.ExifInterface` because of known
older-Android security issues. Use pinned stable
`androidx.exifinterface:exifinterface:1.4.2`, verified against Google's Maven
metadata. This is a narrow supervisor-owned dependency exception, with no guard
suppression, image-loading library, new permission or broader build change.
