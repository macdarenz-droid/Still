# S02 device acceptance matrix

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

Cleanup, backup/restore, app lock, AI, OCR and editing tests belong to their future
task cards. Nothing in this checkpoint claims those features are implemented.
