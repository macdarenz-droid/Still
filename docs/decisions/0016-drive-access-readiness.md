# ADR 0016: Read-only Google Drive access readiness

Status: CP19 design; no media or Drive file operation.

## Context

CP14 lets the owner select a Google account and grant the narrow `drive.file`
scope. A connected label confirms the local authorization flow completed, but it
does not confirm that Still can reach the Drive API or whether Google reports a
storage limit. Before adding a user-initiated copy operation, Still needs a
bounded, read-only check of the chosen account's Drive access.

The Drive v3 `about.get` endpoint documents `drive.file` as an accepted scope. The
check requests only storage quota fields; it does not list or create files.

## Decision

Add a manual **Check Drive access** action in Settings, available only while the
current Drive connection is active. It makes one HTTPS request to Drive v3
`about.get`, asking for the smallest supported `storageQuota` fields. The status
may report the storage values Google returned. Missing or unknown quota remains
unknown; the app must not infer unlimited space or promise capacity.

The action is read-only and user initiated. It does not create a folder, list
files, read media, upload, download, start a backup, schedule work or modify the
Google account. Its state is transient and scoped to the live connection.

The Android connection session owns the access token and issues an internal,
short-lived lease bound to its current connection revision. The lease carries a
private token reference only to the API transport; the UI and persisted
preferences never receive the token. Account switching, reauthorization,
disconnect, session disposal, cancellation, or a newer check fences the prior
result and cancels in-flight HTTP work where possible. Only a still-current
lease may publish a result.

The transport uses HTTPS to the exact Google API host, refuses redirects, has
bounded connection/read timeouts and a small response limit, and parses only the
expected response shape. It never logs the authorization header, token, response
body, email, or raw server error. Automated tests use an injected fake transport;
they make no network request.

Settings copy may say **Drive access checked** and show only quota values returned
by Google. It must not imply that Still has uploaded, verified, or backed up a
photo. The existing account-versus-backup distinction remains explicit.

## Scope and exclusions

No Google Drive file or folder operation, source read, permission change,
provider transfer, local backup record, restore, automatic work, database,
dependency, server endpoint, or token persistence is added. The returned storage
quota is shown only as transient local state after the explicit check; it is not
persisted, logged or sent elsewhere. No user media or library metadata is
transmitted. Existing Android internet permission is reused solely for this
explicit authenticated status request.

This check establishes only that the current in-memory authorization can read
the requested Drive account metadata at the time of the request. It does not
prove quota availability for a future file, user-visible folder behavior,
upload/resume/reconciliation, object readback, source fidelity or restore.

## Required evidence

- No request occurs without a current connected grant or after the action becomes
  stale.
- A valid current grant performs only `about.get` with the minimal quota fields;
  redirects are refused and response/time bounds are enforced.
- Cancellation, disconnect, account switch, reauthorization, disposal and newer
  checks cannot publish an older result.
- Missing quota is represented as unknown; malformed, oversized and unexpected
  responses fail closed.
- HTTP errors and exceptions do not expose tokens, server bodies or account data
  to logs or UI.
- Focused fake-transport tests and full Android tests/lint/build pass. Independent
  reviewer and separate red team pass the exact integrated source. Live chooser,
  consent and endpoint behavior remain device checks requiring owner OAuth setup.

## References

- [Drive API `about.get`](https://developers.google.com/workspace/drive/api/reference/rest/v3/about/get)
- [Drive API scopes](https://developers.google.com/workspace/drive/api/guides/api-specific-auth)
- [CP14 account connection](0012-google-drive-account-connection.md)
- [CP18 composed fixture proof](0015-composed-backup-pipeline-proof.md)
