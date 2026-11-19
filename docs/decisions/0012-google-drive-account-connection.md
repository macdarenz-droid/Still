# ADR 0012: User-selected Google Drive account connection

Status: CP14 implementation scope; provider selected by the owner.

## Decision

Still will let the owner connect a Google account and use that account's Google
Drive as the first backup destination. The connection flow is separate from a
backup action: connecting or switching accounts never uploads media and never
enables automatic backup.

Request the narrow `https://www.googleapis.com/auth/drive.file` scope only. Store
Still-created backup files in a visible folder in the selected user's Drive;
never use `appDataFolder` as a hidden media archive or request access to all Drive
files. Drive backups are ordinary Drive files and do not appear in Google Photos.

The app must show which account is connected and offer explicit switch and
disconnect actions. A local connection epoch fences future transfer work; each
job remains bound to the account selected when that job was created. An account
switch cannot retarget queued, active, uncertain or completed work. In this
checkpoint, disconnecting clears Still's local account state and in-memory
access. It does not revoke Google's account-level grant; the user can remove that
grant in Google Account settings. It never deletes files already stored in Drive.

Access and identity tokens are transient. Do not persist or log OAuth tokens,
authorization codes, resumable session URLs, or ID tokens. Persist only the
minimum local account label and opaque account binding needed to explain the
selected destination and fence jobs. The app has no Still service account or
server-side Google account link in this slice.

## Android and OAuth setup

Use Google's currently supported Android identity and authorization APIs. The
implementation must prompt for an explicit account choice, authorize the Drive
scope for that same account, handle partial/denied consent and cancellation, and
provide a disconnect path. Avoid deprecated Google Sign-In and broad Android
account enumeration. Show the selected email as a label, not as the stable
account key.

OAuth registration is required before the flow can work on a device. The owner
must register the Android application ID and signing-certificate SHA-1, enable
the Drive API, configure consent and the declared `drive.file` scope, and provide
the OAuth client identifier needed by Android sign-in through local build
configuration. No client secret or credential belongs in source control. Builds
without OAuth configuration must show a clear unavailable state and must not
pretend to connect.

## Scope and acceptance

CP14 delivers account connection, account choice, current-account display,
switch, local disconnect, lifecycle handling and a truthful Settings surface.
It does not call Google's non-cancellable remote grant-revocation operation: a
late revoke could invalidate a later connection to the same account. It does not
upload/download user media, create backup objects, implement the transfer ledger,
restore files, schedule background work, or remove local files.
Connection must not add network permission beyond what the Google identity and
authorization SDK requires; any app-owned Drive API requests are a later card.

Tests must cover account choice and cancellation, missing configuration, partial
scope denial, failed/revoked access, lifecycle return, switch/disconnect, and
connection-epoch isolation. A connected label alone is never backup evidence.
Real account verification requires an owner-configured OAuth project and a
physical Android device; no account is connected during automated checks.

## References

- [Google Drive API scopes](https://developers.google.com/workspace/drive/api/guides/api-specific-auth)
- [Android AuthorizationClient](https://developers.google.com/android/reference/com/google/android/gms/auth/api/identity/AuthorizationClient)
- [Google Android client authentication](https://developers.google.com/android/guides/client-auth)
