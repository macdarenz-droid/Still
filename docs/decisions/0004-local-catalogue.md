# ADR 0004: Local metadata catalogue and gallery controls

Status: CP4 development design; Android/device acceptance remains pending.

Architecture sections 7–8 require a rebuildable on-phone catalogue before cleanup,
backup and creation can depend on media identity. Store already-read gallery
metadata in platform SQLite with opaque local source/observation IDs and metadata
revisions. Use versioned schema SQL shared with real host-SQLite probes. No runtime
or test dependency is added. Keep the existing favorites preference store intact;
this checkpoint does not migrate annotations by URI or add deletion/protection.
When concrete-volume discovery changes a URI spelling, preserve older Favorites
through fresh, unambiguous provider-resolved alias evidence in memory. Keep raw
saved references intact; ambiguous/unavailable references stay saved with an
honest notice. No guessed volume binding, invisible metadata exposure or automatic
reference rewrite is permitted. Explicit unfavorite targets proven matching keys.

These IDs identify local observations, not pinned byte content. Provider, storage
volume, database incarnation and row provenance must participate in continuity;
filename equality is never identity. Prefer platform generation/version evidence
where available. Older-platform metadata continuity is explicitly best effort.
A metadata fingerprint is neither a content hash nor an immutable AssetVersion.
No cloud job, backup verification or destructive operation may use these records
as proof of current access, equality, durable content or deletion authorization.

Fresh authorized MediaStore reads remain the only source of visible media.
The index cannot resurrect cached photos after access withdrawal or substitute
for a failed provider read. A source absent from a partial, denied or failed scan
is not proven deleted. Retained metadata records carry no current-access claim.
An index write failure must remain visible/retryable while authorized browsing
may continue; it must not be reported as successful indexing.

Begin an instance-bound scan token before reading the provider. Invalidation is
immediate and nonblocking; recheck through reconciliation, before commit and
before publication. Detected stale transactions roll back. An invalidation during
the final SQLite disk-commit window may leave retained metadata, but can never
publish success or make it visible as currently authorized media. This policy
avoids blocking the main thread behind a large-library transaction. Querying and
indexing run on IO, inside the unlocked gallery lifetime.

Use SQLite syntax supported by the minimum Android platform. No automatic drop,
delete-and-recreate corruption handler or destructive upgrade/downgrade is allowed.
Schema mismatch/corruption is surfaced with existing data retained. App-private
metadata shares the existing backup exclusions; no media bytes, thumbnails, OCR,
biometrics, network transmission, account or analytics data is added.

The same checkpoint adds All/Photos/Videos filtering, newest/oldest/name sorting
with stable ties, and explicit accessible video Play/Pause controls. Name order
must remain global rather than being rearranged by month sections. Controls follow
the actual player state, avoid autoplay, retain seeking and stop/pause correctly
on dismissal/backgrounding. Existing lock, permission, favorites and sharing
behavior remains covered by the required checks.

Acceptance combines pure-policy JVM tests, real host-SQLite schema/transaction/
restart probes, Android build/strict lint and the existing backend/site checks,
followed by separate independent reviewer and red-team gates on the exact source
and local APK. Host SQLite does not establish Android/OEM database behavior or
MediaStore/codec/lifecycle/accessibility/performance acceptance. All unrun native
procedures stay unverified in the device matrix. No production signing, release,
main merge, provider connection or public-site publication is part of CP4.
