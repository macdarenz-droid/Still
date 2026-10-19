# Still Android development app

Native Kotlin/Compose phone gallery, Android 8.0 (API 26) and later. This is the
development foundation, not a production release. It reads real permitted MediaStore
photos/videos in a layered ribbon or lazy grid, groups the grid by month and album, searches filenames, opens images
with zoom and videos with explicit Play/Pause and platform seek controls, and shares selected media through
Android. Favorites are app-private metadata persisted on this phone, not system
favorites. Original media is never written or deleted.

## Build

Use a full JDK 21 (including `javac`), Android SDK platform 37 and build-tools 36.0.0. Set
`ANDROID_HOME` or put `sdk.dir=/your/android-sdk` in untracked `local.properties`.
The SDK manager's platform package is named `platforms;android-37.0`.

```sh
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest :app:lintDebug
```

The wrapper verifies its Gradle distribution against the official SHA-256 recorded
in `gradle/wrapper/gradle-wrapper.properties`.
The Gradle 9.8.1 wrapper JAR's official SHA-256 is
`3b8a25775a69158b5ad2b1d17a80a88dc7b40a352a73eb27d06c612a7ce68e98`.
The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. Standard local debug
signing is used; no production signing identity or release distribution is set up.

## Drift gallery presentation (S17/S18)

Photos, Favorites and album contents open in a layered ribbon over the current
permitted, filtered and sorted rows. At most five thumbnail cards are composed,
each using the existing 512-pixel preview/cache path. Decorative neighbors have no
accessibility action. The focused row has Open photo/Open video, Previous/Next,
Favorite and Grid view buttons; a long press also starts selection. Navigation is
bounded at the ends. Grid view retains lazy rendering and is the selection surface;
a large library does not require swiping through every row. Search and View options
provide filename search, the existing media-type filter and global sort, selection,
and Ribbon/Grid choice. The current type, sort and view are visible in the header.
Settings remains a labelled action. Short windows and enlarged text use a Menu for
all destinations, leaving space for scrollable content. Actual native layout,
TalkBack and 17,000-item frame/memory behavior are unmeasured.

The date rail contains at most seven actual dates from the current view, with
Previous dates/Next dates, an exact YYYY-MM-DD jump and a Date unavailable target
when that view has unknown dates. Chips expose complete localized spoken dates.
Jumping selects the first matching row in the existing sort; Filename A–Z is never
regrouped by date. Source dates can include Android added-date fallback and are
not presented as verified capture times. Scope, refresh, sort and presentation
changes create an explicit new navigation identity, including equal-looking rows.
Batch actions require their captured current view projection. Rejected ambiguous
projections show a load error rather than entering a grid with duplicate keys.
Captured projection and opaque per-focus checks prevent queued navigation from
retargeting a new row or replacing a later choice, including a return to the same
source. Every date-rail page renews that identity, including paging back to the
same range. Pointer and picker work use that focus identity. Each date-picker opening
has its own single-use request, so cancelled confirmations or dismissals cannot
navigate or close a later opening. A separate token-only position intent survives
loading and exact mode/sort changes; foreground refresh reconciles it through the
existing strong-source continuity policy, then creates fresh interaction identities.
Grid opens align the interaction with the real opened source before later intent
capture. Changed, missing or weakly evidenced rows cannot inherit that intent.
The new Options and Menu overlays use per-opening request identities; cancelled
or replaced callbacks cannot edit or close a later opening. Ongoing edits within
the same active Options dialog intentionally update its view scope. Existing native action gates
still decide whether any media can be opened/shared/favorited.

Ribbon focus uses finite Compose springs; photo opening uses a restrained 420ms
scale/fade. Both run in the existing Compose motion context, which observes Android
animator duration scale. No custom settings reader or perpetual animation is added.
There is no delayed exit: refresh, close, scope change, permission loss and lock
remove the viewer immediately. Each opening has a distinct memory-only identity,
including close/reopen of the same source. Old close/share/Favorite/player callbacks
are denied against a later viewer. Photo zoom, pan, details and opening state use
source provenance plus refresh generation. The native video functions, explicit
Play/Pause, no-autoplay behavior and lifecycle guards are preserved. Native live
scale changes, gestures, focus and playback require the device checks below.

The chosen visual uses ink, warm ivory, coral and serif headings; interface labels
remain Photos/Favorites/actual album names and real filenames/dates, without stock
photographs, invented captions, slogans or inferred memories. No new persistent
setting, data schema, dependency, permission, media decoder or provider IO is added.

## Media and privacy boundary

Android 13+ image/video grants and Android 14+ selected-media grants are handled
separately. Partial access shows only accessible items and offers reselection.
Permissions are reconciled on foregrounding and permission results, including
same-bit selected-set changes. A reduced grant clears the visible viewer/selection
before querying again. Cached thumbnails are memory-only; generation fencing
rejects late decoding from an invalidated access epoch. Revocation changes
visibility, never the recorded existence of an original.

Every reload clears visible rows and legacy Favorite aliases, including unchanged
FULL grants. Only pending source UI tokens may survive for fresh reconciliation.
Grid/viewer rendering and queued open/select/share/favorite callbacks also check
current loading/failure and source-token eligibility. Callbacks are denied while
loading and when the refreshed observed source token differs.
Queued media, permission and settings actions also read the controller's current gallery authority through a
required callback and a one-way, memory-only gallery lifetime. Permission/settings
launches and async result/error publication use the same gate. Disposal ends the
old lifetime permanently; later authentication creates a fresh gallery scope.
Both visible and native player starts independently require current row/loading
eligibility, current gallery authority and a RESUMED lifecycle, rather than a
captured unlocked state. Native callback scheduling remains a device check.

Both the memory cache and Compose image state use a local preview fingerprint
including the source URI, MediaStore modification time, orientation, dimensions,
byte length, provider/volume/birth/revision provenance and requested preview size. Reconciled edits and rotations therefore
replace a same-URI preview. This metadata key is not a content hash, proof of
equality or canonical immutable asset-version identity.

`MediaItem.id` is an Android content URI used only as this client's transient
source/UI key. It is not the architecture's canonical opaque asset or version ID.
The local catalogue has opaque source/observation IDs and metadata revisions;
these also are not pinned immutable AssetVersions. Canonical asset integration
remains future work. Neither URIs nor these observations may authorize backend
jobs, deletion or verified backup.

This build declares no internet or write/delete permission. There are no cloud
transfers, AI providers, analytics, deletion, verified backup or generated
outputs. Those remain in the product plan and are described as future work in
Settings. Favorites and search do not infer anything about inaccessible media.
Android auto-backup is off so local preference data is not silently backed up.

## Gallery controls and local catalogue (S09/S10)

All, Photos and Videos apply to the current library, album, filename search or
Favorites view. Date sorting offers newest/oldest with unknown dates last;
Filename A–Z uses case-insensitive filename order, then original case and source
URI for deterministic ties. Filename order is global, with month headers omitted.
At the album root it orders cards by their first matching filename, rather than
sorting album titles. Changing type/search clears selection, and both sharing
and batch Favorites use only items in the visible view. Sorting retains selection.

Android 29+ reads concrete external volumes; bucket IDs include provider and
volume so identical album IDs cannot merge removable storage with the primary
volume. Android 30+ supplies per-volume provider version/generation and row
added/modified generations. Versions and generation rollback are checked around
the entire enumeration, including successfully queried empty volumes. Android
26–28 uses the legacy external namespace; Android 29 has no row birth generation.
Older-provider continuity is explicitly best effort.

Selection/viewer UI tokens include observed source identity, metadata revision
and a provider generation watermark. A reset, reused handle, edited row or
generation rollback clears the old pending action. Unchanged strongly evidenced
rows can retain it while advancing their watermark; unknown legacy continuity
is cleared on refresh. These local keys do not prove byte identity. Existing
URI-based Favorites preferences remain unchanged; no migration is attempted.
On Android 29+, old aggregate `external` Favorites are matched only after an
authorized query of that exact saved URI identifies the same actual volume,
table, row handle and observed birth as one unambiguous fresh row. Alias proof
queries stay inside the same provider-reset and publication fences. They read
metadata only and run sequentially on IO with cancellation checks; absent or
ambiguous candidates need no query. Cost is linear in eligible legacy references,
so large upgrade sets remain unmeasured device work. Favorites remains loading
until resolution completes. Unmatched references stay saved with a generic
notice, independent of the current type/search filter. Explicit unfavoriting
removes only current and legacy keys proven to match that item.

This ephemeral compatibility proves current Android reference equivalence only.
CP3 Favorites stored no historical birth or byte hash; identity after historical
URI reuse cannot be established retroactively. Android 30+ requires a nonnull
provider birth generation in the legacy proof. API 29 requires a positive
date-added fallback, which remains best effort. Ambiguous/missing proof is never
guessed or rewritten.

The versioned platform SQLite index stores permitted browsing metadata only,
never thumbnails or media bytes. It opens and reconciles on IO inside the
unlocked gallery. A scan token starts before querying MediaStore; refresh,
permission changes and lock disposal invalidate it without waiting for SQLite.
Live authorized rows publish before database persistence, and the index has no
browsing/read fallback. A failed/null provider read clears the live result; an
index failure instead shows an honest Retry notice alongside live browsing.
Retained rows establish neither present access nor existence/deletion. A tiny
commit-window race can retain stale metadata, but cannot report index success or
publish it as visible media. Schema mismatch/corruption is surfaced without
automatic deletion or destructive repair.

Video opens paused with a visible labelled Play/Pause button and native seek
controls. Button state follows both its own and the platform controller's actions,
completion and errors. Seeking after completion retains the user's position.
Background pauses, disposal stops/releases, source revision changes replace the
player, and returning/recreating never automatically resumes playback.

## Optional app lock (S06)

Settings can require Android device authentication before opening the whole
gallery. Enabling and disabling each require fresh authentication. API 28+
uses platform biometrics when available, with a phone-screen-lock fallback;
API 26–27 uses the phone screen lock. This protects access through Still. It
does not encrypt, move or hide the phone's original media from other apps.

An enabled session starts locked after every Activity creation. Unlock results,
attempt identities and session state exist only in memory. Leaving the
foreground, opening a new external entry or turning the screen off invalidates
access. Sharing and Android Settings therefore require authentication on return.
The gallery is absent from composition while locked or authenticating, so its
MediaStore query, thumbnails and video viewer do not run behind the gate. Only
gallery navigation and selection UI state use the saveable registry.

Each external phone-screen-lock launch has a unique result registration capturing
the exact memory-only attempt. Its single expected lifecycle handoff keeps only
that challenge; gallery access stays blocked. Recreated Activities do not restore
the registration or accept old results. Biometric mismatches permit another scan;
terminal errors, cancellation, screen-off and new entry fence stale callbacks.
If the phone screen lock is unavailable, an enabled Still stays locked and offers
Android security settings.

The app lock’s durable data is a strict, versioned local on/off setting and a
pending-write marker. The adapter syncs marker file bytes and its parent directory
before touching the setting. It then syncs the AtomicFile record and replacement
directory entry before verification and marker removal. Directory descriptors
are opened through public Android APIs and checked with `fstat`. No fallible
operation follows marker removal; a crash that restores an unsynced deletion can
conservatively restore the marker and require authentication again. A failed
or interrupted update, malformed record or read error is treated as locked; a
marker remains until an authenticated setting update succeeds. A failed enable
is explicitly reported as unsaved, never as enabled. This protocol has JVM fault
coverage, including a model separating volatile file visibility from durable
directory entries. Actual power-loss durability and platform/OEM behavior remain
device checks.

The owner requested screen capture in CP7 to show the app. Still allows screenshots,
screen recordings and ordinary Recents previews, including when app lock is on.
App authentication and relocking still apply. Still does not start, save or upload
recordings. Android can separately protect its system authentication screens.
Actual screenshot/recording/Recents behavior needs hardware checks.
No custom PIN, credential storage, vault or encryption is implemented.

## Exact readable-byte review (S13/S14)

Cleanup starts only after an explicit Start scan. It requests a fresh authorized
photo/video snapshot, independent of gallery filters/search, and requires its
observed source tokens to match the current live gallery mapping. A mismatch
invalidates the scan and refreshes the gallery for a manual retry. Open member
uses the ordinary, currently eligible gallery viewer; there is no index or stale
review fallback. Current Favorites use full saved-reference and exact alias-proof
evidence. An unresolved old reference that could name a member makes that member's
Favorite state unknown and blocks its group's extra-copy review.

The prefilter considers same media kind and positive reported-size buckets with
at least two sources. Unknown sizes and singletons are omitted, so completion
never means every accessible item was byte-compared. Each candidate stream is
read sequentially to EOF with SHA-256 and an actual checked byte count. A positive
length and successful close are required before evidence enters a group. Failed,
empty, changed, canceled, over-limit or close-failed reads create no evidence.
Coverage separates considered/candidate/checked/compared/omitted/empty/unavailable/
changed counts and excluded repeated source aliases. Equal hashes and lengths
refer only to the representations Android supplied. Android may redact metadata,
transcode or fetch cloud-backed media; this does not prove unredacted originals,
independent storage, immutable versions or current deletion eligibility.

Resource bounds are one process-wide **nonqueueing** read lane, one open media
stream and **64 KiB** buffer per reader, **50,000** snapshot rows and **16 GiB**
per readable stream. A larger snapshot aborts honestly; an oversized stream is
excluded without a prefix hash. Scan results and digests stay memory-only unless the user explicitly saves a
content protection choice. No media bytes or previews are persisted; no hashes
are logged or transmitted. Start while an old blocked provider reader is still
unwinding returns Busy, rather than queueing or resuming work. Stop fences results
immediately and shows Stopping until the actual reader exits and closes; provider
calls may delay that exit. Worker completion acknowledges Stop even when its
default-dispatched coroutine was canceled before the body began. Lock, refresh, permission changes, Favorites mutation
and gallery disposal invalidate scan results/actions. New scopes never restore
scan tokens, keepers or old completion callbacks.

Source metadata is probed before and after each read. The entire scan rechecks
access, volumes and namespaces, plus all successful members at the end. On Android
30+ ANY provider generation/version change rejects the scan, including ordinary
generation advance after an early member was read. Older platforms' final member
checks are explicitly best effort; equal old metadata cannot pin concurrent bytes.
Publication and every keeper/open/protection action require the current scan,
live gallery authority, source mapping and lifetime. Members are individual lazy
UI items, including a single large group, but device latency/memory/font behavior
is unmeasured.

Keepers are manual, multiple, reversible and memory-only for that scan. Favorites,
all manually kept members and protected matching content remain keepers. No extras
are shown without a keeper; unknown Favorites or protection prevent the affected
review decisions. An extra is a reversible review choice, never permission to
remove media. There is no quality ranking, automatic keeper, deletion/trash,
savings estimate or native mutation in this checkpoint.

Protect/unprotect confirmations are single-use and bind both the exact visible
request and opaque current review identity. Cancel, reopening identical values,
equal-looking later results and disposal cannot retarget a retained confirmation.
Protect/unprotect is explicitly confirmed for ALL matching readable content,
including a later copy only after fresh hashing. The separate local record stores
only algorithm/version + SHA-256 + actual positive byte count, not URI/name/source
identity. Fresh different bytes at a reused URI cannot inherit protection. Missing
media does not prune saved choices. The store supports **4,096 content keys** in
**512 KiB**, expected-revision writes, pending/file/directory sync/readback fences
and no silent corruption/newer-format/uncertain-write recovery. Capacity rejection
retains existing choices and cannot report saved protection. UNKNOWN blocks extras;
a retry rereads the record without promising repair. Separate instance-bound
annotation request fences prevent delayed READY loads or saves from overwriting
newer UNKNOWN/revision state. Writes recheck live authorization/cancellation inside
the shared store coordinator immediately before admission; an already-admitted tiny
annotation replacement may finish after lock, but no stale UI completion publishes.
Filesystem/power-loss behavior remains a device check. This preference does not
lock files or stop another app changing or deleting them.

## Verification limits

Unit tests exercise API-specific permission policy, mixed-media sharing,
selection visibility, late-thumbnail invalidation, lock-policy transitions and
setting-write failures, filter/sort order and provenance/UI continuity. The
host SQLite probe runs the real schema/statement files for rollback and reopen
checks; it does not prove Android SQLite. These checks do not prove authentication prompts,
permission dialogs, URI grants, image formats, lifecycle, codecs or accessibility
on hardware. Run the [manual device matrix](DEVICE_CHECKS.md) before accepting this
as a release candidate. Large-library indexing and performance remain unmeasured;
the current foundation reads permitted metadata in the background into memory.

The two-frame logo and Still name remain provisional until brand/store checks.
