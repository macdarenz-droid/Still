# ADR 0003: Whole-app access lock

Status: CP3 development design; capture policy revised by the owner in CP7.
Device security acceptance is pending.

R14 / F04 and architecture section 16.3 require a whole-app lock. The Android
gallery can continue development while release-device tests remain incomplete.
The owner's supplied screenshots establish visible gallery navigation only.

Use Android platform authentication rather than an app PIN or custom cryptography.
Biometric authentication is available on supported API28+ devices; Android device
credentials provide the API26+ fallback. No authentication SDK, network permission,
account, cloud provider or production signing change is needed for this work.

The Activity creates a fresh memory-only lock session. When the durable setting
is enabled, a new Activity/process starts locked. Authentication attempts are
bound to both purpose and identity; stale, canceled, replaced or duplicate results
cannot authorize a later session or another operation. Enabling and disabling
each require a fresh successful system authentication. No unlocked flag or pending
authentication is restored through Compose or Activity saved state.

Leaving the foreground revokes access immediately. A narrowly bounded device-
credential Activity round trip may retain its challenge while gallery content
remains hidden. Screen-off, external entry, a second foreground loss or recreation
invalidate that exception. Callback/resume ordering must be covered in JVM policy
tests and checked separately against real Android lifecycle behavior.

Persist only the on/off setting in a strict, versioned platform AtomicFile record.
An existing corrupt or unreadable record is treated as enabled. Failed writes
must not silently disable the lock or claim successful setup. Android's ordinary
SharedPreferences may silently load defaults after XML corruption, and a failed
commit can still change its in-memory value; it is unsuitable as the security
flag here. Existing app-wide backup exclusions apply to this local setting.
A pending-write marker fences an uncertain transaction: sync its bytes and parent
directory before changing the setting, sync the replacement record and directory,
read back the strict record, then remove the marker only after success. A remaining
marker forces locked-on recovery on the next load. Deletion of the marker is the
last operation; a crash that restores that marker conservatively relocks the app.
The marker contains no authentication result, credential or unlocked session.

Place the entire gallery behind the gate, including media queries, viewers and
Settings. CP3 originally requested screenshot/Recents blocking while lock protection
was active. On 19 October 2026 the owner explicitly requested removing screenshot
and screen-recording restrictions so they could show the app. CP7 supersedes that
capture policy: Still allows captures and ordinary Recents previews even with app
lock enabled. Authentication, hidden locked gallery content and relocking remain
required. Android's own authentication surfaces may retain system restrictions.
Device credentials that become unavailable leave the app locked with Android security-settings
recovery. Shared recipients retain the explicit URI grants already given to them;
the gate protects subsequent access through Still.

This is an access gate, not an encrypted vault. It does not hide ordinary phone
photos from other galleries, encrypt originals or protect a compromised OS.
No new private media storage, analytics, network transmission or schema migration
is part of this checkpoint. Uninstall removes Still's setting and annotations;
phone originals are unchanged.

Acceptance requires the existing backend/site checks, full Android build, all JVM
tests and strict lint, followed by independent reviewer and red-team verdicts on
the exact integrated source/artifact. These cannot replace the native biometric,
credential, screen-off, background, rotation, process death, snapshot and
accessibility procedures in the Android device matrix. CP3 is a development
handoff until those procedures are actually run; it is not production approval.
