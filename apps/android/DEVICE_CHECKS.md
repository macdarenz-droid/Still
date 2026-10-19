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
