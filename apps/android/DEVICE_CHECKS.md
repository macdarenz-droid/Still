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
| All | Enable airplane mode; inspect manifest/network behavior | All local gallery functions work; app has no internet permission or uploads | Blocked |
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
| All | Capture screenshot/recording and inspect Recents while locked, unlocking, gallery, photo/video viewer and information dialog are visible | Android protection requested for Activity and inherited dialog windows; verify actual device surfaces and document OEM limits | Blocked |
| All | Interrupt setting writes or inject read/corruption/write/file-sync/directory-sync/readback/marker removal failures; recreate app and retry authenticated setting change | No record change before marker directory sync; no success before replacement directory sync; no failed-disable bypass; recovery stays locked; successful fresh update can recover | Blocked |
| All | Enable and background quickly; disable then background; return from authentication while screen off | No sensitive first frame or recents flash; only foreground accepted authentication grants access | Blocked |
| All | Gate/settings with TalkBack, switch/keyboard navigation and 200% font; trigger cancel/error/recovery | Textual state and polite error announcements; readable scrolling; reachable actions without icon/color dependence | Blocked |

Cleanup, backup/restore, AI, OCR and editing remain future tasks. No runtime or
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
