# ADR0006 — Drift native gallery experience

Status: selected by the owner on 19 October 2026; native implementation checkpoint CP6.

The owner chose the first, dark cinematic DRIFT concept after two rendered and
interactive design samples. Product name remains Still. The selected prototype
is retained locally as `.artifacts/design-ux/drift.html`, final hash
`fec5ed6a5e668e6ff9f4e54f638325e968b882f2769e5c6f5cfcbde6e969f2d2`.
The private comparison Page is
https://chatgpt.com/space/page_c731a83d8b3c8191bec755c2c9575d18.
Both independent roles accepted that browser design sample; it is not Android
runtime or accessibility evidence. No stock/demo media belongs in the native app.

## Presentation and behavior

Use an ink canvas, warm white text, restrained coral selection, expressive serif
heading and a layered photo ribbon. Compose at most five thumbnail cards around
the current item. Drag/swipe, explicit Previous/Next and accessible semantics all
navigate the same current ordered view. Bound the ends; do not wrap unexpectedly.
Tap the focused photo to open the existing viewer. Real filenames, observed dates,
media type and current-view position replace fictional place/memory captions.

The ribbon is the default browsing presentation for Photos, Favorites and album
contents. Retain the lazy Grid as an explicit view choice and the selection surface;
large libraries must not require repeated swipes. Albums retain their grid overview.
Search, type filtering, existing global sorting, selection/sharing, Favorites,
playback, Cleanup, app-lock and access controls remain reachable. A lightweight
header/menu may reveal search and view controls; Settings must have an obvious
labelled route. Do not allow all these controls to crowd out the pictures.

Flexible/scrollable layouts must accommodate small/landscape windows and enlarged
fonts. Touch targets are at least48dp; no essential action depends on gesture,
color, tilt or a clipped single-line label. Offscreen decorative neighbors do not
create duplicate accessibility targets. Announce settled position, not animation
frames. Date chips expose complete localized dates including year to assistive
technology; unknown dates have a real reachable target.

## Current-source navigation

S17 supplies a small, memory-only navigation projection over the existing filtered
and ordered eligible media. It never queries MediaStore or becomes access authority.
Instance/snapshot-bound navigation returns source UI tokens and current indexes,
not URI-only identities. New snapshots invalidate old navigation, even if display
rows are identical. Invalidate before refresh/revocation/lock disposal; existing
`galleryActionItems` and gallery lifetime still govern every actual action.

Build token indexes and local-date anchors once per view snapshot, then use constant
time navigation. Duplicate exact tokens make a snapshot unusable instead of
choosing an arbitrary item. Local dates use the observed epoch milliseconds and an
explicit ZoneId. Nonpositive dates are Unknown. Source dates may be Android's
added-date fallback, so do not label them as verified original capture dates.

The date rail exposes a bounded window of at most seven actual date targets, with
paging/jump controls for other dates. Dates absent from the current view do not
produce phantom media. A date jump selects a real current token without reordering
the view. Filename sorting remains global even when a day's items are interleaved;
the rail is date navigation, not a claim that those rows form a contiguous group.
Focus recovery after changing view/sort/scope is an explicit UI decision; stale
callbacks must never silently retarget the item now occupying an old position.

## Motion and privacy boundaries

Use finite Compose springs/tweens for ribbon focus and a restrained photo-viewer
opening transition. Keep animations in the Compose coroutine/frame context, which
already observes Android animation scale; no new settings IO or dependency is
needed. Scale zero must snap live transitions as well as future ones. Reset/cancel
motion on source/scope change. Close, refresh, permission loss and locking remove
ineligible media immediately; exit decoration cannot delay those fences.

Keep the existing viewer, zoom/share/Favorites and guarded native VideoPlayer.
Do not animate a retained old viewer after lock/revoke or change autoplay policy.
Reuse `LibraryImage`, its generation/provenance cache keys and the existing bounded
cache. No full-library bitmap deck, new decoder, media copy, remote request,
analytics, permission, dependency, persistent setting/schema or cloud operation.
The selected design permits dark app tokens and matching system-bar styling;
authentication, secure-window and lifecycle policy remain unchanged.

## Evidence and recovery

Meaningful new policy cases cover snapshot/session rejection, duplicate tokens,
same-URI replacement, boundaries, timezone/midnight/DST/unknown dates, NAME ordering
and bounded navigation over17,000 rows. Preserve all129 existing JVM cases and
their assertions. Final native assemble/all JVM/strict lint and repository checks,
APK audit, separate exact-source/local-APK reviewer and red-team passes, hosted CI
and draft PR are required before handing off the development APK.

No attached device/emulator is currently available. Native gestures, animator-scale
changes, TalkBack, focus, large text, rotation, secure surfaces, video lifecycle and
17,000-item memory/frame timings remain explicitly unverified until measured.
Code/model/browser evidence cannot establish those results. Rollback CP6 while
preserving local app data, or reinstall a compatible retained development build;
no data migration or original-media mutation is part of this checkpoint.
