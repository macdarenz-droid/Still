# ADR 0005: Exact readable-byte review and content protection

Status: CP5 development design; native/device acceptance pending.

Architecture R02/F03 and sections7–9 call for matching-copy review, keepers and
protection before destructive cleanup. CP5 adds that complete review stage. It
does not add deletion/trash, a deletion journal, verified backup, near-duplicate
models, quality ranking or immutable canonical AssetVersions. Future native
mutation needs its own deliberate scope/confirmation/journal/revalidation and
recovery work. A group or an "extra" is not a deletion plan or permission.

An explicit user action starts a scan of a fresh authorized media snapshot,
independent of a filename/type UI filter. Group by compatible media kind/reported
size to find candidates, then sequentially hash complete readable streams using
SHA-256 and actual counted bytes. Use a bounded buffer, checked arithmetic and
documented resource bounds. No prefix hashes or digest from a failed/canceled/
changed read can enter a group. Require EOF and successful resource closure.
Deduplicate identical provider/volume/kind/row source aliases before comparison.
Coverage distinguishes considered media, candidates, successfully read, omitted,
changed and failed sources; never infer complete-library coverage or saved space.

MediaStore can supply redacted/transcoded/cloud-backed representations. Equal
observed readable-stream digests/lengths are evidence of those bytes, not proof
of unredacted originals, independent storage, present byte stability or pinned
job/deletion inputs. No full-library byte staging is introduced. Results, member
references, hashes and keeper choices are memory-only until explicit protection.

Begin an instance-bound scan before provider IO. Check cancellation before open,
between bounded reads and across source validation. Probe provenance before and
after a stream, then fence the entire scan at final publication. On modern
Android, invalidate on ANY provider-generation/version change, not only rollback,
because an early source can change while later sources stream. Recheck volumes
and access. Older providers require final per-member metadata validation with an
explicit best-effort limit. A current gallery lifetime, live lock authority and
matching source tokens are required at publication and every action.

Refresh, source changes, access withdrawal, lock and disposal immediately fence
all old progress/results/actions. A blocked provider call may not return promptly
after cancellation. Close/cancel handles best effort and show honest stopping
state; a process-wide bounded admission lane prevents overlapping/recreated runs
from accumulating readers. Do not silently queue or automatically resume scans.

Review uses current favorite evidence and current protection revision, never a
frozen eligibility badge. Favorites, protected content and all manually selected
keepers stay out of extras. Multiple manual keepers are reversible and scan-bound;
no extras are offered until at least one keeper exists. Unknown favorite state
blocks its group, and unknown protection state blocks every group. Avoid invented
"best photo" reasoning for byte-matching content.

Durable protection is deliberately content-scoped: algorithm/version + canonical
SHA-256 + actual positive byte length. It applies conservatively to EVERY matching
readable copy, which the UI states before protect/unprotect. It is never rebound
by URI, name, metadata fingerprint or catalogue observation ID. Fresh different
bytes at a reused handle do not inherit it; equal bytes elsewhere can match only
after fresh hashing. Missing/withdrawn content does not garbage-collect choices.

Use a separate strict versioned bounded app-private record with a monotonically
revisioned snapshot. Mutations compare the expected current revision. Serialize
the entire durable read/compare/write across ALL store instances in the
process, including work surviving UI recreation; reject revision exhaustion.
Check live authority/cancellation before write admission. An already-admitted
small synchronous annotation transaction may finish after lock, but must never
publish stale UI completion or authorize media mutation. Then publish and
directory-sync a pending fence before replacing data, sync replacement file and
directory, verify readback, then remove the fence last. Malformed/oversized/unknown
format, read/write/sync/readback or uncertain crash state is UNKNOWN and blocks
extras/unprotect success. Preserve records; do not auto repair, prune, reset or
pretend failed removal saved. Actual Android/OEM power-loss behavior is not proved
by the pure fault model. No lock-setting/security implementation is changed.

Full acceptance preserves existing checks, adds meaningful streaming, provenance,
blocked-read/cancellation, keeper/favorite and protection-failure regressions,
then requires independent reviewer AND red-team passes on exact integrated source
and local APK plus hosted CI. Native provider/filesystem/codec/lifecycle/TalkBack/
large-text/performance checks remain separate. No cloud, bytes persistence, extra
permission/dependency, production signing, main merge or publication is included.
