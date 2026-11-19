# Still product plan and technical architecture

Version 1.0 · 19 October 2026 · Planning and implementation specification

Still is a general-purpose phone gallery that helps people find, organise, improve, preserve and enjoy their photos and videos. Intelligent cleanup is a central capability, alongside cloud-assisted search, creative editing, document conversion, selective backup and meaningful memories.

This document defines the complete product direction before implementation. It preserves the full brainstorm, separates explicit requirements from proposed additions, and sets the contracts and safety rules that keep development coherent. Delivery in stages is an engineering sequence, not permission to quietly remove features or replace the vision with a small cleanup utility.

**Current authorisation:** prepare this architecture and the development workflow. No app implementation, repository changes, provider purchases or account connections are authorised by this document. The working name and visual identity still need availability checks before publication.

## Contents

1. Product decisions and scope
2. Complete feature inventory
3. Connected user journeys
4. Design and interaction principles
5. Technical approach and system boundaries
6. Domain modules and contracts
7. Data model and ownership
8. Asset and indexing lifecycle
9. Cleanup and deletion safety
10. Backup and recovery
11. Shared AI execution architecture
12. Search organisation and memories
13. Photo editing and try on
14. Document conversion
15. Creative video editing
16. Security privacy and sharing
17. Reliability performance and cost
18. Acceptance criteria and release gates
19. Build sequence and parallel work
20. Agent development workflow
21. Decisions required before implementation
22. Evidence and source register

## 1 Product decisions and scope

### 1.1 What the product is

The starting experience is a normal, dependable gallery for the photos and videos on a phone. The user should be able to browse, open, select, organise, search and share media without first learning an AI workspace. More capable tools appear in context, when a selected item or task makes them useful.

Cloud AI is encouraged wherever it improves quality, accuracy or functionality. Local thumbnails, metadata, caching and efficient indexing are implementation choices for speed and cost. They are not an offline-first restriction on the product. A network outage must be explained honestly while locally available media remains usable.

The gallery is general-purpose. Work photos are one example of repetitive media, not the product's audience definition. The same tools should work for family pictures, travel, pets, screenshots, documents, hobbies, events and everyday life.

### 1.2 Confirmed requirements

These requirements come from the product discussion. They form the controlling direction for the plan.

| ID | Requirement |
|---|---|
| R01 | Provide an everyday phone gallery, with the intention of using it as the normal/default photo app where the operating system permits. |
| R02 | Detect exact duplicates and visually similar photos across different times, rather than limiting comparison to a short burst window. Help select extras and keep good photos. |
| R03 | Recognise and organise screenshots, including important or sensitive screenshots; do not assume visual similarity means a picture is disposable. |
| R04 | Use online services and cloud AI when they improve the app; do not force important features to work entirely offline. |
| R05 | Keep the product general-purpose, rather than specialising in one job or subject. |
| R06 | Include a reference-guided editor for hairstyles and outfits using the user's own photo and a reference image. |
| R07 | Check generated edits before delivery using human-perceptual likeness and quality, allow bounded improvement attempts, retain the best result, and show the best available usable output rather than reject it merely for imperfect pixel similarity. |
| R08 | Provide a selected-photo or selected-album Back up action plus an automatic backup setting that can be turned on or off. |
| R09 | Convert photos of text and text screenshots into PDF, Word and plain text. |
| R10 | Let AI make creative video-editing decisions from the user's prompt, with tools/rendering behind it, follow-up revisions, and an emphasis on quality, speed and low overall cost. |
| R11 | Use quiet, premium, simple UI, inspired by the restraint of Linear and Vercel; avoid an all-black default and generic template-like presentation. Keep detailed screen layouts a surprise for production. |
| R12 | Use natural, specific wording throughout the interface and backend-derived messages. Avoid stock AI phrases such as “At a glance” and “By the numbers.” |
| R13 | Make interaction tactile, with motion that responds to touch and can be interrupted. |
| R14 | Allow the whole gallery app to be locked. |
| R15 | Resurface anniversary memories selectively; skip mundane pictures and days without a meaningful memory. |
| R16 | Plan the complete feature set, workflows and architecture before building, to reduce scope churn and tangled code. Deliver the plan as a downloadable architecture.md file. |
| R17 | Carry the applicable development workflow from the existing repository's AGENTS.md and CLAUDE.md into this architecture. |
| R18 | Use Still and the two-photo-frame logo concept as the working brand direction. |

### 1.3 How to interpret proposals

**Required** means explicitly requested or clearly accepted. **Supporting design** means an engineering or product rule needed to make a required feature reliable. **Proposed** means discussed and retained in the inventory, but not individually approved. Asking for more ideas is not approval of every idea offered.

All proposed features remain visible in this document. Their sequence reflects dependencies and risk; it does not erase them. Before implementation, confirm the proposed features as a group or make explicit changes in the decision log. No screen-by-screen design approval is required.

### 1.4 Boundaries and corrections

- A third-party gallery can become a person's everyday gallery without controlling every system photo surface. Android OEM gallery privileges and system pickers are separate platform concerns. iOS also has its own integration limits. Do not promise universal operating-system replacement.
- “Best available output” does not mean inventing a successful generation after a provider error, presenting corrupt media, or bypassing content-safety restrictions. Return a usable prior attempt when one exists; otherwise show the real failure and preserve the inputs.
- Similarity does not prove redundancy. Before/after states, a person's changing expression, a sequence of instructions, different document pages and original/edit relationships can all be meaningful.
- Turning off automatic backup stops automatic transfers. It does not delete existing cloud copies, disconnect the account or disable manually requested backup.
- An app lock protects access through Still. It does not hide ordinary phone-library photos from other apps. A separate encrypted vault is a distinct proposed feature.
- Do not turn the app into a specialist work-reporting product. Earlier job-specific suggestions are superseded by the explicit general-purpose direction; their reusable ideas survive as collections, comparisons and document export.

## 2 Complete feature inventory

Delivery tracks below are dependency groups: **Foundation**, **Intelligence**, **Creation**, **Preservation** and **Extensions**. They are not separate apps, separate approval stages or a promise that only Foundation will ship.

### 2.1 Everyday gallery and control

| ID | Capability and outcome | Status | Dependencies and delivery track |
|---|---|---|---|
| F01 | Phone photo/video grid, full-screen viewer, zoom, playback, metadata and sorting | Required R01; supporting basics | Asset access, thumbnails, local catalogue; Foundation |
| F02 | Albums, favourites, bulk selection, batch actions and ordinary sharing | Supporting design | F01, permission-aware mutations; Foundation |
| F03 | Reversible selection review, protected keepers and clear deletion scope | Supporting R02 | Asset references, deletion coordinator; Foundation |
| F04 | Whole-app lock with biometric/device credential options and automatic relock | Required R14 | Session protection and lifecycle handling; Foundation |
| F05 | Separate encrypted private vault | Proposed | Key management, verified import/export, recovery policy; Preservation |
| F06 | One task centre for uploads, edits, conversions, cancellations, retries and costs | Supporting design | Durable job state machine; Foundation |
| F07 | Editing history, original/version relationships and restore-to-earlier-version | Proposed; original preservation required for creation | Version graph and immutable originals; Foundation to Creation |
| F08 | Collections referencing existing items without duplicate files | Proposed | Collection membership; Foundation |
| F09 | Text notes and optional voice notes attached to a photo | Proposed | Annotation storage, recording consent, export; Extensions |
| F10 | Full-screen swipe review with keep/delete suggestions and final batch confirmation | Proposed | F03; Intelligence |

### 2.2 Finding and organising

| ID | Capability and outcome | Status | Dependencies and delivery track |
|---|---|---|---|
| F11 | Exact duplicate and near-duplicate groups across the accessible library | Required R02 | Checksums, perceptual features, similarity index; Intelligence |
| F12 | Best-shot recommendation with a reason, adjustable grouping and synchronised zoom comparison | Supporting design; comparison controls proposed | F11, quality assessment, user override; Intelligence |
| F13 | Screenshot categories such as receipts, bookings, instructions and review-sensitive material | Required R03; category set proposed | OCR, classification, sensitivity filter; Intelligence |
| F14 | Natural-language search and Find similar for people, objects, outfits or places | Proposed | Hybrid search, source scope, optional private person clusters; Intelligence |
| F15 | Search text within screenshots and photographs | Proposed; reused by R09 | OCR index, exclusion controls; Intelligence |
| F16 | People and pets groups with optional naming, merge/split and correction | Proposed | Consent, person/pet clustering, sensitive metadata safeguards; Intelligence |
| F17 | Living albums defined by a saved rule or phrase | Proposed | F14, rule versioning and incremental indexing; Extensions |
| F18 | Search connected backup catalogues and show where a missing photo lives | Proposed with positive response | Backup adapters, authorised remote inventory; Preservation |
| F19 | Show source app or original link when actually available | Proposed | Provenance fields; Foundation to Extensions |
| F20 | Learn private selection preferences from explicit corrections | Proposed | Opt-in feedback, reset, bounded ranking changes; Extensions |

### 2.3 Creating and understanding

| ID | Capability and outcome | Status | Dependencies and delivery track |
|---|---|---|---|
| F21 | Hairstyle/outfit reference try-on and side-by-side looks | Required R06; try-on collection proposed | Image edit adapter, perceptual review, version graph; Creation |
| F22 | Bounded generate/check/improve loop retaining the best usable result | Required R07 | Evaluation rubric, budget and attempt ledger; Creation |
| F23 | Crop, lighting, distraction removal and consistent batch edits | Proposed | Non-destructive recipes, preview and export; Creation |
| F24 | Saved editing styles reusable across selected photos | Proposed | F23, style presets independent of originals; Extensions |
| F25 | PDF, DOCX and text conversion from one or multiple text photos | Required R09 | OCR/layout engine, document model, export validation; Creation |
| F26 | Screenshot sequence ordering, overlap removal and readable document assembly | Proposed | F25, provenance-aware stitching; Extensions |
| F27 | Translate a sign, identify an object or explain a screenshot | Proposed | Image question adapter, uncertainty and no hidden actions; Extensions |
| F28 | AI-directed video editing with revisions and fast previews | Required R10 | Media analysis, validated edit plan, rendering; Creation |
| F29 | Search inside videos and jump to the matching moment | Proposed | Scene/transcript indexing, time ranges; Creation to Extensions |
| F30 | Editable highlight reels from selected albums | Proposed | F28, diverse selection, licensed/user-supplied audio; Extensions |
| F31 | Group-photo blink correction using a real alternate shot | Proposed | Face-region alignment, reference provenance, consent; Extensions |
| F32 | Restore faded printed photos while preserving the scan | Proposed | Image restoration adapter, fabricated-detail disclosure; Extensions |
| F33 | Destination previews for profile crops, wallpaper and print quality | Proposed | Export dimensions, crop preview, resolution checks; Creation |
| F34 | Recipe collections and visual style boards from selected inspiration | Proposed | F08, OCR and layout, source links when known; Extensions |

### 2.4 Preservation sharing and memories

| ID | Capability and outcome | Status | Dependencies and delivery track |
|---|---|---|---|
| F35 | Back up selected photos/albums and verify cloud copies | Required R08 | Provider account, resumable transfer, checksums/manifests; Preservation |
| F36 | Automatic backup toggle, source inclusion, video choice and Wi-Fi/mobile data controls | Required toggle; supporting settings | F35, OS scheduler, policy snapshot; Preservation |
| F37 | Storage review with large media and safely backed-up phone-copy removal | Proposed | F35, restore verification, explicit deletion scope; Preservation |
| F38 | Meaningful anniversary memories with hide/feedback controls and optional reminders | Required R15; controls supporting | Capture dates, event grouping, memory preferences; Intelligence |
| F39 | Then-and-now comparisons for a person, pet or place | Proposed with positive response | F38, matching and user correction; Extensions |
| F40 | Editable annual photo book with varied moments and export | Proposed | F38, print-ready document/export pipeline; Extensions |
| F41 | Sharing checks for location, addresses, credentials, faces and plates, with redaction | Proposed | Local sensitivity scan, reversible preview, safe export; Extensions |
| F42 | Shared event albums and private share links | Proposed | Separate sharing service, membership and revocation; Extensions |
| F43 | Temporary-photo reminders and screenshot actions such as copy, translate or open a link | Proposed | Per-item reminders, OCR, deliberate external action; Extensions |
| F44 | Choose AI access per album/item and exclude selected media from analysis | Supporting consent; detailed controls proposed | Consent policy, exclusion invalidation; Foundation |
| F45 | Portable originals, edits, collection manifests, notes and account export | Supporting design; extended export proposed | Versioned portable manifest and provider restore; Preservation |

**Scope treatment:** F01–F45 are retained. An implementation plan must show each as committed, explicitly deferred with a reason, or explicitly declined. Nothing moves out of scope simply because it is in Extensions. Prioritise required journeys first while preserving shared contracts for the proposed capabilities.

## 3 Connected user journeys

These describe behaviour and information flow, not a prescribed screen layout.

### 3.1 Open and use the gallery

1. Open Still and unlock if configured.
2. Grant the appropriate photo access or continue with selected items. Explain what access enables; handle denial without a blank or broken app.
3. See the available local media promptly. Indexing continues in the background and clearly states its scope.
4. Browse, search, play video, select items or enter a relevant tool.
5. Cloud AI consent and backup connection are separate decisions. Neither is a prerequisite for viewing permitted phone photos.

### 3.2 Review similar photos and recover from a mistake

1. Choose the library, album or date range to review, or open a prepared suggestion.
2. See exact-copy groups separately from visually related groups. Each group shows the suggested keeper and a short reason.
3. Compare photos together; change the keeper, protect any item, or mark a group as containing different moments.
4. Review the final proposed removals and their precise scope. The selected keeper and required source versions cannot silently enter the removal list.
5. Confirm the batch, then complete any required operating-system confirmation.
6. See per-item results and available recovery actions. A partial failure never appears as a fully completed cleanup.

### 3.3 Back up and free phone space

1. Select items and tap Back up, or enable automatic backup and choose what it includes.
2. Choose an authorised provider/account and review network and capacity information.
3. Upload resumably; verify the resulting cloud object and manifest before showing Backed up.
4. Turning automatic backup off stops its new work and pauses/cancels its in-flight work safely; manual jobs remain distinct.
5. If Free up phone space is used, show only eligible verified copies, the provider destination and any source-dependency warnings.
6. Remove phone copies only after deliberate approval. Cloud copies remain. Restore can download an original without creating an uncontrolled duplicate loop.

### 3.4 Try a hairstyle or outfit

1. Choose the base photo, a reference and the specific property to borrow.
2. Review the cloud-processing scope and estimated cost range, then start.
3. Generate a candidate anchored to the original; assess recognisable likeness, requested change, unintended changes and obvious defects.
4. If worthwhile and within budget, refine. Keep earlier good candidates even if a later candidate is worse.
5. Show the best usable result alongside the original, with a plain qualification if imperfect. Save a separate version or request a revision.
6. If all attempts fail technically, keep the project and explain the failure. Do not claim an image exists or substitute an unrelated face.

### 3.5 Convert text photos into a document

1. Select document photos or screenshots; choose PDF, Word or Text.
2. Arrange page order and correct cropping/rotation. Offer sequence stitching only when appropriate.
3. Inspect extracted text beside the source, with uncertain regions and missing-content warnings.
4. Correct important words, keep the chosen output mode and export to the chosen destination.
5. The new document records its source pages internally; the original images remain untouched.

### 3.6 Ask AI to edit a video

1. Select clips and describe the desired story, length, tone, aspect ratio or changes.
2. AI analyses relevant footage, creates a structured editing plan and uses a render engine to make a quick preview.
3. Automated technical checks and perceptual review inspect the actual preview. Bounded repairs fix detected problems.
4. The user requests changes in ordinary language. Only the affected plan/segments are rebuilt when possible.
5. Save a new full-quality version. The source footage, prompt history and editable plan remain linked.

### 3.7 Find and organise something remembered

Search with a phrase, text from an image or a similar picture. Results identify their source, local/cloud availability and why they match when useful. The app searches only authorised, indexed sources and shows coverage; it does not imply a missing result proves the photo is gone. A saved search can later become a living album without duplicating files.

### 3.8 Return to a meaningful memory

Use reliable capture dates and event context to select a small, varied set. Exclude hidden people/dates, vault items, ordinary screenshots and low-value repetitions. Let the user save, hide, correct or turn a memory into a comparison or reel. When there is nothing meaningful, show nothing. Notifications are optional.

### 3.9 Put an item in a private vault

For the proposed vault, first create an encrypted copy and verify it can be decrypted. Explain that the original phone copy and any existing backups still exist. Removing those copies is a separate explicit action through their own providers. Recovery setup is part of vault activation; a lost unrecoverable key means a real risk of permanent loss.

## 4 Design and interaction principles

### 4.1 Brand and visual direction

**Working name:** Still. Treat it as provisional until app-store, domain and trademark checks are completed for intended markets.

**Working logo:** two softly rounded photo frames, slightly offset, whose negative space suggests an S. Muted deep blue on warm ivory; no generic camera or AI-sparkle motif. A small in-app frame-alignment motion can echo the tactile interface. Final artwork must pass small-icon, monochrome and accessibility checks.

Use a warm light default, charcoal text, restrained greys and a muted accent. Photos supply most of the colour. The premium quality should come from proportion, type, spacing, precise alignment and responsive interaction rather than oversized banners, decorative dashboards or ornamental gradients. A dim viewer and an accessible dark theme can exist without making the whole product black by default.

Do not settle a final screen composition in this document. Production may choose the navigation and layouts, provided the journeys, accessibility and state visibility below remain intact.

### 4.2 Motion and accessibility

- Photo opening/closing should retain spatial continuity with its place in the grid.
- Gestures may include pinch-to-resize, drag selection, comparison sliding and swipe-down dismissal. Essential actions also need buttons/accessibility actions.
- Motion follows the user's gesture, can reverse or stop immediately, and never delays the next action.
- Haptics are restrained and optional. Reduced-motion mode removes unnecessary movement without removing state information.
- Support screen readers, large text, clear focus order, sufficient contrast and comfortable touch targets. Selection, backup state and confidence cannot rely on colour alone.
- Preserve scroll position, selection and an unfinished review after interruptions. Unexpected jumps feel worse than a modest animation.

### 4.3 Language and information rules

Use direct labels: Photos, Find similar, Compare, Keep this photo, Back up, Remove from phone, Retry and Your video is ready. Avoid slogans and generic headings such as “At a glance,” “By the numbers,” “Unlock your potential” and “AI-powered transformation.”

Be precise about uncertainty: “This may be the same moment,” “3 words need review,” or “12 photos are waiting for Wi-Fi.” Do not show fake percentages while waiting for a provider. Put important technical limits in plain language at the decision point, not only in a policy page.

Centralise UI strings, error mappings and notification templates. Backend codes return structured facts; the client owns reviewed human wording. Model-generated text is never the source of button labels or destructive-action confirmations.

## 5 Technical approach and system boundaries

### 5.1 Recommended platform strategy

**Recommendation:** start with a native Android application using Kotlin and Jetpack Compose, backed by cloud services, if Android is confirmed as the first target. A full gallery depends heavily on media permissions, lifecycle, background work, large-library performance and secure storage. Native access reduces risk in these areas. This is a proposed implementation choice, not an assumption that the user has selected Android or approved a stack.

Use typed interfaces so an iOS Swift/SwiftUI client can share backend contracts and product rules without pretending Android and iOS have identical permission or deletion behaviour. A web companion can later support viewing, administration or shared albums; a PWA should not be assumed to provide equivalent phone-library access.

Android's privileged SYSTEM_GALLERY role is OEM/system-app territory. A third-party gallery can integrate through ordinary media APIs and applicable intents; the exact default/open-with behaviour must be verified on target devices. Broad access requires appropriate permissions and Google Play eligibility. Selected-photo access must remain a tested fallback. [Android roles](https://source.android.com/docs/core/permissions/android-roles)

On Android, use MediaStore content URIs rather than broad filesystem paths. Handle Android 13+ image/video permissions and Android 14+ selected-photo access separately; recheck access after foregrounding and support reselection. Never interpret reduced permission as deletion. A genuine gallery can be eligible for broad media access, but Play review is a real release gate. Do not request all-files access merely for convenience. [Shared media](https://developer.android.com/training/data-storage/shared/media), [selected access](https://developer.android.com/about/versions/14/changes/partial-photo-video-access), [Play policy](https://support.google.com/googleplay/android-developer/answer/16935362?hl=en)

On iOS, use PhotoKit/PhotosPicker with their full, limited and add-only distinctions. Apple's documented default-app categories do not include a gallery replacement. Newer PhotoKit background-upload APIs exist, with full-access and OS-version conditions; verify stable SDK availability rather than declaring all background backup impossible. Most importantly, deleting from Photos can also delete from iCloud Photos and other devices. A PhotoKit delete must never be labelled Remove from this phone only. [Limited library access](https://developer.apple.com/documentation/PhotoKit/delivering-an-enhanced-privacy-experience-in-your-photos-app), [default apps](https://support.apple.com/en-ie/121430), [background upload](https://developer.apple.com/documentation/photokit/uploading-asset-resources-in-the-background), [iCloud deletion](https://support.apple.com/en-us/104967)

### 5.2 Deployment shape

Use a **modular application backend with separately scalable workers**, rather than a microservice for every feature:

```text
Native phone client
  Gallery UI and local catalogue
  Permission and device-media adapters
  Local analysis and durable transfer queue
  Consent, vault and user-visible task state
            |
Authenticated product API
  Catalogue metadata and user settings
  Job admission, cost reservations and event log
  Backup and provider integrations
  Sharing and export policy
            |
Durable queue and workflow coordinator
  Analysis/OCR workers
  Image editing and evaluation workers
  Video analysis and rendering workers
  Document/export workers
            |
Typed provider adapters
  AI providers   User backup accounts   Object storage

Shared infrastructure
  Relational database   Optional vector index
  Encrypted object storage   Audit/metrics   Secret manager
```

**Proposed baseline:** Room/SQLite on Android; a typed TypeScript API/control plane; PostgreSQL for durable product state; private object storage for job media and derivatives; a durable queue/workflow engine; isolated Python or specialised media workers where their libraries are useful. These are replaceable technical choices, but ownership and contracts are not optional.

Keep synchronous UI requests short. Uploads, indexing, generation, OCR batches and renders are jobs. Heavy media work must not run in the API request process or block the UI thread. A worker may be replaced without changing the product's job model.

### 5.3 Three separate data planes

| Plane | Default content and authority | Retention and failure boundary |
|---|---|---|
| Phone library | Existing originals stay in their OS-managed locations; Still indexes permitted media in place | OS permissions govern access; no mandatory duplicate of the whole library in app storage |
| Optional backup | Only selected media or explicitly included automatic-backup scope goes to the chosen account | Independently retained copies with manifests and explicit deletion rules |
| AI processing workspace | Only the derivatives, regions, frames or originals necessary for an authorised task | Purpose-bound temporary storage, separate retention/cleanup and provider consent |

The server catalogue stores metadata needed for enabled cloud features and jobs, not a mandatory mirror of every phone asset. A first opening must not upload the full library or all metadata. Server-side originals exist only for selected tasks, explicitly enabled backup or a separately chosen cloud-library feature. Cloud AI permission is not automatic-backup permission. Outputs may be downloaded into the phone gallery and the temporary cloud workspace then expires according to its disclosed policy.

### 5.4 Important boundaries

1. **Phone library is not the cloud catalogue.** Local access can be revoked, a cloud copy can outlive a phone copy, and a provider item may be unavailable.
2. **AI processing is not backup.** Temporary task uploads have a separate purpose, retention policy and deletion path.
3. **AI intent is not authority.** Models propose tags, plans, edits or candidate removals. Deterministic policy and the user govern side effects.
4. **Editing is not mutation of an original.** Preserve source assets and store derivatives/recipes as versions.
5. **Backup is a verified copy workflow.** Default deletion semantics are independent, not bidirectional mirror deletion.
6. **App lock is not encryption.** The vault must have an actual encryption and key-recovery design.
7. **MCP is a tool-connection option.** It is not the video renderer or the creative intelligence itself.

## 6 Domain modules and contracts

### 6.1 Ownership map

| Module | Owns | Must not own |
|---|---|---|
| Gallery | Viewing, selection, navigation, accessible interaction | Provider calls or file deletion logic |
| Media access | Platform permissions, stable handles, source discovery, native mutation requests | AI ranking or cloud backup policy |
| Catalogue | Canonical asset records, source references, version graph, collections | Media bytes as database blobs |
| Indexing | Checksums, metadata, OCR/features, incremental coverage | User deletion decisions |
| Search and organisation | Hybrid retrieval, rules, clustering, category corrections | Provider account tokens |
| Cleanup | Candidate groups, keeper suggestions, protected-item checks | Direct filesystem/provider deletion |
| Deletion coordinator | Scope-specific plans, confirmations, execution journal, restoration | Similarity judgment |
| Backup | Account capability, manifests, transfer checkpoints, verification and restore | Implicit delete propagation |
| AI coordinator | Admission, consent checks, model routing, bounded evaluation, budgets | Unrestricted shell or arbitrary provider access |
| Creation | Image/document/video project models and revisions | Global library mutations |
| Vault | Encrypted content, keys, unlock state and recovery | Automatic cloud AI eligibility |
| Sharing | Export/redaction, recipient access, link lifecycle | Reusing private backup ACLs as share permissions |
| Jobs | Durable status, progress events, leases, cancellation and retries | Feature-specific source-of-truth data |
| Account and policy | Identity, entitlements, consent, exclusions and preferences | Hidden expansion of data use |

### 6.2 Shared contract rules

- Use opaque IDs. Never make an OS path or temporary URL the canonical asset ID.
- Every write has an idempotency key and expected resource revision where races matter.
- Every asynchronous command snapshots the input asset versions, consent revision, cost limit, requested output and destination.
- Every provider callback is authenticated and reconciled against a known job; duplicate callbacks are harmless.
- Structured errors include a stable code, retryability, affected scope and safe recovery action. Human wording is mapped separately.
- Use additive, versioned schemas. Deprecations include migration and rollback rules. One module may not read another module's tables as a shortcut around its contract.
- Events describe facts already committed. Emit them transactionally with an outbox; consumers are idempotent and tolerate reordering.
- Derivatives carry source lineage and a policy snapshot. They inherit restrictions unless a deliberate operation changes them.

### 6.3 Illustrative command shapes

These are contract examples, not app implementation.

```json
{
  "command": "CreateMediaJob",
  "schemaVersion": 1,
  "idempotencyKey": "opaque-client-operation-id",
  "kind": "image_try_on",
  "inputs": [{"assetId": "asset-id", "versionId": "version-id"}],
  "consentRevision": 7,
  "policyRevision": 3,
  "budget": {"currency": "USD", "maxAmount": "user-approved-limit"},
  "qualityMode": "balanced",
  "outputPolicy": "new_version",
  "parameters": {"borrow": "hairstyle", "referenceAssetId": "reference-id"}
}
```

The contract validates actual numeric money and parameter types; the labels above explain their roles. A client cannot set its own provider credentials, bypass required consent or make a job free by omitting its estimate.

Important APIs include GetLibraryCoverage, QueryAssets, BuildCleanupPlan, CommitDeletionPlan, RestoreDeletion, CreateBackupJob, SetBackupPolicy, CreateMediaJob, CancelJob, ReviseCreationProject and ExportCollection. Delete plans and generation jobs use separate endpoints and capabilities.

### 6.4 Event envelope and side effect boundaries

Every durable event contains event_id, schema_version, owner_id, aggregate_id, aggregate_revision, operation_id, occurred_at and a typed payload. Events contain opaque references and necessary facts, not media bytes, credentials or signed URLs. Core events include AssetDiscovered, SourceAccessChanged, AssetVersionAdded, AnalysisCompleted, ProtectionChanged, BackupVerified, DeletionItemResolved, ConsentChanged, JobStateChanged and CreationRevisionSaved.

Consumers checkpoint their event offset and deduplicate event_id. Aggregate revisions prevent an older BackupVerified or AnalysisCompleted event from overwriting a newer source revision. A consent revision can invalidate a queued command even if the command was valid when created. An outbox commit and the associated state change form one database transaction; external provider calls are reconciled sagas, not pretend cross-provider atomic transactions.

BuildCleanupPlan returns exact target SourceObjects, expected revisions, keeper/protection checks, scope, estimated freed bytes and recovery capability. CommitDeletionPlan accepts that plan ID plus a still-valid explicit confirmation record; it does not accept free-form model text or an unvalidated asset list. AI workers never receive this commit capability.

SetBackupPolicy increments its policy revision and emits a stop/reschedule event for affected jobs. CreateBackupJob records manual versus automatic origin. Provider capability flags include supported trash/restore, checksum semantics, resumable upload, app-folder scope, quota visibility and whether deleting a source can affect a synchronised library elsewhere.

## 7 Data model and ownership

### 7.1 Core records

| Record | Essential fields and invariants |
|---|---|
| Asset | asset_id, owner_id, media_type, capture_time with source/confidence/timezone, imported_at, dimensions/duration, lifecycle_state, revision |
| SourceObject | source_id, asset_id, version_id, device/provider/account, external_handle, access_state, representation/fidelity class, byte_length, content_hash, provider_revision, last_verified_at |
| AssetVersion | version_id, asset_id, parent_version_ids, immutable source/input references, recipe/project_id, output_hash, generation provenance |
| AnalysisArtifact | asset_version_id, kind, model/pipeline_version, content/policy fingerprint, result, confidence/quality status, created_at, retention class |
| Collection | collection_id, owner_id, kind manual/smart/event, rule_version, ordering, membership references, access policy |
| SimilarityGroup | group_id, generation/revision, members and pairwise evidence, suggested_keeper, meaningful_difference flags, review state |
| Protection | asset/version target, protection kind, owner choice, inherited dependency protection, created_at |
| BackupManifest | source/version, destination/account/object, bytes/checksum, upload session, verification evidence, state, restored_from marker |
| DeletionPlan | plan_id, owner/device, exact targets/scopes, dependency/backup snapshot, expected revisions, confirmation state, expiry |
| DeletionOperation | plan/item target, attempted_at, provider receipt, outcome, recoverable_until when known, restore status |
| Job | job_id, owner, kind, immutable inputs, consent/policy snapshots, state, attempts, lease, checkpoints, outputs and errors |
| JobCost | job_id, estimate, reservation, incurred usage by provider/unit, reconciliation status, maximum budget |
| CreationProject | project_id, type, source versions, prompt/revision history, current plan, candidates, chosen result |
| ConsentPolicy | scope, purposes, providers, exclusions, revision, accepted_at, revoked_at, retention preference |
| VaultItem | opaque id, ciphertext locator, key version, encrypted metadata, integrity proof; no plaintext thumbnail in ordinary catalogue |
| Share | share_id, owner, permitted assets/versions, recipients/access level, expiry, revocation, download policy |
| Feedback | target/result, explicit action, optional ranking preference, retention and reset scope |

### 7.2 Identity and deduplication

A media concept can have multiple source copies and versions. A phone file and its verified backup may be two SourceObjects for one version. A cropped or generated edit is a different version or related asset, not an interchangeable copy.

Cryptographic content hashes identify byte-identical content; perceptual features find visually related content. Identical pixels in different encodings still require separate treatment from byte-identical files. Preserve file format, resolution, embedded metadata, animation, motion-photo companions and user-created edits before suggesting redundancy.

Representation/fidelity distinguishes original bytes, original-resolution export, location-redacted input, transcoded media, thumbnail/proxy and generated rendition. A picker result can be cloud-backed or transcoded; it must not automatically be called a local byte-identical original. An AssetVersion is immutable only when its content has been pinned: before a job reads it, verify the source revision/hash and either use a stable snapshot/handle or copy the authorised representation into bounded task staging. Recheck after reading when the source can change concurrently. If it changed, fail or rebuild the input snapshot; never mix old metadata with new bytes. No full-library staging is implied.

Do not deduplicate by filename, timestamp alone, or thumbnail equality. Provider object identifiers are namespaced by account. A restored asset should link to its manifest and source lineage so it does not immediately appear as an unexplained duplicate.

### 7.3 Data separation

Keep originals and generated media in storage, not the relational database. Keep signed URLs short-lived and out of logs. User backup accounts are destinations under the user's control; temporary AI storage is a product-controlled processing area with a limited retention class.

Device-local catalogue records should survive a process restart but are rebuildable from authorised sources and portable manifests. User annotations, edit lineage, exclusions and consent are not disposable caches. Back them up according to explicit account settings and migration rules.

## 8 Asset and indexing lifecycle

### 8.1 Lifecycle

```text
Discovered → Metadata available → Thumbnail available
           → Analysis eligible → Queued → Indexed

At any stage: access lost / source missing / quarantined / excluded
Deletion: active → deletion planned → trash confirmed → purged or restored
Cloud-only: local source removed + verified cloud source retained
```

These are independent dimensions where necessary. A photo can be indexed, backed up and locally inaccessible at the same time. Do not flatten all state into one status badge that implies more certainty than exists.

### 8.2 Incremental work

1. Discover accessible changes using platform change notifications and reconciliation scans.
2. Fetch metadata and thumbnails first. Do not read every full-resolution file before the first useful gallery view.
3. Compute hashes/features when needed, with bounded concurrency and battery/thermal controls.
4. Run OCR and low-cost classification once per immutable content version and pipeline version.
5. Upload only the representations needed for permitted cloud analysis; high-resolution inputs are requested selectively.
6. Persist coverage by source, date range and analysis type. Search and cleanup state which part of the library has been considered.
7. Invalidate affected artifacts after source changes, consent changes or model/pipeline upgrades. Avoid wholesale reprocessing without a reason and budget.

### 8.3 Reconciliation and conflicts

- **External edit:** import a new content revision; invalidate stale features; keep the earlier edit lineage only if its source remains recoverable.
- **External deletion:** mark that SourceObject missing; do not delete cloud backups or unrelated source objects.
- **Permission revocation:** stop dependent reads/transfers and immediately remove the affected source's thumbnails, OCR, embeddings, labels and search results from eligible reads. Purge or quarantine cached content according to the retention policy. An independently authorised backup may remain accessible under its own source permission, but the revoked phone grant cannot be reused. Permission loss is not evidence of deletion.
- **Multi-device album edit:** use operation IDs and per-collection revision checks; preserve both nonconflicting membership changes. Resolve conflicting order or name edits with a visible deterministic rule or a short user choice.
- **Protection vs queued deletion:** protection wins until the user explicitly reauthorises the updated deletion plan.
- **Deletion or revocation vs any delayed result:** every upload, AI, render, document and export callback rechecks source-version tombstones, owner, consent and current eligibility before publishing. Late results cannot resurrect deleted items or recreate withdrawn indexes. Quarantine/delete staging outputs and request provider cleanup where supported; keep only sanitised operational receipts needed for reconciliation.
- **New account on the same device:** partition cache and keys by owner; invalidate old jobs and signed URLs. Never attach one user's upload to another account.

## 9 Cleanup and deletion safety

### 9.1 Detection and grouping

Use a staged pipeline: size/type and cryptographic checks for exact duplicates; perceptual hashes/embeddings for candidate retrieval; targeted visual comparison for ambiguous groups. Capture time, location and source are useful clues but not rigid boundaries. A photo taken fifteen minutes later may be redundant; two photos one second apart may be meaningfully different.

Avoid unrestricted transitive clustering: if A resembles B and B resembles C, A and C are not necessarily interchangeable. Validate pairwise/representative compatibility, split mixed groups, and retain the evidence used to propose each group.

Near-duplicate assessment considers composition, subject, visible state, expression, image quality and content changes. Document pages and screenshot sequences need text/layout comparison. Compare original and edited variants explicitly rather than deleting one as a duplicate without explanation.

### 9.2 Keeper recommendations

Rank candidates by clarity, exposure, relevant subject detail, completeness, resolution, expression and the user's explicit preference signals. Do not equate “prettier” with “more important.” Show a concise reason that follows actual measurable evidence. Prefer uncertainty to a fabricated reason.

Manual favourites, explicit protection and sources required by active edit projects are excluded from automatic removal suggestions. A suggested keeper is not permission to delete every other group member. Different stages or useful alternate views remain visible and may yield multiple keepers.

### 9.3 Deletion transaction

1. Build a deletion plan against exact source/version revisions and requested scopes.
2. Check protected items, source dependencies, cloud-copy evidence, selected keepers and access permissions.
3. Present item count, recoverable space estimate, destination/scope and available restore window. Do not double-count shared bytes or assume cloud quota equals phone storage freed.
4. Require deliberate user confirmation; use the OS/provider confirmation when needed.
5. Journal each item before execution. Revalidate access and protection immediately before mutation.
6. Request recoverable trash where supported. Permanent deletion is a distinct, stronger action, never the default cleanup operation.
7. Record per-item outcomes and reconcile native/provider state. Retry only safe failed items; do not repeat uncertain destructive calls blindly.
8. Surface restore for as long as the actual OS/provider supports it. Display known expiry and any limits.

Undo is not an indefinite guarantee. A short UI undo may cancel pending work or restore from trash, but it cannot recover an already purged file without another verified copy. A backup is not assumed valid merely because an old success badge exists.

### 9.4 Distinct removal commands

- **Remove from this collection:** removes a reference only.
- **Remove from phone:** affects the specified local source, keeping independent cloud copies.
- **Delete cloud copy:** affects the selected provider/account object only.
- **Delete selected copies:** explicitly lists each source and consequence.
- **Delete everywhere:** if offered, requires a complete visible source list, stronger confirmation and honest disclosure that inaccessible third-party copies may remain.

These labels are capability-gated, not universal promises. On a synchronised system library such as iCloud Photos, deletion may propagate beyond the phone; disable a phone-only removal option unless the platform exposes a genuinely local-cache-only operation. Explain any known provider synchronisation side effects. Still's own backup adapter never adds delete propagation, but it cannot override another product's sync behaviour.

Android trash/delete/write requests are distinct native operations. Optional MANAGE_MEDIA access can suppress some OS dialogs but does not grant broad media read access and never removes Still's own deliberate review step. Batch within the current platform limit and inspect per-item results. [MediaStore](https://developer.android.com/reference/android/provider/MediaStore), [MANAGE_MEDIA](https://developer.android.com/reference/android/Manifest.permission#MANAGE_MEDIA)

## 10 Backup and recovery

### 10.1 Provider strategy

Implement a provider capability interface rather than hard-coding one service into gallery or cleanup code. Prefer a supported official upload/restore API, constrained account permissions, resumable transfers, quota visibility and stable object identity. “Large free storage” is useful only if the integration is lawful, reliable and maintainable.

**Recommended first candidate:** a user-connected Google Drive account, subject to scope and verification review. Drive is distinct from Google Photos. Do not treat the Google Photos Library API as a general-purpose gallery backend: its policy explicitly prohibits that use. [Google Photos API policy](https://developers.google.com/photos/support/api-policy)

Filen and TeraBox remain evaluated options, not assumed drop-in providers. Provider credentials, terms, quota expiry, upload limits, encrypted transfer semantics and mobile integration must pass a short proof of concept. Free allowances and conditions must be read from the actual account and current official terms before product copy promises them.

| Provider candidate | Official allowance observed on 19 October 2026 | Integration assessment |
|---|---|---|
| Google Drive | Up to 15 GB shared with Gmail and Google Photos; existing use reduces available capacity | Official Drive API; prefer drive.file for app-created/user-selected files and a visible backup folder. [Allowance](https://support.google.com/drive/answer/9312312?hl=en), [scopes](https://developers.google.com/workspace/drive/api/guides/api-specific-auth) |
| Filen | 10 GB free, subject to current new-account eligibility conditions | Public API/SDK, but credential-derived authentication and encryption require careful security/mobile review. A narrow OAuth app-folder flow was not verified. [Free account conditions](https://filen.io/hub/an-update-on-free-account-creation/), [authentication](https://docs.filen.io/docs/api/guides/authentication/) |
| TeraBox | Up to 1 TB marketed; current official free-plan page explicitly describes 1024 GB as time-limited | Official OAuth integration documentation exists, with separately requested application credentials. Partner eligibility, scopes, quotas and restore reliability remain gates. [Free-plan qualification](https://www.terabox.com/chinese?lang=zh_CN), [Open Platform](https://www.terabox.com/integrations/docs?lang=en) |
| OneDrive | 5 GB free Microsoft cloud storage, shared with other counted content | Microsoft Graph app-folder scope is a bounded additional adapter. [Allowance](https://support.microsoft.com/en-au/onedrive/microsoft-storage-faqs), [app folder](https://learn.microsoft.com/en-us/graph/onedrive-sharepoint-appfolder) |
| Dropbox | Basic 2 GB | Scoped OAuth with App Folder and supported mobile token flows; useful but modest free capacity. [Basic](https://help.dropbox.com/plans/dropbox-basic-faq), [OAuth](https://docs.dropboxapi.com/dropbox-api/docs/oauth) |

These are research observations, not promised entitlements, permanent offers or measured reliability. TeraBox's full English documentation extraction was incomplete; do not fill gaps with private or unofficial APIs. Do not create accounts or buy plans until implementation is authorised and the provider choice is confirmed.

For Drive, use normal user-visible app-created media files, not appDataFolder as a hidden photo archive. Use resumable uploads, record object IDs/revisions, and distinguish trash from permanent delete. Launch-project API quotas and possible billing must be checked; do not promise unlimited free developer API usage. [App data](https://developers.google.com/workspace/drive/api/guides/appdata), [uploads](https://developers.google.com/workspace/drive/api/guides/manage-uploads), [limits](https://developers.google.com/workspace/drive/api/guides/limits)

### 10.2 Transfer state machine

```text
Requested → Awaiting policy/network → Preparing → Uploading
          → Uploaded unverified → Verifying → Backed up

Branches: paused / cancelled / retryable failure / needs sign-in
          quota blocked / source changed / verification failed
```

Persist upload sessions and byte offsets where supported. Chunk retries use idempotent provider operations and exponential backoff with jitter. On a crash, reconcile the provider object before creating another upload.

A verified backup requires an immutable source version, matching byte count, and provider checksum or application integrity manifest appropriate to the provider. Where end-to-end checksum comparison is unavailable, use a documented read-back verification strategy and label the evidence accurately. Store originals without silent recompression. Account for sidecar/motion components and preserve exportable metadata.

### 10.3 Automatic backup policy

Automatic backup starts off. Store enabled state, included albums/folders, whether existing media or only future media is included, videos, network policy, optional charging preference, destination/account and policy revision. Enabling it requires a clear explanation of the scope. Changes do not silently apply to unrelated sources. In selected-photo permission mode, do not promise discovery of future camera media that the app cannot access.

Turning it off immediately prevents new automatic jobs and revokes their right to continue uploading. Cancel or pause active transfers at the next supported boundary; report if an already-submitted transfer finishes before cancellation is possible. Keep completed cloud objects. Manual backup is still available and has a separate origin flag.

Background execution is operating-system controlled. Show “Waiting for Wi-Fi,” “Paused by the phone,” or “Open Still to continue” when accurate, instead of promising uninterrupted background uploads. iOS support requires its own permission/version-specific design rather than copying Android assumptions.

On Android, use WorkManager for deferrable automatic work, with persisted checkpoints and constraints. User-initiated transfer jobs apply only when their actual launch conditions are met; enabling automatic backup once does not make every future upload user-initiated. Long-running workers can be subject to job quotas. Distinguish Wi-Fi from merely unmetered connectivity in both implementation and copy. [Transfer options](https://developer.android.com/develop/background-work/background-tasks/data-transfer-options), [user-initiated transfers](https://developer.android.com/develop/background-work/background-tasks/uidt), [long-running workers](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running)

Deleting a cloud copy creates a scope-specific backup exclusion/tombstone so automatic backup does not immediately recreate it. A provider switch or account disconnect does not migrate/delete prior backups. Explain retained copies and let the user separately choose migration, restoration or deletion.

### 10.4 Restore and free space

Restore downloads the original, verifies its integrity and links it to the existing asset/version. The user chooses a supported destination. Handle name collisions, existing copies, low storage and interrupted downloads. Album manifests rebuild logical collections; they cannot guarantee recreation of every vendor-specific album feature.

Free-space eligibility requires a verified, currently reachable backup of the exact version, no unresolved source dependency, no active modification and no user protection. Revalidate immediately before local removal. If the provider account disconnects or quota/access changes, stop and explain the affected items.

### 10.5 Backup is not a recovery strategy by itself

Test recovery, not just upload success. Include account token expiry, revoked access, provider-side deletion, a lost phone and a new-device restore. Export the account's portable manifest without embedding access tokens. A user should understand which assets exist only on the phone, only in cloud storage, in both places or in neither accessible source.

## 11 Shared AI execution architecture

### 11.1 Job pipeline

```text
User intent → Input and consent validation → Cost reservation
            → Minimal sufficient analysis → Typed plan
            → Tool execution → Technical validation
            → Perceptual evaluation → Bounded repair
            → Best usable result → User review → Save/export
```

The same coordinator supports image edits, OCR assistance, video creation and advanced classification. Feature workers own their domain logic. A general chat agent must not become the only implementation of the app's business rules.

The server rechecks consent and exclusions at dispatch, before each external upload and before any retry. Revocation blocks new transmissions and initiates supported deletion/cancellation. Revocation cannot make data already received by a provider disappear instantly; expose that distinction and enforce the documented retention policy.

### 11.2 Job states and cancellation

Use created, waiting_input, admitted, queued, running, evaluating, awaiting_user, succeeded, succeeded_with_limitations, failed, cancelling and cancelled. Each attempt has its own provider request ID, lease and checkpoints. Terminal output is published only after validation and durable storage.

Cancellation prevents new billable stages and asks the provider to stop where supported. An in-flight request may still complete and incur cost. Discard or retain its result according to the cancellation policy, without presenting the cancelled job as successful. Show incurred cost independently of final status.

### 11.3 Routing and bounded quality loops

- Use inexpensive deterministic/on-device steps for hashes, thumbnails, metadata and obvious grouping.
- Use cloud models for semantic judgment, creative planning and difficult content when justified.
- Prefer cached analysis keyed by content, model version and consent policy. Do not send the full library with every prompt.
- Separate the generator from the evaluation rubric. A second model or separate evaluation pass can reduce self-confirmation, but remains fallible.
- Use a configurable attempt, time and monetary ceiling. A proposed starting mode is one initial candidate plus up to two targeted improvements; benchmark before adopting it.
- Stop when improvements are marginal, the user cancels, the budget/time limit is reached or no usable path remains. Preserve the best prior valid candidate.
- Select based on a documented rubric, not a hidden model assertion that the result is perfect.
- Safety, corrupt-output and unauthorised-data failures are hard stops. Cosmetic imperfection is not.

### 11.4 Provider abstraction and evaluation

Define adapters for image edit, visual assessment, OCR/layout, transcription, semantic embedding and render execution. Each declares supported input types, size limits, regional processing, retention, cancellation, pricing units and output guarantees. Provider changes require regression tests and updated consent/disclosures when material.

Select providers through a small, representative evaluation set: varied faces, hair, clothing, skin tones, lighting, languages, screenshots, long/short clips and difficult layouts. Score user-perceived quality, task success, p50/p95 latency and **total cost per accepted result**. Low token use alone is not evidence of a better system.

Do not lock the architecture to a model family that may be deprecated. In particular, the official OpenAI documentation currently reports that the Sora 2/Videos API shut down on 24 September 2026 with no direct one-to-one replacement. Creative editing of existing footage should use an independent rendering pipeline. [OpenAI video generation status](https://developers.openai.com/api/docs/guides/video-generation)

### 11.5 Concrete initial candidates

These are recommended starting points for a controlled comparison, not measured winners or an instruction to buy access to every service. Keep model IDs, settings, regions, retention and pricing revisions in a capability registry.

| Role | Initial candidate | Alternative or selection gate |
|---|---|---|
| Hashes and media preprocessing | CPU workers with mature image decoders, OpenCV and FFprobe/FFmpeg | Pin versions, sandbox malformed inputs and review codec licensing |
| Compact visual facts | Paid Gemini 3.5 Flash-Lite | Compare another small vision model on grounded facts and cost per indexed item. [Model documentation](https://ai.google.dev/gemini-api/docs/models/gemini-3.5-flash-lite) |
| Image/text retrieval | Gemini Embedding 2 | Compare a self-hosted licensed SigLIP 2 route; keep model-specific indexes. [Embeddings](https://docs.cloud.google.com/gemini-enterprise-agent-platform/models/embeddings/get-multimodal-embeddings), [SigLIP 2](https://huggingface.co/google/siglip2-base-patch16-224) |
| OCR and layout | Google Document AI Enterprise Document OCR, selective Layout Parser | Azure Read/Layout or private OCR for sensitive/simple material. [Document OCR](https://docs.cloud.google.com/document-ai/docs/enterprise-document-ocr), [Azure Read](https://learn.microsoft.com/en-us/azure/ai-services/document-intelligence/prebuilt/read?view=doc-intel-4.0.0) |
| Creative planning and critique | GPT-6.1 Sol with compact facts and relevant frames | Compare a native-video-capable model on the same footage and review rubric. [Model documentation](https://developers.openai.com/api/docs/models/gpt-6.1-sol) |
| Hair/outfit editing | GPT Image 2.5 Sunburst as the precision candidate | Nano Banana 2.1 as a challenger; Flare as a speed candidate. No automatic downgrade to a weak multi-reference model. [OpenAI image guide](https://developers.openai.com/api/docs/guides/image-generation), [Google image guide](https://ai.google.dev/gemini-api/docs/image-generation) |
| Video rendering | FFmpeg workers executing a validated timeline | Generated shots are optional separate adapters. [Filter primitives](https://ffmpeg.org/ffmpeg-filters.html) |
| Private people grouping | An explicitly enabled, appropriately licensed face detector/embedding model | Commercial weight rights and privacy review before activation; generic scene embeddings are not identity recognition |

Avoid making Google's retiring virtual-try-on-001 endpoint a foundational dependency; the current documented retirement date is 15 March 2027. General image-edit models still need a real clothing/hair evaluation. [Model lifecycle](https://docs.cloud.google.com/gemini-enterprise-agent-platform/models/vto/virtual-try-on-001?hl=en)

## 12 Search organisation and memories

### 12.1 Hybrid retrieval

Combine ordinary metadata filters, OCR full-text search, visual/text embeddings and optional person/pet clusters. Retrieve candidates first, then rerank a small set. Show literal matches confidently; do not present a semantic guess as a confirmed person or location.

Store detected facts separately from user labels and model interpretations. User corrections override suggestions. Unknown source links remain unknown; do not infer a website URL from a visual resemblance. Sensitive OCR text is not placed in general analytics, crash reports or unconstrained model prompts.

For the proposed PostgreSQL/pgvector baseline, choose embedding dimensions explicitly. Gemini Embedding 2 defaults to 3,072 dimensions, while pgvector HNSW supports ordinary vector up to 2,000 and halfvec up to 4,000. Start by evaluating requested 1,536-dimensional vectors against 3,072-dimensional half-precision storage; never define unsupported vector(3072) HNSW indexing. Store model/config identity and measure recall after reduction or quantisation. [Embedding dimensions](https://docs.cloud.google.com/gemini-enterprise-agent-platform/models/embeddings/get-multimodal-embeddings), [pgvector limits](https://github.com/pgvector/pgvector#hnsw)

### 12.2 Screenshots and living albums

Categories are suggestions with multi-label support. A booking screenshot can also be travel material; a screenshot with a password should be review-sensitive without copying its secret into the category title. Classification cannot decide the real importance of a credential or document.

Living albums are saved, versioned queries plus explicit inclusions/exclusions. New matches appear without copying files. Users can inspect why a photo matched and remove it from that album without deleting it. Rule upgrades must not silently rewrite a carefully curated collection.

### 12.3 Meaningful memories

Use capture-date provenance, favourites, revisits, explicit saves, familiar subjects, event cohesion, variety and user feedback as possible signals. Avoid relying on face presence alone. A meal table may matter at a birthday; an isolated routine object usually should not trigger a memory.

Hard exclusions include hidden dates/people, excluded albums, vault items, sensitive documents and user-suppressed media. Prevent notification duplicates across timezones and devices. Download dates do not substitute for capture dates without a visible qualification.

The system may decline to select a memory on any day. Keep the feature calm and optional, with no engagement streaks or pressure to share. Then-and-now and photo books reuse curated events and diversity selection rather than creating independent indexing systems.

## 13 Photo editing and try on

### 13.1 Project inputs and preservation

A try-on project identifies a base image, one or more references, the intended change and protected attributes. For hair, preserve identity, expression, clothing and background as far as possible. For clothing, preserve the person's identity and body representation without claiming a true size/fit prediction.

When the base photo contains multiple people, select the intended person explicitly. The reference supplies hairstyle or clothing, not a replacement identity. Check usable resolution and occlusion before spending on generation; weak inputs may be usable with a warning rather than an unnecessary hard block.

Where supported, masks and regional editing constrain change. Masks are suggestions, not a guarantee that all outside pixels stay unchanged. Re-anchor each improvement to the original and the explicit requested changes, rather than repeatedly editing an increasingly drifted result.

### 13.2 Evaluation rubric

| Dimension | What the checker assesses |
|---|---|
| Recognisable likeness | Does the person still look like the base person to a human viewer? Do not require pixel equality. |
| Requested change | Does the hairstyle/outfit meaningfully reflect the selected reference and instruction? |
| Unintended changes | Has the face, expression, body, background or unrelated clothing changed unnecessarily? |
| Visual integrity | Obvious malformed anatomy, hair boundaries, texture errors, unreadable added text or inconsistent lighting |
| Usefulness | Is the result a useful preview even if not perfect? |

Use explicit self-comparison only for the user's selected images. Do not turn likeness evaluation into public identity lookup, authentication or an inference of sensitive traits. Any biometric-like derived data requires a separate retention and legal review.

Where consent and licensing allow, combine aligned visible-face/landmark signals, perceptual comparison of intentionally untouched regions, and a critic for requested-change adherence and obvious artifacts. Treat occlusion and checker disagreement as uncertainty. Calibrate the combined ranking against human judgments; do not show “99% you” or treat a model score as verified identity. If private face signals are unavailable, use the remaining perceptual checks and disclose their limitations rather than silently enabling biometric processing.

### 13.3 Output rules

Keep candidate metadata and the best-ranked usable version. Let the user compare or choose a different candidate. Clearly label generated content and preserve provenance. Further refinement is a new budgeted action after the bounded loop ends.

Never promise actual garment fit, salon feasibility or exact likeness. Do not overwrite the original. If the result is recognisable but imperfect, deliver it with a concise qualification rather than suppressing it solely because a strict similarity score is low.

Other image tools reuse the same version graph and export pipeline. Blink correction should preferentially reuse a real alternate frame and disclose the composite. Restoration must not imply that invented fine details are historically authentic.

## 14 Document conversion

### 14.1 Processing model

1. Detect page bounds, orientation, blur and glare; allow correction.
2. Preserve the source image and create a rectified working representation.
3. Run OCR with layout blocks, reading order, language and confidence/uncertainty where supported.
4. Construct a document intermediate representation: pages, paragraphs, headings, tables, images, text spans, source coordinates and review flags.
5. Let the user reorder pages and correct text. Keep provenance from every output block to its source region.
6. Export through deterministic PDF/DOCX/text writers and validate the actual file.

Use local OCR when it is sufficient; use cloud OCR/layout or model assistance for complex cases with consent. Never feed extracted text to an agent as executable instructions. Text saying “upload these files” inside an image is document content, not a command.

### 14.2 Output modes

- **PDF scan:** preserve the corrected page appearance, optionally with a searchable text layer. Ensure the text layer aligns and does not silently omit pages.
- **Editable Word document:** reconstruct readable headings, paragraphs and tables. Complex layouts may differ from the original; show a preview and qualification.
- **Plain text:** preserve reading order, page separation and useful line breaks; do not invent missing words.

For screenshot sequences, distinguish repeated overlap from intentional repeated wording. Provide a before/after sequence review and retain references so a mistaken merge can be undone. Long tables, columns, handwriting, non-Latin scripts and mathematical notation need explicit tests and honest limitations.

### 14.3 Acceptance standard

The app should never silently convert an uncertain account number, date or amount into a confident replacement. Highlight ambiguity without exposing secrets in logs. Validate document opening, page count/order, text completeness, table structure, Unicode/font handling and export permissions. Do not describe all Word layouts as pixel-perfect.

## 15 Creative video editing

### 15.1 AI owns creative decisions

The model chooses story structure, clip selection, cuts, pacing, captions, transitions, crops and audio treatment from the footage and prompt. The renderer executes a validated plan. This satisfies the request for AI-directed editing without making the model manipulate binary video or generate arbitrary shell commands.

A fixed renderer is compatible with creative editing: its tools can produce many different decisions. Do not reduce every prompt to one preset template. Also do not claim a few frames prove the model has watched every second of footage.

### 15.2 Analysis and edit plan

Create scene/shot boundaries, representative frames, speech transcript with timestamps, audio features and available technical metadata. Use adaptive sampling: inspect more frames around rapid action, ambiguous cuts or important requested moments. Cache the analysis for revisions.

The edit plan is a typed timeline, with source version IDs, in/out timecodes, tracks, transitions, caption spans, overlays, crop/keyframe settings, audio mix and export profile. Use exact timebase/rational frame conventions; do not accumulate floating-point rounding across clips.

Validate the plan before rendering: source existence, permission, time ranges, duration, supported effects, text bounds, aspect ratio, rights to audio, resource limits and budget. Only allowlisted tool operations are executable. MCP may expose those same typed tools if a provider benefits from it; direct APIs are the default simplification, not a universal speed claim.

### 15.3 Preview and quality loop

Render a lower-cost preview, then inspect the actual output for decode errors, black/frozen frames, clipped captions, abrupt audio, sync, pacing and prompt compliance. Technical checks are deterministic where possible; perceptual review considers creativity and coherence. Repair the plan rather than repeatedly re-analysing all source footage.

Show the best usable preview within the bounded budget, with optional refinement. A user revision creates a new plan version and identifies affected segments. Reuse unaffected proxy media, transcripts and renders. Full-quality export uses originals, appropriate codecs, frame rates, colour handling and the chosen destination profile.

Proposed starting limit: no more than two automatic video critique/refinement passes after the first preview, with a stricter time/cost cap taking precedence. Additional user revisions create a fresh bounded revision budget. Measure whether the second repair pass actually improves accepted output before making it a default.

### 15.4 Media engineering limits

Test variable frame rate, rotation metadata, HDR/SDR conversion, colour profiles, mixed resolutions, long clips, silent clips, interrupted uploads, codec support, subtitles and audio normalisation. Preserve capture provenance where appropriate. Do not bake in licensed songs without rights; user-selected audio may still carry usage restrictions.

Generated B-roll is an optional, separately priced adapter. It is not a dependency of editing existing videos, and it should not be confused with factual footage from the user's life. Video search reuses the same time-indexed analysis and returns the relevant segment rather than only a whole-file hit.

## 16 Security privacy and sharing

### 16.1 Consent matrix

| Action | Required product disclosure and control |
|---|---|
| Browse phone media | OS access scope, selected/full access distinction, revocation handling |
| Cloud analysis | Selected media/representations, purpose, provider, retention and exclusions |
| Automatic analysis | Explicit included scope, network/cost policy, stop control and revalidation |
| Backup | Destination account, included items, network policy, copy semantics and restore limitations |
| People grouping | Private grouping purpose, optional names, correction, deletion and reset controls |
| Try-on | Base/reference transfer, generated nature, likeness limitations and cost |
| Shared album/link | Exact items/versions, recipient/audience, permissions, expiry and revocation |
| Vault recovery | Key model, recovery responsibility and consequences of loss |

Use purpose-bound consent. Permission to back up does not authorise AI analysis. Permission to analyse selected photos does not authorise sharing them with friends or indexing the entire library. A provider change with materially different data handling may require renewed consent.

No-training and no-retention are different claims. Verify each endpoint, paid/free tier, region and retention configuration. For example, an API store=false setting is not by itself a universal zero-retention guarantee; some zero-retention configurations require approval or feature-specific setup. Do not promise privacy properties from the provider brand alone. [OpenAI data controls](https://developers.openai.com/api/docs/guides/your-data), [Google retention guidance](https://ai.google.dev/gemini-api/docs/zdr?hl=en)

### 16.2 Sensitive content and AI exclusions

Use local preprocessing to flag likely credentials, recovery codes and other high-risk content before external transmission. This is a targeted safety boundary, not an offline-only restriction. Keep such content out of default cloud analysis and require a dedicated safe handling decision. Do not send passwords or tokens to general model endpoints.

Excluded albums/items must be removed from eligible queues and search indexes as specified by the exclusion choice. Clarify whether exclusion means no future analysis, removal of existing analysis, or both; default to the safer complete exclusion. Vault content is excluded from analysis, memories, previews and sharing unless explicitly unlocked and selected for a specific action.

### 16.3 App lock and encrypted vault

Prefer platform biometric/device credential facilities. Relock on the chosen inactivity/background policy and hide sensitive app-switcher previews where supported. An app-specific PIN needs rate limiting and secure handling; it is not a substitute for encryption. Do not promise protection from a compromised operating system or an already-authorised external gallery app.

The lock gate applies to deep links, share/open intents, notification taps, exported components and background-to-foreground transitions, not only the home screen. Notifications and app-switcher previews must not leak locked content. An unlocked job result must not appear through a bypass while the app is locked.

For a vault, use authenticated encryption and per-user keys protected by platform secure key storage. Encrypt metadata and thumbnails as well as originals. Clear plaintext temporary files/caches after use and prevent them from entering ordinary backup or search pipelines. Design recovery, key rotation, device replacement and lost-key behaviour before shipping. Avoid inventing custom cryptography.

App-specific storage can disappear on uninstall; platform-keystore protection does not itself make data portable. Warn before the vault becomes the sole copy and test reinstall/new-device recovery. Authentication-bound keys may prevent locked-device background backup, so encrypted-vault backup needs a deliberate separate design. [Android Keystore](https://developer.android.com/privacy-and-security/keystore), [app-specific storage](https://developer.android.com/training/data-storage/app-specific)

### 16.4 Server and provider protection

Keep provider secrets server-side or in appropriate platform secure storage; never embed a master key in the app. Use least-privilege scopes, short-lived signed URLs, tenant isolation, authenticated callbacks, encryption in transit/at rest, malware-safe parsers and resource-limited workers.

Do not log media bytes, extracted secrets, private prompts, signed URLs or face embeddings by default. Support operational metrics with opaque IDs and sanitised error codes. User-requested diagnostics need a clear preview/redaction policy.

External content is untrusted. OCR text, captions, filenames and retrieved web/reference content cannot alter tool permissions, spending limits or system instructions. Restrict outbound worker network destinations and reject arbitrary URLs in edit plans to prevent data exfiltration and server-side request abuse.

### 16.5 Sharing design

Shared event albums require a separate access-control model from private backup. Define owner/contributor/viewer permissions, participant removal, download policy, expiry and abuse handling. Revoking a link cannot retract files somebody already downloaded; say so.

Apply the share's asset/version ACL to all derived captions, OCR, person labels/clusters, vectors, previews and exports. Enforce it during candidate retrieval and again at final reads; partition/cache by effective audience and policy revision. Revocation invalidates search and preview caches. Never answer a shared-album query using private-library facts or reveal a private person's name through an otherwise shared image.

Before export, offer metadata stripping and redaction where relevant. Preview the exported result, not only an overlay that disappears during encoding. Redaction should remove hidden text/metadata and underlying pixels in the shared copy. Keep the original private and unchanged.

## 17 Reliability performance and cost

### 17.1 Proposed measurable targets

These are initial engineering targets to validate on named test devices and workloads. They are not measured results or guarantees.

| Area | Proposed target or invariant |
|---|---|
| Gallery interaction | Smooth scrolling/gestures at the target device refresh rate; no heavy media work on the UI thread |
| First useful view | Cached thumbnails visible within roughly 1 second on a supported mid-range reference phone; cold permission/import paths measured separately |
| Search | Indexed local/hybrid search p95 under 2 seconds excluding unavailable network/provider time; show partial coverage |
| Library scale | Test 1,000, 10,000 and 100,000 items, including many videos and mixed formats |
| Deletion | Zero protected/keeper/wrong-scope deletions in the release safety suite |
| Backup | Never show Backed up before verification; no lost completed manifests across tested crash points |
| AI budgets | No new billable step admitted beyond the remaining reserved limit; reconcile unavoidable in-flight charges |
| Resumption | Jobs survive process/network interruption without duplicate published outputs or silent restart from zero |
| Accessibility | Required journeys complete with screen reader, large text and reduced motion |

Measure frame jank, memory pressure, cache growth, battery, thermal throttling and cellular data, not only server latency. The right AI timeout depends on task size and provider performance; surface a realistic stage and cancellation option rather than promising one duration for every video.

### 17.2 Cost model

Estimate the total task cost from input/output tokens, image generation, OCR pages, audio/transcription duration, render compute, storage, transfer/egress and retries. Track estimate, reserved amount and actual charges separately.

Offer simple quality/speed choices only if benchmarks show meaningful differences. A lower-cost mode can use smaller previews, fewer candidate generations and more cached analysis; it must not silently change source preservation or deletion safety. Give a per-job limit and optional account/monthly limit. Reaching a limit pauses optional refinement rather than discarding a usable result.

Cache per-user artifacts safely; do not cross-share private media-derived caches between users. Reuse analysis across search, memories and editing when the purpose and consent permit. Bulk AI indexing should be incremental and scheduled, not a fresh full-library scan every time the app opens.

### 17.3 Illustrative operating costs

The following USD calculations use official rates observed on 19 October 2026 and explicitly assumed usage. They are planning examples, not measured production bills, quotes or user-facing prices. Tax, infrastructure and any components listed as extra are excluded. Recheck rates before implementation.

| Example | Assumption and calculation | What remains extra |
|---|---|---|
| Index 1,000 new unique photos | Flash-Lite at $0.30/M input and $2.50/M output, assuming 1,500 input + 250 output tokens/photo: $1.075. Embeddings at 258 tokens/photo × $0.45/M: $0.1161. Combined about $1.19 | OCR, retries, compute, index storage and transfer; only authorised unique photos are processed |
| OCR a 20-page document | Document OCR at $1.50/1,000 pages: $0.03. If Layout Parser is used on all pages at $10/1,000: another $0.20 | Cleanup, reconstruction, review, rendering, storage and retries |
| Three Sunburst image candidates with checks | Assume each candidate consumes 8,000 image-input tokens at $8/M, 500 text-input at $5/M, and 4,000 image-output at $30/M: $0.1865 each, $0.5595 for three. Three Sol checks of 4,000 input + 500 output tokens at $2/$10 per M add $0.039. Combined $0.5985 | Infrastructure, different resolution/tokenisation, provider retries or additional analysis |
| Creative edit of existing footage | Sol planning/critique total of 20,000 input + 4,000 output tokens at $2/$10 per M: $0.08 | Video analysis, transcription, proxy/final rendering, storage, transfer and rerenders; generative-video seconds are not inherently required |

Rate sources: [Google API pricing](https://ai.google.dev/gemini-api/docs/pricing), [embedding token basis](https://docs.cloud.google.com/gemini-enterprise-agent-platform/models/embeddings/get-multimodal-embeddings), [Document AI pricing](https://cloud.google.com/products/document-ai/pricing), [OpenAI pricing](https://developers.openai.com/api/docs/pricing), [Sol rates](https://developers.openai.com/api/docs/models/gpt-6.1-sol).

These examples explain why caching and limiting attempts matter, but they do not prove one provider is cheapest. Compare cost per human-accepted result, including discarded attempts. When an endpoint cannot bound a billable response tightly, reserve conservatively and disclose that uncertainty before automatic execution. Do not start another stage merely because the previous estimate was low.

### 17.4 Failure and recovery table

| Failure | User-visible outcome | Recovery behaviour |
|---|---|---|
| No connection | Local media still viewable; online task waiting | Resume with current consent/policy after connection |
| Permission revoked | Explain which source is no longer accessible | Pause dependent work and rebuild eligible scope |
| Provider timeout/rate limit | Job delayed or retry available | Backoff, idempotent reconciliation, bounded retry |
| Quota full | Show affected destination and items | Stop transfers; allow another authorised destination |
| Low phone storage | Export/restore paused with required space estimate | Clean temp files safely; do not delete originals |
| Corrupt source | Identify the unreadable item | Skip only that item where valid; preserve batch results |
| Poor generation | Best usable candidate with limitation | Optional refinement within/new explicit budget |
| No valid generation | Honest failure; inputs retained | Safe retry or change inputs/provider after disclosure |
| Partial deletion | Per-item outcomes and available restore | Reconcile before any retry |
| Crash during upload/render | Task remains visible | Resume checkpoint or reconcile provider completion |
| Duplicate callback | No duplicated output or charge reservation | Idempotent event handling |
| Consent revoked mid-job | Stop new transmission and explain in-flight status | Cancel/delete according to provider capability |

### 17.5 Observability and operations

Use correlation IDs across client, API and worker stages; structured state transitions; queue age; provider error rates; cost drift; backup verification failures; and deletion anomalies. Alert on integrity/scope violations immediately. Avoid logging content to make debugging convenient.

Use feature flags and per-provider kill switches. A failed image provider should not disable ordinary gallery browsing or backup. Schema migrations are reversible where possible and tested on old data; destructive migration requires an explicit backup/recovery plan. Establish retention and deletion jobs for temporary AI files, abandoned uploads and stale previews.

## 18 Acceptance criteria and release gates

### 18.1 Requirements traceability

| Requirement | Primary design sections | Minimum acceptance evidence |
|---|---|---|
| R01 | 3.1, 5, 8 | Real-device gallery and intent tests; selected/full access and revoked-access flows |
| R02 | 3.2, 9 | Cross-time duplicate fixtures, mixed-state counterexamples, keeper/protection safety tests |
| R03 | 12, 16 | Correctable screenshot categories; sensitive screenshots excluded from default cloud submission |
| R04 | 5, 11 | Working cloud analysis/edit pipeline; outage behaviour does not redefine the product offline-first |
| R05 | 1, 2 | Diverse evaluation library; no work-niche navigation or mandatory job-specific model |
| R06 | 3.4, 13 | Base/reference try-on with distinct saved output and comparison |
| R07 | 11, 13 | Earlier best candidate survives worse retry; bounded cost/time; imperfect valid output delivered |
| R08 | 3.3, 10 | Manual backup, auto toggle, pause/restart and verified restore; off never deletes cloud copies |
| R09 | 3.5, 14 | Valid PDF/DOCX/text, multipage ordering, source comparison and uncertain-text review |
| R10 | 3.6, 15 | Different prompts produce substantive edit-plan changes; preview/render/revision tests |
| R11 | 4 | Visual review of quiet light direction; production layouts follow journeys without wireframe approval dependency |
| R12 | 4.3 | Reviewed copy inventory and error/notification wording checks |
| R13 | 4.2 | Interruptible gesture tests, reduced motion and accessible alternatives |
| R14 | 16.3 | Lock/relock, background preview and unauthorised-session tests; no claim it locks other galleries |
| R15 | 3.8, 12.3 | Meaningful-memory fixtures, mundane-day suppression, date/people exclusions |
| R16 | Whole document | Inventory/dependency review, approved change process and downloadable canonical file |
| R17 | 20 | Adapted workflow mapped to source files, ownership and review gates in place before code |
| R18 | 4.1 | Working brand included; legal/store availability remains a pre-publication gate |

### 18.2 Test layers

1. **Unit and property tests:** asset identity, grouping constraints, keeper protection, budget arithmetic, timecode math, policy evaluation and state-machine transitions.
2. **Contract tests:** native adapters, provider adapters, signed callbacks, API schemas, retries and compatible migrations.
3. **Integration tests:** discovery → indexing → search; source → backup → verify → local removal → restore; selected input → AI job → saved version.
4. **Fault injection:** kill the process at each transfer/mutation boundary; duplicate/reorder events; expire tokens; revoke consent; disconnect network; fill storage; change source content.
5. **Real-device tests:** supported Android versions/OEMs, selected access, native trash prompts, background limits, large libraries and thermal behaviour. Test iOS separately if in scope.
6. **AI evaluations:** labelled duplicates/nonduplicates, human-rated try-on likeness, diverse search relevance, OCR fidelity and video prompt-following. Pin datasets and versions; protect evaluation media.
7. **Security review:** tenant isolation, vault keys, log redaction, prompt injection, malicious media/documents, link revocation, provider scopes and deletion/retention.
8. **Accessibility and visual review:** all primary journeys, typography, touch/keyboard/screen-reader operation, reduced motion, errors and interrupted states.

### 18.3 Non-negotiable release blockers

- Any wrong-owner, wrong-source or wrong-scope read/write/delete.
- Protected photos or required originals entering a destructive batch without a new explicit decision.
- Cloud-copy deletion caused by turning automatic backup off or removing a phone copy.
- Backup success without durable verification evidence, or an untested restore path.
- Provider transmissions continuing under an outdated revoked consent revision when the next stage could have been stopped.
- Plaintext vault thumbnails/keys in ordinary caches, logs or analytics.
- A malformed AI plan escaping the allowlisted tool boundary.
- A budget loop that can continue indefinitely or repeatedly charge after cancellation.
- AI/content text interpreted as instructions to mutate data or contact external services.
- Public store copy that overstates default-gallery privileges, free storage, likeness, restore guarantees or privacy.

### 18.4 Evidence required to call a feature done

Code review and a green unit suite are necessary but insufficient. A feature completion record links its requirements, contract/schema changes, test commands/results, real-device or rendered-output evidence where applicable, known limits, rollback and exact commit. Tests that were not run are marked not run, never implied to pass.

For media/document creation, inspect the produced file, not just a successful API response. For backup, restore and verify bytes. For cleanup, test wrong-delete prevention and recovery. For a visual feature, review the actual running interface and accessible states.

## 19 Build sequence and parallel work

### 19.1 Sequence the dependencies

**Stage 0 — Decisions and risk proofs.** Confirm target platform/repository, provider/cost constraints and proposal scope. Establish the workflow from section 20. Prove gallery permissions/default integration, representative large-library access, one verified backup/restore, one try-on evaluation and one AI-directed video preview. These are risk probes, not a substitute for the complete product.

**Stage 1 — Shared foundation.** Implement media adapters, asset/version schema, durable jobs, consent/exclusions, reviewed copy system, lock, thumbnails and gallery basics. Freeze contracts for deletion, backup and creation. Integrate end to end before feature teams build on unstable IDs/state.

**Stage 2 — Cleanup and preservation.** Build incremental indexing, exact/near-duplicate review, protection, deletion journal, trash/restore, selected backup and automatic policy. Validate the complete find → review → back up → remove local → restore journey. These safety foundations serve all later tools.

**Stage 3 — Search and creation.** In parallel on established contracts: hybrid search/screenshot organisation; document conversion; try-on and image versions; AI video plans/rendering. Integrate their task states, costs, consent and exports through shared modules rather than separate feature-specific queues.

**Stage 4 — Meaningful library features.** Add memories and agreed proposals such as people/pets, collections, swipe review, living albums, video search, notes, then-and-now and saved styles. Each reuses the catalogue, index and project graph.

**Stage 5 — Advanced preservation and sharing.** Add the encrypted vault, shared events, private links/redaction, photo books and other approved extensions. Security-sensitive sharing/vault work has its own design and validation gates, rather than being treated as cosmetic add-ons.

**Stage 6 — Release hardening.** Run full compatibility, accessibility, fault, cost and quality suites; verify store permissions/disclosures, recovery documentation, provider operations, migrations and rollback. Release to a limited test group before broad distribution.

This sequence is deliberately comprehensive. Calendar estimates should follow the risk proofs and measured team throughput; no arbitrary completion date is implied here.

### 19.2 Safe parallel boundaries

| Workstream | Owns | Can start after | Integration gate |
|---|---|---|---|
| Platform/gallery | Native media access, viewing, lifecycle and device integration | Stage 0 target decision | Shared asset contract and real-device fixtures |
| Catalogue/jobs | Schemas, version graph, event outbox, consent snapshots, job state | Initial design | Versioned contracts and migration tests |
| Cleanup/search | Hash/features, groups, search, screenshot classification | Catalogue contracts | Cross-time counterexamples and deletion-plan interface |
| Backup | Providers, transfers, verification, restore | Source/job contracts | Crash-safe upload and verified restore |
| Image/document creation | Try-on, review loops, OCR model, exporters | Job/version/consent contracts | Valid actual outputs and cost bounds |
| Video creation | Analysis, edit plan, proxies, render and revisions | Job/media contracts | Timeline validator and rendered QA |
| UX/quality | Design system, interaction, wording, accessibility and independent tests | Journeys and tokens | Real-state walkthrough, no mocked completion |

Assign one owner for each shared schema or high-conflict file. Feature workers propose contract changes; the owner approves and migrates them. Avoid simultaneous edits to the same module entry points. Distinguish dependencies that block work from merge order that merely organises integration.

### 19.3 Proposed repository map

The exact package manager and build commands are established during authorised bootstrap. This map defines boundaries rather than claiming files already exist.

```text
AGENTS.md                         Shared delivery rules
CLAUDE.md                         Single reference to AGENTS.md
architecture.md                   Canonical product and architecture plan
apps/android/                     Native client and feature UI
apps/android/core/media/          Platform media access owner
apps/android/core/catalogue/      Local catalogue and migrations owner
apps/android/core/security/       Lock, vault and consent owner
apps/android/features/            Feature-scoped UI and coordinators
services/api/                     Product API and authorisation
services/workflows/               Durable job/state orchestration
workers/analysis/                 Hashes, OCR, embeddings and facts
workers/image/                    Image projects and evaluation
workers/video/                    Timeline compiler and rendering
workers/documents/                Document model and export
packages/contracts/               Versioned commands/events/DTOs
packages/providers/               AI and backup adapters
packages/policy/                  Shared policy specifications and fixtures
db/migrations/                    Server schema migration owner
tests/contracts/                  Cross-component contract tests
tests/faults/                     Interruption and reconciliation tests
tests/evaluations/                Consented media quality evaluation harness
docs/decisions/                   Short architecture decisions
docs/delivery/                    Task cards and exact-commit evidence
```

The contracts owner controls packages/contracts and coordinates matching client/server generation. The catalogue owner controls schema changes; the supervisor controls dependency manifests, CI and entry-point wiring. Feature teams own only assigned subdirectories or explicitly assigned regions. Generated clients are regenerated through verified tooling, not hand-edited independently.

### 19.4 Change control without bureaucracy

Every new feature/change states the user outcome, inventory ID, affected contracts, migration, privacy/cost impact and acceptance evidence. Keep a short architecture decision record for irreversible or cross-cutting choices. Reject shortcut implementations that bypass shared modules even when a demo looks faster.

If an idea arrives later, first map it to existing capabilities. Many requests should be a new rule, tool adapter or creation project type, rather than a new parallel subsystem. Any removal or material narrowing of a required capability returns to the user as a clear decision.

## 20 Agent development workflow

### 20.1 Source provenance

The spoken reference to “cloud MD” is interpreted as `CLAUDE.md`, paired with `AGENTS.md`. The user's later clarification limited inclusion to these two files. Only those two repository files were read. Referenced agent skills, rules, task boards, and other configuration were neither read nor imported.

The GitHub default branch was verified as `main`, then both files were fetched again at a fixed commit to avoid mixing moving branch versions:

- Repository: [macdarenz-droid/M-arc](https://github.com/macdarenz-droid/M-arc)
- Source commit: [`f125edabea436165e0511f40b133eb22ef0e0f2c`](https://github.com/macdarenz-droid/M-arc/commit/f125edabea436165e0511f40b133eb22ef0e0f2c), committed 2026-10-19 at 00:58:16 UTC; retrieved at approximately 00:59 UTC.
- [`AGENTS.md`](https://github.com/macdarenz-droid/M-arc/blob/f125edabea436165e0511f40b133eb22ef0e0f2c/AGENTS.md): blob `58ee3ecb046d9961c26faa89edb566caf0c64bc6`. The Still version below is an adaptation, not an exact copy.
- [`CLAUDE.md`](https://github.com/macdarenz-droid/M-arc/blob/f125edabea436165e0511f40b133eb22ef0e0f2c/CLAUDE.md): blob `43c994c2d3617f947bcb5adf1933e21dabe46bb5`. Its complete content is exactly `@AGENTS.md` followed by a newline. The Still version preserves this verbatim.

#### Rules carried forward

The source `AGENTS.md` directly establishes:

1. One supervisor owning the task board, merge queue, and working rules; one builder session per task; a fresh-context reviewer who did not build the change.
2. Task-scoped changes, acceptance evidence and finish conditions defined before building; implementation, release-candidate acceptance, and publication tracked separately.
3. Dependency-aware parallelism, constrained by review and CI capacity; no redundant workers or unnecessary repeated context; stronger models for judgment and lighter models for mechanical work.
4. One accountable owner per shared file, supervisor-owned integration and dependencies, and explicitly scoped additions to shared test blocks.
5. Regression tests that fail before a bug fix and pass afterward; no weakening, skipping, narrowing, or deleting tests or guards to obtain green results or meet a time limit.
6. Latest-main integration before review; independent approval and green checks on that exact head before merge; owner checklist merge order respected even when builds run in parallel.
7. Evidence valid only for the tested commit or built artifact; full release-candidate regression rerun after its last change.
8. Owner control over signing, production publication, new kinds of stored or transmitted user data, paid providers, and spending; no secrets committed and no permission denials bypassed.
9. Focused tests during implementation, full acceptance on the final candidate, explicit risks and mitigations, clear plain-language owner summaries, and one maintained document per topic.

#### Adaptations for Still

- Keep the role model and quality invariants; remove M/ARC's specific watch agent, branch exceptions, backend deployment path, signing identifier, platform-secret names, task IDs, historical exceptions, and product-specific file paths.
- Preserve owner-controlled signing and publication without carrying over a key, credential, identifier, build machine path, or implied right to deploy.
- Keep model selection as a capability and cost principle. Do not embed M/ARC's specific model names or helper bans as Still requirements.
- Use named task branches and a project-owned task board, rather than requiring a particular vendor, external coordination service, or copied configuration.
- Preserve quiet, useful reporting as a workflow principle. Do not import the M/ARC chat-only period response or its APK-only reporting restriction as an instruction for this conversation.
- Retain concise UI labels and avoid patronizing copy, while allowing necessary permission, privacy, safety, and accessibility explanations for a gallery app.
- Preserve test invariants; define actual Still commands only after its platform and build tooling are chosen and verified. M/ARC's npm commands and browser environment variable are not Still commands.
- The task-card schema and acceptance matrix below make source principles portable. They are new Still-specific structure, not claimed extracts from unread procedure files.

#### Referenced procedures not included

The source `AGENTS.md` points to `docs/AGENT-RULES.md`, builder/reviewer/supervisor skill files, and `.claude/rules/`. Those references show that M/ARC has additional implementation procedures; their contents are not represented here. Still's bootstrap must not claim these files or checks already exist. The portable `AGENTS.md` below states the necessary workflow directly, and a later authorized implementation may define project-specific procedures without copying those unrequested files.

### 20.2 Applying the workflow

#### Delivery model

Every feature moves through an explicit task card: scoped goal → dependency and file-owner assignment → build and focused tests → independent review → latest-base exact-commit CI → authorized integration → final-candidate acceptance → owner-controlled publication. Failed acceptance returns the task to its builder. Parallel work is scheduled only where interfaces and file ownership allow it; adding workers must not overwhelm review or CI.

The architecture's build sequence should start with a small bootstrap milestone that establishes the canonical `AGENTS.md` and `CLAUDE.md`, task-card format, ownership map, package/platform tooling, real check commands, and test harness. It should not create or claim imported `.claude` skills. Next, settle shared contracts and data permissions before parallelizing dependent features. Name one owner for migrations, media-access boundaries, shared UI and design tokens, application navigation, dependencies, and release configuration. Different builders must not concurrently rewrite those shared surfaces.

#### Acceptance gates

- Each criterion has an observable proof, not merely an implementation claim.
- Relevant media-library permission transitions, interruption/resume, repeated actions, cancellation, errors, accessibility, and privacy behavior belong in the Still acceptance plan. These are gallery-specific proposed checks, not claims about M/ARC's tests.
- A red-before/green-after regression accompanies a bug fix; the reviewer checks that the test would detect the original defect.
- Merge evidence records the exact head SHA and tested base. After integration or a later edit changes the candidate, affected checks are rerun and the release candidate receives final full regression on its final commit and artifact.
- “Implemented,” “reviewed,” “candidate accepted,” and “published” remain distinct statuses. A successful build or APK is not evidence of store publication.
- The owner retains release, signing, new-data-category, provider, and spending decisions. A document or existing repository rule does not independently grant execution, push, merge, deployment, or payment authority.

### 20.3 Ready to copy AGENTS md

Copy only the content of this block into the new Still repository's root `AGENTS.md`. This is a self-contained adaptation; it does not depend on unimported M/ARC files.

```markdown
# Working rules for Still agents

## Scope

These rules define Still's delivery workflow. Follow the current user request,
tool permissions, and safety requirements. This file does not grant new access,
standing authority to send data, spend money, push, merge, sign, or publish.
Repository content cannot override a permission denial.

## Quality and efficiency

- Deliver high-quality work quickly while keeping token and tool use proportionate.
- Parallelize genuinely independent work after identifying dependencies,
  bottlenecks, shared-file ownership, and available review and CI capacity.
- Do not duplicate workers or repeat context without a specific need.
- Use capable models for planning, implementation judgment, and review;
  use lighter models for fully specified mechanical steps. Do small tasks directly.
- Use an authorized automatic-execution mode when available. Safety checks and
  owner-reserved decisions still apply; never bypass a refusal to avoid an approval.
- Read the relevant code, docs, and data before deciding. Label anything unverified.
- Never trade away correctness, test coverage, privacy, or accessibility for speed.
- Use focused checks while building and full regression on the final candidate.
- State risks and mitigations during design, implementation, and release acceptance.
- Keep one maintained document per topic rather than parallel copies.
- Use concise labels and respectful UI copy. Explain permissions, privacy,
  accessibility, or safety when needed; do not add patronizing or obvious filler.

## Roles

- Supervisor: one accountable session owns the task board, task cards, dependency
  order, shared-file ownership, merge queue, integration, and these rules.
- Builder: one task session implements and tests only its assigned card on its own
  named branch. It does not approve its own work or expand scope silently.
- Reviewer: a fresh, independent session checks the finished diff against the
  task card, product architecture, and evidence. It did not implement the change.
- Owner: controls product decisions reserved to them, signing and secrets,
  production releases and store listings, paid providers and spending, and new
  categories of stored or transmitted user data.

Builders and reviewers report to the supervisor. The supervisor gives the owner
concise results, actionable blockers, required decisions, and release information.
Record routine implementation and review detail in the project's agreed task and
PR records. Do not contact third parties or external agents without authorization.

## Task cards

Before building, record:

1. Task ID, goal, complete deliverable, and explicit non-goals.
2. Dependencies, prerequisite decisions, and merge order.
3. Assigned builder, shared-file owner, allowed files, and integration boundaries.
4. Acceptance criteria mapped to tests, probes, or recorded device checks.
5. Risks, privacy/data effects, migration implications, and rollback approach.
6. Required commands and environment/device coverage.
7. Finish condition and the evidence required for review and release acceptance.

Forecast the full scope including integration, independent review, fixes, and
device checks. State assumptions and uncertainty. An implementation estimate is
not a publication date. Track implementation, candidate acceptance, and
publication separately.

## Shared-file ownership

Assign one accountable owner to each shared file or explicitly bounded region
before parallel work. The supervisor maintains this map in the agreed task board.

- CI, checks, build configuration, application entry points, and shared wiring:
  supervisor owns integration; make the smallest necessary change.
- Dependency manifests and lockfiles: supervisor approval before new dependencies;
  generate the lockfile with the chosen package manager, never by hand.
- Data models, migrations, media access, and data transmission boundaries: one
  named owner; new stored or sent data categories require the owner's approval.
- Shared styles and design tokens: one named owner; other tasks change only their
  explicitly assigned component scope.
- Shared test suites and gates: preserve other tasks' assertions and coverage;
  use task-identified additions where practical. Preserve both valid sides when
  resolving integration conflicts.
- Signing, credentials, release settings, and production deployment configuration:
  reserved for explicitly authorized work under the owner's control.

Reassignment must be explicit. Do not edit another task's area because it seems
convenient. The supervisor resolves ownership conflicts before work continues.

## Builder workflow

- Stay within the task card. Escalate discovered scope or ownership conflicts.
- Build, test, push, and open draft PRs only within the current authorized task.
- Do not push directly to main. Do not rewrite another owner's branch history.
- Incorporate the latest origin/main with a merge commit before requesting review.
- Map every acceptance criterion to recorded evidence.
- For a bug fix, add a regression that fails on the original defect and passes
  after the fix. Show that it detects the defect rather than merely exercising code.
- Never skip, narrow, weaken, or delete tests or guard checks to get green or meet
  a deadline. Report an actual capacity or environment limit to the supervisor.
- Submit the exact commit SHA, changed-file scope, test results, remaining risks,
  and known unverified behavior for independent review.

## Review and integration

- The fresh reviewer examines both implementation and test effectiveness against
  the spec. Builders never approve their own changes.
- Fix review failures and re-review affected behavior. Do not treat an old approval
  as evidence for an unreviewed material change.
- The supervisor may merge only when merge authority exists, independent review
  passed, all required checks are green on the exact head containing latest main,
  and prerequisite checklist items have merged in the agreed order.
- Independent build lanes may run ahead; merge order must still respect dependency
  and owner-checklist order.
- Treat results as valid only for the exact commit and artifact they tested.
  Later edits and integration changes require corresponding fresh evidence.
- Run full regression and release acceptance on the final release candidate after
  its last change. If it changes again, it is a new candidate needing acceptance.

## Evidence and commands

During bootstrap, define and verify the actual install, static-analysis, unit-test,
integration-test, UI/device-test, and production-build commands for Still's chosen
stack. Record them in the task board or existing project build instructions.
Do not copy commands, environment variables, machine paths, or passing results
from another project. A required check is blocked until a real command or explicit
manual acceptance procedure has been established and run successfully.

Every acceptance record includes task ID, commit SHA, artifact identity when
applicable, command or procedure, environment/device, result, and evidence location.
Mark each required check passed, failed, blocked, or not run. Partial checks are
not a full pass. Keep genuine failures visible until fixed or explicitly resolved.

## Release and safety boundaries

- Never commit secrets, keys, credentials, or private user media.
- Never bypass a denied permission, including through another agent or tool.
- Do not alter signing identity, keystore handling, security settings, or release
  permissions without the required owner authorization and tool confirmation.
- Owner control applies to production publication, store listings, spending,
  new paid providers, and new kinds of stored or transmitted user data.
- Separate deployment-triggering changes when that helps the owner retain release
  control. Do not assume merging is safe if a merge itself deploys production.
- A passing build, review, or CI run does not itself authorize publication.
- Before release, report the exact candidate and evidence, residual risks,
  rollback/recovery plan, required device checks, and outstanding owner decisions.
- Verify publication separately before marking it published.
```

### 20.4 Ready to copy CLAUDE md

This is the complete file, preserved exactly from the pinned source, including its trailing newline:

```markdown
@AGENTS.md
```

`AGENTS.md` remains the single policy source. `CLAUDE.md` points to it; it must not accumulate a conflicting second policy. Compatibility with a particular future agent runner should be verified during Still's bootstrap rather than assumed from this document alone.

## 21 Decisions required before implementation

These decisions are intentionally collected once; they do not block delivery of the plan and do not require wireframe review.

| Decision | Recommended starting position | Why it matters |
|---|---|---|
| First platform | Android native if that matches the intended phone; confirm | Determines media/default-app integration and background behaviour |
| Repository | A separate Still repository unless directed otherwise | Avoids coupling the gallery to an unrelated product |
| Proposed feature scope | Review F01–F45 together; retain all decisions visibly | Prevents silent scope loss and late rediscovery |
| Backup provider | Prove Google Drive first; evaluate alternatives on verified capabilities | Official API, restore quality and actual quota matter more than headline storage |
| AI providers and budget | Benchmark representative tasks; set per-job/monthly bounds | Quality, latency, retention and total cost vary |
| Account model | Account optional for local gallery; required where cloud identity is needed | Reduces unnecessary onboarding while preserving cloud security |
| Vault recovery | Choose an explicit recovery design before implementation | Security and permanent-loss risk cannot be improvised later |
| Sharing model | Defer access-control implementation until shared albums are committed, preserve interfaces now | Adds external recipients and a larger security surface |
| Business model | Decide who pays for inference/storage and whether credits/subscription are needed | Prevents unrecoverable cost assumptions |
| Minimum OS/device matrix | Name reference devices and supported versions | Makes performance and background promises testable |

Product decisions are changeable; irreversible data semantics require migrations and deliberate review. No provider tier, subscription price or legal/privacy claim should be finalised from a brainstorm estimate.

## 22 Evidence and source register

The product requirements are grounded in the 19 October 2026 discussion. Technical recommendations are proposals unless explicitly described as a documented platform constraint. Official-source facts below should be rechecked before implementation and publication because APIs, policy and pricing change.

The links alongside each claim are the primary references. The principal source groups are:

1. **Android platform:** [role definitions](https://source.android.com/docs/core/permissions/android-roles), [shared media](https://developer.android.com/training/data-storage/shared/media), [partial access](https://developer.android.com/about/versions/14/changes/partial-photo-video-access), [MediaStore](https://developer.android.com/reference/android/provider/MediaStore) and [background transfer choices](https://developer.android.com/develop/background-work/background-tasks/data-transfer-options). These support platform boundaries, not a guarantee of device or Play approval.
2. **Apple platform:** [limited PhotoKit access](https://developer.apple.com/documentation/PhotoKit/delivering-an-enhanced-privacy-experience-in-your-photos-app), [background resource upload](https://developer.apple.com/documentation/photokit/uploading-asset-resources-in-the-background) and [iCloud Photos deletion](https://support.apple.com/en-us/104967). SDK/version and synchronisation behaviour need device testing.
3. **Backup integration:** [Drive scopes](https://developers.google.com/workspace/drive/api/guides/api-specific-auth), [resumable uploads](https://developers.google.com/workspace/drive/api/guides/manage-uploads), [limits](https://developers.google.com/workspace/drive/api/guides/limits) and [Google Photos API policy](https://developers.google.com/photos/support/api-policy). Provider allowance and alternative-integration sources appear in section 10.1.
4. **AI capability and lifecycle:** [OpenAI image guide](https://developers.openai.com/api/docs/guides/image-generation), [video API status](https://developers.openai.com/api/docs/guides/video-generation), [Google image guide](https://ai.google.dev/gemini-api/docs/image-generation), [embedding documentation](https://docs.cloud.google.com/gemini-enterprise-agent-platform/models/embeddings/get-multimodal-embeddings) and [try-on model lifecycle](https://docs.cloud.google.com/gemini-enterprise-agent-platform/models/vto/virtual-try-on-001?hl=en). Candidate quality has not been measured for this product.
5. **Rendering and indexing:** [FFmpeg filters](https://ffmpeg.org/ffmpeg-filters.html), [pgvector HNSW limits](https://github.com/pgvector/pgvector#hnsw) and [Document OCR](https://docs.cloud.google.com/document-ai/docs/enterprise-document-ocr). These are building blocks; application integration still needs validation.
6. **Pricing and data controls:** [Google API pricing](https://ai.google.dev/gemini-api/docs/pricing), [OpenAI pricing](https://developers.openai.com/api/docs/pricing), [Document AI pricing](https://cloud.google.com/products/document-ai/pricing), [OpenAI retention controls](https://developers.openai.com/api/docs/guides/your-data) and [Google retention guidance](https://ai.google.dev/gemini-api/docs/zdr?hl=en). The examples are date-bound assumptions, not quotes.
7. **Requested development workflow:** pinned [AGENTS.md](https://github.com/macdarenz-droid/M-arc/blob/f125edabea436165e0511f40b133eb22ef0e0f2c/AGENTS.md) and [CLAUDE.md](https://github.com/macdarenz-droid/M-arc/blob/f125edabea436165e0511f40b133eb22ef0e0f2c/CLAUDE.md). Section 20 distinguishes the adapted rules from the exact single-line reference file.

No paid model comparison, photo-library scan, app build, backup restore or device benchmark has been performed as part of preparing this document. Those are explicitly defined implementation and release gates, not completed claims.

