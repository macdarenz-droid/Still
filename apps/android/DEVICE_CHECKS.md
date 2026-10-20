# Android device acceptance matrix

Status: **BLOCKED — no attached Android device or accelerated emulator in the
bootstrap environment.** An APK build, JVM tests and lint do not replace these
checks. No device behavior below is represented as passed.

Record device/model, Android version, app commit, APK SHA-256, steps/result and
evidence for each row. Use synthetic or explicitly consented media and keep it
outside git. Include images, rotated JPEGs, HEIC, screenshots, videos, damaged and
unsupported files, empty and large libraries, and removable storage where present.

| Environment | Procedure | Expected result | Status |
| --- | --- | --- | --- |
| API 26–32 | Deny, grant, permanently deny, then change access in Settings | Clear access state, working settings recovery, real permitted media only | Blocked |
| API 33 | Grant images only, videos only, both, neither | Partial/full/none correct; queries avoid collections without grants | Blocked |
| API 34–37 | Grant selected photos/videos, reselect with same permission bits | Only new selection shown; no withdrawn viewer/selection/thumbnail survives refresh | Blocked |
| API 34–37 | Open a selected item, background app, withdraw it, resume | Viewer clears before async reload; no crash or false deletion result | Blocked |
| All | Scroll library, search filename, open album, rotate, background/resume | Stable usable navigation; no UI-thread media decoding; no lost originals | Blocked |
| All | Open photo, pinch/pan, zoom buttons, close with button/system Back | Aspect ratio retained, bounded pan/zoom, usable non-gesture actions | Blocked |
| All | Open video, use play/pause/seek; background/close | Real playback; pauses on background and stops on dismissal; honest error for unsupported source | Blocked |
| All | Favorite/unfavorite, restart app, change system favorites | Only Still favorites persist; original/system metadata untouched | Blocked |
| All | Long press/select mixed images and videos; cancel; share to two apps | Reversible selection, correct URI grant/MIME, recipients receive only selected media | Blocked |
| All | Delete/change media in another app, then return | Library reconciles without crashes or treating access loss as deletion | Blocked |
| All | Enable airplane mode; inspect manifest/network behavior | Local gallery functions work offline; Google account connection requires internet; no photos or library metadata are uploaded | Blocked |
| All | TalkBack, keyboard/switch navigation, 200% font, dark theme, RTL | Labels and selection conveyed, touch targets usable, no essential text/action clipped | Blocked |
| Reference device | Scroll at least 10,000 permitted items, rapidly open/close, revoke during load | Record cold-start/scroll memory and responsiveness; fix or document limits before release | Blocked |

## S06 whole-app lock

All rows below are **Blocked** pending identified physical devices or a suitable
emulator. JVM fault/transition tests and source inspection do not establish any
platform-authentication, screenshot, accessibility or lifecycle result. The
owner-supplied gallery screenshots have unknown API and APK identity and do not
count as lock acceptance.

| Environment | Procedure | Expected result | Status |
| --- | --- | --- | --- |
| API 26–27 | Set a phone screen lock; turn Still app lock on/off; cancel each prompt | System phone-screen-lock challenge; fresh success required for either setting change; cancellation preserves setting | Blocked |
| API 28–29, 30–37 | Enable, unlock and disable using biometric; retry a mismatch; choose phone screen lock; trigger enrollment/lockout/unavailable errors | No mismatch or error unlock; fallback opens the exact system challenge; honest retry/settings route | Blocked |
| All | Enable, force-stop/reopen; rotate or recreate during locked, unlocked and authenticating states | Fresh Activity starts locked; no restored auth/session/token or stale success | Blocked |
| All | Leave to Home, Recents, sharing chooser or Android Settings and return; use split-screen/top-resumed changes on API 29+ | Gallery/media work gated immediately at foreground loss; fresh authentication on return | Blocked |
| All | During credential challenge return results before/after app foreground, press Home, cancel, reopen and trigger another challenge | Gallery remains absent throughout; only matching single-use challenge can succeed; stale/unrelated results fail closed | Blocked |
| All | Turn screen off during viewer, biometric and external credential challenge; unlock phone and reopen Still | Still relocks; old callbacks cannot authorize; a new Still challenge is required | Blocked |
| All | Launch Still repeatedly and deliver a launcher/new intent while already open, including during prompt | Single task; every new entry relocks and invalidates old challenge | Blocked |
| All | While lock enabled, remove phone screen lock/enrollment in Android Settings; return to Still | Content remains gated; recovery opens Android security settings; unavailable authentication cannot disable lock | Blocked |
| All | Capture screenshot/recording and inspect Recents while app lock is on/off, gallery, photo/video viewer and information dialog are visible | CP7 owner-requested policy allows Still surfaces to be captured and ordinary Recents previews; verify each on hardware. Android may separately protect its authentication UI | Blocked |
| All | Install CP7 over CP6 with app lock enabled, authenticate and capture the gallery/viewer | Existing lock setting and local data retained; authentication still required; capture follows the CP7 policy without disabling app lock | Blocked |
| All | Interrupt setting writes or inject read/corruption/write/file-sync/directory-sync/readback/marker removal failures; recreate app and retry authenticated setting change | No record change before marker directory sync; no success before replacement directory sync; no failed-disable bypass; recovery stays locked; successful fresh update can recover | Blocked |
| All | Enable and background quickly; disable then background; return from authentication while screen off | Gallery appears only after foreground accepted authentication; locked gallery stays absent. Recents may contain captured app content under the CP7 capture policy | Blocked |
| All | Gate/settings with TalkBack, switch/keyboard navigation and 200% font; trigger cancel/error/recovery | Textual state and polite error announcements; readable scrolling; reachable actions without icon/color dependence | Blocked |

Native deletion/trash, backup/restore, AI, OCR and editing remain future tasks. No runtime or
production security acceptance is claimed by the native implementation alone.

## S09/S10 local catalogue and gallery controls

Every row is **Blocked** until actually run with recorded device/API, source SHA
and APK hash. Host SQLite/pure JVM probes validate the tested rules and SQL only.

| Environment | Procedure | Expected result | Status |
| --- | --- | --- | --- |
| API 26–29, 30–37 | Browse a populated/empty library, force-stop/reopen, change metadata then refresh | Live permitted rows appear before index persistence; metadata index survives restart; old-platform continuity remains best effort | Blocked |
| API 29–37 with removable media | Use identical bucket/row IDs across primary and removable volumes; mount/unmount during load | Distinct albums/source provenance; mixed or reset enumeration is rejected without cached fallback | Blocked |
| API 30–37 | Reset provider/version or simulate generation rollback and source-handle reuse, including an empty volume; select/open an item before replacement | New source/observation identity; old viewer and pending selection clear; originals/Favorites preferences are not migrated or deleted | Blocked |
| All | Revoke/limit access with same-bit selected-set changes during query/index; refresh repeatedly; lock/background while indexing | Invalidation fences live/status publication; no withdrawn thumbnails/selection/viewer or index-based browsing; late metadata never claims access/deletion | Blocked |
| All, full access | While grid/viewer is open, trigger a provider edit/reset/reused URI and delay fresh read/alias proof; invoke queued old open/select/share/favorite callbacks | Old rows/aliases/viewer hide immediately; all stale callbacks denied during loading and after changed-source completion; fresh valid rows regain actions | Blocked |
| All | Delay an old blocking provider query until a replacement scan succeeds, then make the old query fail | Old failure cannot clear the newer live rows, aliases, selection, viewer or access/error state | Blocked |
| All | Retain queued media/permission/settings/native Play callbacks, then background/lock before Compose disposal and again after it; authenticate later | Current authority denies callbacks immediately; disposed lifetime never revives; old player cannot restart; only a fresh eligible gallery scope acts | Blocked |
| All | Inject read/null-cursor/query error; separately inject SQL open/corruption/version/write/close errors | Provider failure clears visible results; index failure keeps valid live browsing with Retry and no success claim; no destructive DB repair | Blocked |
| All | Combine All/Photos/Videos, filename search, album and Favorites views with new/old/name sort, ties and unknown dates | Correct filtered counts, stable order, global filename order without month regrouping; filter-empty wording distinguishes the view from the whole library | Blocked |
| All | Select mixed media, change type/search/album scope and sort, then Share/batch Favorites | Hidden items excluded; sorting alone preserves unchanged selection; recipients receive only selected visible URIs | Blocked |
| API 29–37 | Upgrade old aggregate image/video Favorites; mix new concrete keys, duplicate handles across volumes, unavailable access/storage and changed row birth; unfavorite a matched item | Exact provider proof resolves only unambiguous current references; no raw-key migration; current+legacy matched keys toggle together; unresolved refs remain with honest notice; no transient final-empty state during lookup | Blocked |
| All | Open video; use visible and native Play/Pause, seek, complete then seek/replay, trigger bad/unsupported media | No autoplay; consistent action/state labels; native seek works; post-completion chosen position retained; honest playback error | Blocked |
| All | Play then Home/share/settings, screen-off, new entry, rotation/recreation, close and same-URI source edit | Pause/stop/release as appropriate; lock gate retained; no automatic resume or stale player after return/source change | Blocked |
| All | Filters, dropdown, errors and playback with TalkBack, keyboard/switch, 200% font, RTL and narrow/landscape window | Selected choice/action announced, visible 48dp controls, text/actions reachable without clipping; native controls accessible | Blocked |
| Reference device | Cold browse/index at least 17,000 synthetic/consented items; repeat filtering/sorting/search and cancellation | Record actual memory/startup/frame/SQL timings; no responsiveness or scalability claim without measurements | Blocked |
| Reference device | Upgrade a large set of legacy Favorites; cancel/revoke/reset provider midway through sequential metadata alias queries | Record linear lookup latency and cancellation behavior; no partial alias publication, guessed attachment or claimed fast upgrade | Blocked |


## S13/S14 exact readable-byte review

Every row is **Blocked** until run on an identified device/API and exact source/APK.
Synthetic JVM streams, policy/fault tests and source review do not establish native
provider transformations, actual filesystem durability, performance or event order.
Use synthetic or explicitly consented media outside git; never commit private media.

| Environment | Procedure | Expected result | Status |
| --- | --- | --- | --- |
| API 26–29, 30–37 | Start scans with known matching bytes, same-name different bytes, same pixels/different encoding, mixed type/size and unknown sizes | Only complete same-kind readable digest/actual-length matches group; coverage states omitted/unreadable/empty/changed items and never whole-phone/original equivalence | Blocked |
| All | Open Cleanup without Start; change type/search in gallery, then start manually | No automatic reads; scan uses fresh authorized full scope independent of UI filters | Blocked |
| All | Use redacted, transformed, cloud-backed or unsupported provider media | Readable representation scope stays honest; actual length counted; failures excluded; no offline/original/pinned-version claim | Blocked |
| All | Stop before a queued worker starts; delay a read and its close, Stop, tap Start repeatedly, navigate, lock/unlock and recreate | Output fenced immediately; Stopping lasts until real reader exit; one process-wide lane stays held; no queued or resumed scan; explicit retry after Busy | Blocked |
| All | Fail open/read/close; use empty, short-chunk, stalled or over-16-GiB representation; exceed 50,000 snapshot rows | No prefix/partial evidence; honest separate coverage or scope-limit failure; resources close; no fake successful groups | Blocked |
| API 30–37 | Change an early item while the final candidate reads; advance generation normally, reset/rollback version, mount/unmount storage | Whole result rejected after final namespace/member/access checks, even if an early digest was already read | Blocked |
| API 26–29 | Replace/edit an early item and preserve coarse metadata while scanning | Final per-member checks reject observed changes; unchanged coarse metadata remains explicitly best effort, never immutable byte proof | Blocked |
| API 34–37 | Reselect with same permission bits or withdraw an early selected member mid-scan | Late progress/results and old member actions blocked; fresh proof required, no withdrawn preview or alias-based action | Blocked |
| All | Change gallery between snapshot and scan, invoke old member/keeper/protection callbacks after new scan, refresh, lock and disposal | Different mapping invalidates and refreshes with manual retry; scan-object identity and live authority reject stale actions and completions | Blocked |
| API 29–37 | Include unresolved legacy Favorites, volume/handle collisions, current Favorites changes and mixed raw/proven references | Possible Favorites stay Unknown; affected extras paused; Favorites mutation invalidates review; raw keys preserved | Blocked |
| All | Choose/reverse multiple keepers; open member in photo/video viewer; protect/unprotect matching content | Keepers reversible and scan-bound; ordinary viewer/share/playback gates retained; no extras without keeper and no deletion affordance | Blocked |
| All | Protect then restart/reset/miss sources; reuse URI with different bytes; freshly hash equal bytes at another URI | Only content-key matches share durable protection; missing annotations retained; no URI rebinding or historical-identity claim | Blocked |
| All | Cancel a protection dialog, reopen identical values, retain old confirm/dismiss callbacks and replace results with equal-looking new content | Only the exact active single-use request and current opaque review may admit a write; no retargeted old confirmation | Blocked |
| All | Inject format/oversize/corruption/revision/write/sync/readback/marker failure; fill 4,096-key/512-KiB bounds; delay old READY load behind failed save/new request | Unknown blocks extras and labels; no optimistic saved/unprotected state; raw records retained; old completions denied; explicit reread never promises repair | Blocked |
| All | Stop/background during annotation admission; delay store coordinator; lock/recreate | New mutation denied after invalidation; admitted tiny replacement may finish but cannot publish to old/new gallery; new gallery reloads durable state | Blocked |
| All | TalkBack, switch/keyboard, 200% font, RTL, narrow/landscape; one very large matching group | Reachable Stop/Refresh/keeper/protection confirmation; textual unknown/saved/error states and lazy members; no clipped essential action | Blocked |
| Reference device | Measure 17,000-item scans, large videos, many legacy references and 50,000-member synthetic group | Record actual read/CPU/memory/battery/thermal/cancellation/frame timings; no throughput, responsiveness or durability guarantee before measurement | Blocked |


## S17/S18 Drift native presentation

Every row remains **Blocked** until run on an identified Android device/API with
exact source and APK hash. Browser prototype checks and JVM navigation tests do not
establish native rendering, gestures, motion, focus, accessibility or performance.

| Environment | Procedure | Expected result | Status |
| --- | --- | --- | --- |
| All | On Photos/Favorites/album Ribbon, swipe left repeatedly, alternate Next/Previous with swipes in both directions, reach both ends, and return without switching tabs | After every focus change both controls remain usable; no stuck gate, unexpected open/selection or tab-switch workaround; bounds stay on the current item | Blocked |
| All | After repeated ribbon moves, change dates/pages, refresh/restrict access, leave/lock and return; attempt retained old input | Fresh permitted view remains usable; old exact-focus/snapshot actions stay denied, including A→B→A | Blocked |
| API 26–37 | Browse Photos, Favorites and album contents; drag and use Previous/Next/Open; reach both ends | At most five ribbon previews; no wrap; same current ordered row from buttons and gestures; real filename/date/count | Blocked |
| All | Start a drag, navigate A→B→A or jump to the same row, then release; change query/type/sort/album/destination/Ribbon/Grid/selection | Old completion cannot override later focus or act on another view; replacement at the same URI cannot inherit gesture/viewer state | Blocked |
| All | Refresh to identical-looking rows; reset provider/reuse URI; withdraw same-bit partial access; lock before composition disposal | Immediate old-card/viewer removal and stale-action rejection; no retained exit frame or old thumbnail publication | Blocked |
| All | Open a nonfirst source from Grid, close it, switch Ribbon/Grid/sort and foreground-refresh unchanged strongly evidenced rows; then change/miss/replace it | Position intent survives only valid continuity; fresh callback identities are minted; changed/weak rows fall back without retaining pixels or authority | Blocked |
| All | Page date windows 0→7→14 and 0→7→0; retain old page callbacks | Every accepted page renews interaction identity; old callbacks cannot reset a newer rail page even when its offset or focused source repeats | Blocked |
| All | Dismiss and reopen Menu/View options; retain old Close, menu destination and field/filter/view callbacks | Exact opening identity fences old actions; ongoing edits in the active Options dialog remain usable | Blocked |
| All | Retain a broad-view batch action, then select different items in a narrower query/type/album view | Old Share/Favorites handler cannot consume the new selection; only the current view acts | Blocked |
| All | Cancel and reopen identical date pickers; retain old confirm/dismiss; change focus without intermediate composition | Old single-use requests cannot jump or close a later picker or bind a new focus | Blocked |
| All | Open, close and immediately reopen the same source; retain old close/share/Favorite callbacks | Distinct opening identity denies every old viewer action; current viewer continues normally | Blocked |
| All | Browse dates across midnight/year/DST/timezone, unknown dates and NAME order; page rail and exact jump, enter malformed/absent dates | At most seven actual rail dates; full spoken date/year; Date unavailable reachable; first exact match without reordering; distinct input/no-match errors | Blocked |
| All | While ribbon/photo opening moves, change Android animator scale to zero; navigate again and repeatedly close/reopen | Running and future decoration settles without delaying action or retaining media; no perpetual motion or stuck viewer | Blocked |
| All | Use Search/View options/Grid/Select/share/Favorites/Settings/app lock/Cleanup from new chrome | Existing actual source, selection, Favorites alias, permission, annotation and lock guards remain intact; no hidden essential action | Blocked |
| All | Use TalkBack, keyboard/switch, 200% font, RTL, 320dp width, landscape and split-screen | Focused photo only among decorative cards; settled-position announcements; selected states and 48dp actions; all content/menu/dialog actions reachable without clipping | Blocked |
| All | Open videos from ribbon/grid/Cleanup; Play/Pause/seek, background, lock, rotate and edit source | Existing explicit/no-autoplay/native-seek behavior and current-source/lifecycle start checks preserved | Blocked |
| Reference device | Browse 17,000 synthetic/consented rows, large albums and legacy Favorites; measure ribbon/Grid/date/search transitions | Record actual thumbnail requests, heap, startup and frame timings; bounded composition is not a measured performance guarantee | Blocked |

## S29/S30 selected inputs and photo comparison

Every row is **Blocked** until run on an identified device/API with exact source
and APK hash. JVM files/streams, sampled-decode policy tests and APK inspection
do not establish Android provider output, native decoding, IO event order or UI.
Keep synthetic/consented fixtures outside git.

| Environment | Procedure | Expected result | Status |
| --- | --- | --- | --- |
| API 26–37 | Select exactly two photos from Photos/Favorites/album Grid, compare, zoom/pan with gestures and buttons, Reset and Close/Back | Two real bounded prepared-file previews; linked controls and filenames; selection scope retained; no source/share/delete operation | Blocked |
| All | Select zero/one/three photos, mixed photo/video, hide a selected photo by filter/search, or invoke a retained old selection callback | Comparison accepts exactly the current two visible photo sources; old/hidden/replaced selections cannot retarget it | Blocked |
| All | Compare JPEG/PNG/HEIC/WebP, eight EXIF mirror/rotation fixtures, mismatched dimensions, damaged/unsupported/animated files and very large dimensions | Orientation follows staged bytes; bounded sampled/allocation checks; honest preview failure; no source fallback or autoplay/full-quality claim | Blocked |
| All | Use a provider that redacts/transcodes/cloud-fetches; vary reported size and actual representation length | Actual readable bytes govern quotas and identity; no original fidelity, offline, backup or deletion-readiness claim | Blocked |
| All | Prepare exact32MiB inputs then one byte over, unknown/lying sizes, empty/stalled/failed/close-failed streams or low-storage/sync/readback failure | At most64MiB retained pair; no partial pair/READY; explicit failure and verified cleanup or blocked staging on cleanup failure | Blocked |
| All | Change first source while second prepares; reset/advance provider, replace same URI, remove volume or withdraw same-bit selected access | Whole pair fenced; both previews disappear; no old-byte fallback or late completion | Blocked |
| All | Cancel before worker starts, delay read and close, tap Compare repeatedly, navigate and recreate while IO is blocked; overlap old Cleanup scan | Immediate UI fencing; shared process admission held through actual closure; no queued readers, deleted live files or accumulated sessions | Blocked |
| All | Close/background/lock, change browse scope/refresh, rotate/recreate and authenticate later; retain old zoom/reset/close/completion callbacks | No saved comparison or inherited ticket/path; current session alone acts; new gallery can recover after admitted cleanup settles | Blocked |
| All | Kill process at partial write/publish/readback/ready/cleanup boundaries, reopen gallery and compare again; inject file eviction/deletion failure and unexpected link/path | No session resumes; own orphan cleanup is coordinated, no unrelated cache/original touched; failed cleanup remains visible and blocks added staging | Blocked |
| All | Inspect app-private comparison cache, backup/transfer and recipient/share behavior during/after comparison | Only explicitly selected temporary representations in owned cache; no preview cache/export/FileProvider/network path; existing backup exclusions hold; no secure-erasure promise | Blocked |
| All | TalkBack, keyboard/switch, 200% font, RTL, 320dp width, landscape/split-screen and live animator scale zero | Paired content and essential pan/zoom/reset/close/cancel/recovery controls reachable and labelled; source withdrawal has no exit animation | Blocked |
| Reference device | Repeated maximum-size comparisons plus Cleanup; measure heap/decode/IO/cancel/frame behavior and retention | Record actual bitmap/CPU/disk/latency/thermal values; bounded requests are not measured throughput or heap guarantees | Blocked |

## S34/S35 selected-photo visual review

Every row remains **Blocked** until observed on an identified device/API with
exact source and APK hash. Host pixel/ImageIO and fault tests do not establish
Android codec/provider/EXIF behavior, broad retrieval quality or hardware budgets.
Use synthetic/consented fixtures; keep private evaluation media outside git.

| Environment | Procedure | Expected result | Status |
| --- | --- | --- | --- |
| API26–37 | Select2–200 visible photos from Photos/Favorites/album Grid across different months/years, Find similar, review a pair and Compare | Only the explicit selection analyzed; direct appearance pairs, actual names/dates and fresh independently prepared CP10 comparison; no keeper/extras/delete authority | Blocked |
| All | Select0/1/201 photos, mixed video, duplicate/alias sources, hide selected photos by filtering, or reuse a captured old action | Honest2–200 photo limit; no truncation, broadened scope, stale selection or retargeted action | Blocked |
| All | Use resized/reencoded/light-adjusted same scenes, all8EXIF transforms and actual64/32pixel source/analyzed boundaries | Versioned bounded appearance evidence; tiny/upscaled/low-information rejected; native orientation and format support recorded without general accuracy claim | Blocked |
| All | Review similar screenshots with a changed digit, different document pages, expressions/objects, before-after, crops/edits and patterns | Meaningful differences remain independently reviewable; no interchangeable-copy, quality winner or removal suggestion | Blocked |
| All | Use exact readable copies, different encodings, tiny/flat/alpha images, animation/motion, damaged headers and extreme dimensions | Exact-readable-pair omissions separated from appearance; actual unsupported/low-detail failures reflected in coverage; no video/animation playback or source fallback | Blocked |
| All | Reach16MiB per-input/256MiB run limits including one-byte overflow/EOF probes; submit more than50 qualifying pairs | Actual read/buffer limits hold; incomplete input/scope and omitted pairs shown distinctly from no match; no hidden full-library or complete-coverage claim | Blocked |
| All | Change first source during a later read, reset/advance namespace, remove volume or withdraw same-bit selected access | Entire old result/preview/Compare fenced; every selected source and namespace rechecked; no late publication | Blocked |
| All | Cancel before launch, block read/decoder/close, throw read+suppressed-close errors, recreate; overlap exact Cleanup or CP10 | No replacement heavy reader before actual settlement; uncertain close keeps process ownership; honest busy/stopping/retry after dialog disappearance | Blocked |
| All | Start equal-looking new review; retain old Compare/Close/Stop/Retry; choose pairA→B→A without intermediate render | Exact current review/choice/origin alone acts; old callbacks cannot retarget or clean a new task | Blocked |
| All | Refresh, navigate, background, lock/unlock, rotate/recreate or kill process during analysis/review/Compare | Immediate withdrawal, no saved features/tickets/auto-resume, admitted cleanup finishes independently; CP10 cache policy preserved | Blocked |
| All | TalkBack, keyboard/switch, 200%font, RTL,320dp width/landscape/split-screen and animator scale0 | Reachable selection/Start/Stop/Compare/Close/retry and truthful textual coverage, with current-source previews and no retained exit images | Blocked |
| Reference device | Repeated200-photo batches, maximal encoded inputs, crowded50-pair results and17k-row metadata mapping; measure actual heap/read/CPU/thermal/cancel/frame behavior | Record identified workload and timings; hard request bounds are not measured heap/throughput/accuracy guarantees | Blocked |


## CP14 Google Drive connection (all unverified)

| Device/setup | Action | Expected result | Status |
| --- | --- | --- | --- |
| Unconfigured build | Open Settings → Google Drive | Plain unavailable reason; no Google chooser or request; saved account clearly marked as requiring sign-in | Blocked |
| Configured Google Play services device | Connect; choose existing or another Google account | Official chooser/sign-in; separate account-bound `drive.file` permission with prior resource scopes excluded; no uploads or backup controls | Blocked |
| Configured device | Cancel chooser or deny Drive permission | No new connected account; previous authorized session retained on cancelled switch | Blocked |
| Configured device, two authorized test accounts | Switch A → B; cancel/retry; replay old result | Only successful current attempt changes account; old callbacks cannot retarget B | Blocked |
| Connected device | Restart process | Only saved display remains; fresh explicit sign-in required | Blocked |
| Lock enabled | Complete Google handoff; return/rotate/home/screen-off | Gallery remains gated; Google connection never unlocks Still; interruption may require a new attempt | Blocked |
| Connected device, then offline/revoked access | Disconnect; retry sign-in | Local token and saved account clear immediately; Google permission remains until removed in Google Account settings; Drive files are kept | Blocked |
| Small window, large text, TalkBack | Navigate Drive section and all states/actions | Wrapped text, headings/status announcements, labelled 48dp controls; no colour-only state | Blocked |
| Configured/unconfigured APK | Inspect preferences/backup rules/logs and merged manifest | Only display/email/random epoch persisted; no token/subject/ID token; no media writes, app-owned service, worker or upload surface. Google SDK's exported `RevocationBoundService` is guarded by its `REVOCATION_NOTIFICATION` permission | Blocked |
