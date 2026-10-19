# ADR0009 — Selected-photo visual review

Status: CP11 development design after accepted CP10.

Implement required R02/architecture9.1 and Stage2 as a bounded selected-photo
appearance-candidate task. In Grid, select2–200 current visible photos and choose
Find similar. Analyze that explicit selection across arbitrary dates, then show
direct candidate pairs with actual filenames and dates. Compare is an explicit
action that independently prepares the chosen current pair through CP10. This
checkpoint does not complete the architecture's durable full-library incremental
similarity index; that remains planned, with existing library-wide exact scanning
unchanged. Never silently truncate a large selection or add a time cutoff.

## Authority and representation

Use exact current source observations, provider namespaces, live access and opaque
scan/result/pair/choice identities. No restored tickets, saved features or automatic
resume. Snapshot the authorized metadata with the existing50,000-row bound, match
EVERY selected token to it, deduplicate physical source aliases, and reject changed
or ambiguous sources. Probe before/after each read, validate scope and recheck all
selected sources at the end, including early and failed members. Modern generation
advance invalidates the complete result; old-provider checks remain best effort.

One bounded provider read captures a transient readable representation per photo;
EXIF/header/pixel extraction reads those same memory bytes. Only complete nonempty
EOF and successful tracked closure permit extraction. The byte representation,
perceptual descriptor and pair evidence never establish original fidelity,
canonical AssetVersion, exact-copy equality, CP5 protection or CP9 readiness.
Android may transform/redact/fetch the selected readable source. No new network
permission, cloud request, provider account or persistent media/feature store.
Initial native analysis supports static JPEG/PNG and omits other formats, known
animation/motion variants and transparency. An omitted input is unassessed, never
proven unique or redundant. Progress separates provider-open attempts from photos
that actually yielded usable features and from unattempted or incomplete scope.

## Resource and lifecycle bounds

- 2–200 selected photos; inherited50,000 accessible-metadata-row cap.
- 16MiB actual encoded bytes per photo,256MiB actual encoded bytes per run,
  64KiB read chunks; at most32MiB live explicit encoded buffers for one photo.
  Count scratch, growth, copies and overflow probes. An over-limit photo may
  consume one rejection-probe byte beyond its accepted limit, charged to the run;
  never exceed the run budget to prove EOF. Exhaustion without proven EOF omits
  that input. Prefer one owned capture without an unnecessary full-size clone.
- At most one decoded feature bitmap at a time; sampled edge≤256, pixels≤1million,
  actual software allocation≤4MiB. Validate dimensions before decoding and actual
  output before reading pixels. Reject malformed/oversized/nonfinite/unsupported
  media; omit known animated/motion representations when support is uncertain.
  Actual source shorter edge must be at least64pixels and actual analyzed shorter
  edge at least32pixels; upscaling a tiny source cannot establish usable detail.
- At most200 bounded descriptors,19,900 unordered direct pair checks and50 returned
  pairs. Record pairs omitted by the result cap separately. Budget exhaustion
  reports unattempted scope and cannot appear as complete no-match coverage.

Analysis is explicitly started and runs off the UI thread with nonqueueing shared
`cleanupProcessReads` admission. Cancellation immediately fences output; actual
blocked IO/decoder completion and verified closure govern lane release. Retain
failed resources/process ownership across recreated controllers; retry only owned
closure, with no replacement worker admitted while closure is uncertain. Never
copy CleanupScanner's unconditional-finally release as failed-close proof. No
files, hashes, paths, features, filenames or source handles in logs/saved state.

Refresh, scope/selection/access changes, lock, ON_STOP, navigation and disposal
immediately withdraw results and comparisons; cleanup outlives composition if
needed. Late same-shaped result or pair-choice callbacks cannot retarget a new
review. A worker cancelled before its body starts still completes ownership
settlement. Show busy/finishing/failed-close recovery honestly after the dialog
is gone. No secure-erasure, power-loss, latency or hardware-memory guarantee.

## Appearance retrieval and evidence

Use a versioned deterministic normalized perceptual feature (32×32 luminance DCT
pHash64) with aspect/color/spatial checks and low-information rejection. Produce
direct PAIRS with their own evidence; no union-find/transitive group equivalence,
keeper/quality recommendation, extras count, removal or protection action. Capture
time is not eligibility or a distance multiplier. Distances are algorithm values,
not calibrated percentages or probability of redundancy. Do not use cached gallery
thumbnail equality as evidence.

Pin the algorithm/threshold/fixture versions. Calibrate with a separate fixture
set, then run held-out production-pixel evaluations: same scene across years,
resizing/re-encoding/light changes and EXIF1–8, dissimilar subjects/colors/aspects,
flat/tiny/patterned/alpha images, crops/edits and meaningful document digit/page,
expression/state and before-after changes. Report candidate false positives and
misses honestly. Appearance collisions remain possible: screenshot text, document
pages and changed states need direct comparison, never interchangeable-copy claims.
Host pixel fixtures do not prove native codec/provider or broad retrieval quality.
Freeze thresholds before observing held-out results. If evaluation feedback changes
them, retain the failure and treat that fixture set as regression evidence; obtain
new held-out fixtures. Positive retrieval cases must actually produce candidates.

Initial frozen-v1 evaluation missed a clear brightness-shift positive because
fixed histogram bins changed abruptly. CP11 therefore uses a separately versioned
illumination-normalized color/layout revision with the same numeric thresholds,
declared new calibration and genuinely new independently frozen evaluation. The
original failures and corpus remain regression evidence; every descriptor/pair
must carry the revised pipeline identity. This is a retrieval correction, without
semantic text/state equivalence or general quality claims.

## UI and Compare integration

Keep existing Drift, Grid, exact Cleanup and CP10 toolbar behavior. Add Find
similar only for an eligible selection, with visible2–200 limit and actual counts.
Use a separate similarity owner and responsive read-only dialog/list, lazy pair
cards, real filenames/dates, essential buttons, Stop/Close and truthful empty/error/
partial states. No AI slogans, fake percentage, staged success or new annotation.
Use current generation-fenced gallery previews for display only, distinct from
analysis evidence, and no retained analysis bitmap cache. Existing theme and
capture permission remain; motion is finite/reduced-motion compatible.

The chosen pair resolves through the exact current similarity result and choice
identity against current full authorized media. Capture its original selected
scope/lifetime/access/refresh identity, reject stale/foreign/withdrawn pairs and
selection ABA, then start existing CompareCoordinator/ComparePreparedWork. Do
not fake Gallery selection, reuse old prepared files or weaken the existing
Grid Compare predicate. CP10 independently enforces32/64MiB staging and cleanup.
Closing/changing the similarity origin also withdraws its comparison.

## Acceptance and rollback

Preserve every225 Android and74 backend case/assertion and existing schema,
security/capture/player/permissions/source semantics. Add meaningful production
policy/stream/ownership/source/coordinator tests plus independent pixel evaluation.
Every discovered defect needs an old-policy failure and fixed pass, without
weakening gates. Both independent reviewer and separate red team inspect design,
final exact root source and actual local APK, then each hosted confirmation.
Root runs npm check, full225+new Android/JVM/assemble/strict lint and actual
signature/permissions/manifest/backup/8SQL/DEX audit. Retain a unique CP11 APK,
ignored `.artifacts/cp11-validation.json`, and update existing draft PR1.
Native provider/codec/EXIF rendering, gestures, fonts/TalkBack, memory/thermal/
large-library/low-storage/process/power-loss behavior remains blocked until
observed on identified hardware. No main merge, production signing/publication,
paid calls, provider connection, schema migration or deletion. Roll back using a
retained compatible development APK with app data/history preserved; the analysis
is session-only and never resets catalogue/protection/recovery records.
