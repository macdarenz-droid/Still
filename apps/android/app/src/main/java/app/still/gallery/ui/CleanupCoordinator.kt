package app.still.gallery.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.still.gallery.cleanup.AndroidContentProtections
import app.still.gallery.cleanup.ExactCandidateToken
import app.still.gallery.cleanup.ExactReadEvidence
import app.still.gallery.cleanup.ExactReviewSession
import app.still.gallery.cleanup.ExactReviewSnapshot
import app.still.gallery.cleanup.ExactScanToken
import app.still.gallery.cleanup.FavoriteState
import app.still.gallery.cleanup.ProtectionSnapshot
import app.still.gallery.cleanup.ProtectionStatus
import app.still.gallery.cleanup.ProtectionWriteStatus
import app.still.gallery.cleanup.ReadableContentKey
import app.still.gallery.cleanup.ReadableMediaKind
import app.still.gallery.media.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** These live callbacks stay memory-only and are never restored after recreation. */
data class CleanupStartBindings(
    val access: MediaAccess,
    val favorites: Set<String>,
    val isCurrent: () -> Boolean,
    val acceptSnapshot: (CleanupSourceSnapshot) -> Boolean,
    val currentFavorites: () -> Set<String>,
    val currentAliases: () -> Map<String, String>,
    val openMember: (MediaItem) -> Unit,
)

/** Main-thread UI owner. Blocking bytes and annotation IO run on worker dispatchers. */
class CleanupCoordinator(
    private val scope: CoroutineScope,
    private val lifetime: GalleryActionLifetime,
    private val reader: (MediaAccess) -> CleanupSourceReader,
    private val protections: () -> AndroidContentProtections,
) {
    private val session = ExactReviewSession()
    private val annotation = CleanupRequestFence { lifetime.isActive }
    private var worker: Job? = null
    private var annotationWorker: Job? = null
    private var bindings: CleanupStartBindings? = null
    private var scan: ExactScanToken? = null
    private var members = emptyMap<ExactCandidateToken, MediaItem>()
    private var manualKeepers by mutableStateOf(emptySet<ExactCandidateToken>())
    private var coverage by mutableStateOf(CleanupCoverageUi())
    private var phase by mutableStateOf(CleanupPhase.IDLE)
    private var protectionSnapshot by mutableStateOf(ProtectionSnapshot.unknown())
    private var protectionState by mutableStateOf(CleanupProtectionState.UNKNOWN)
    private var protectionWriteFailed by mutableStateOf(false)
    private var sourceLimitExceeded by mutableStateOf(false)
    var actionIdentity: Any by mutableStateOf(Any())
        private set
    var currentReview: ExactReviewSnapshot? by mutableStateOf(null)
        private set
    val currentScan: ExactScanToken? get() = scan

    fun invalidate() {
        session.invalidate()
        scan = null
        currentReview = null
        members = emptyMap()
        manualKeepers = emptySet()
        bindings = null
        worker?.cancel()
        annotation.invalidate()
        annotationWorker?.cancel()
        protectionSnapshot = ProtectionSnapshot.unknown()
        protectionState = CleanupProtectionState.UNKNOWN
        if (phase != CleanupPhase.IDLE) phase = CleanupPhase.INVALIDATED
        actionIdentity = Any()
    }

    fun start(expectedAction: Any, next: CleanupStartBindings) {
        if (expectedAction !== actionIdentity || !lifetime.isActive || next.access.level == AccessLevel.NONE || !next.isCurrent() || protectionState == CleanupProtectionState.SAVING) return
        if (worker?.isCompleted == false) { phase = CleanupPhase.BUSY; return }
        val token = session.beginScan()
        scan = token
        bindings = next
        currentReview = null
        members = emptyMap()
        manualKeepers = emptySet()
        coverage = CleanupCoverageUi()
        sourceLimitExceeded = false
        phase = CleanupPhase.SCANNING
        actionIdentity = Any()
        worker = scope.launch {
            try {
                val registered = linkedMapOf<String, ExactCandidateToken>()
                val observed = linkedMapOf<ExactCandidateToken, MediaItem>()
                val sourceReader = reader(next.access)
                val result = CleanupScanner(sourceReader).scan(
                    next.favorites,
                    checkCurrent = { requireRun(token) },
                    onSnapshot = { fresh -> withContext(Dispatchers.Main.immediate) {
                        requireRun(token)
                        if (!next.acceptSnapshot(fresh)) throw CancellationException("The gallery mapping changed")
                    } },
                    onCandidate = { item ->
                        requireRun(token)
                        val ref = cleanupSourceReference(item)
                        val candidate = session.candidate(token, ref, if (item.isVideo) ReadableMediaKind.VIDEO else ReadableMediaKind.IMAGE)
                        registered[ref] = candidate
                        observed[candidate] = item
                    },
                    onProgress = { update -> withContext(Dispatchers.Main.immediate) {
                        requireRun(token)
                        coverage = update.toUi()
                    } },
                )
                requireRun(token)
                val evidence = result.members.map { member ->
                    ExactReadEvidence(registered.getValue(cleanupSourceReference(member.item)), ReadableContentKey(member.digest.sha256, member.digest.byteCount))
                }
                val completed = withContext(Dispatchers.Default) { session.finish(token, evidence) }
                withContext(Dispatchers.IO) { requireRun(token); sourceReader.validateScope(result.snapshot) }
                requireRun(token)
                members = observed.toMap()
                coverage = result.coverage.toUi()
                currentReview = completed
                phase = CleanupPhase.COMPLETE
                actionIdentity = Any()
            } catch (cancelled: CancellationException) {
                if (scan === token && lifetime.isActive) {
                    currentReview = null
                    members = emptyMap()
                    phase = if (phase == CleanupPhase.STOPPING) CleanupPhase.CANCELLED else CleanupPhase.INVALIDATED
                    session.invalidate()
                    scan = null
                    actionIdentity = Any()
                }
                throw cancelled
            } catch (failure: Exception) {
                if (scan === token && lifetime.isActive) {
                    currentCoroutineContext().ensureActive()
                    phase = when (failure) {
                        is CleanupReaderBusyException -> CleanupPhase.BUSY
                        is CleanupSourceChangedException -> CleanupPhase.INVALIDATED
                        else -> CleanupPhase.FAILED
                    }
                    sourceLimitExceeded = failure is CleanupScopeLimitException
                    currentReview = null
                    members = emptyMap()
                    session.invalidate()
                    scan = null
                    actionIdentity = Any()
                }
            }
        }
    }

    fun stop(expected: ExactScanToken?) {
        if (!lifetime.isActive || expected == null || scan !== expected || phase != CleanupPhase.SCANNING) return
        phase = CleanupPhase.STOPPING
        session.invalidate() // Fence output immediately, even if provider IO is blocked.
        currentReview = null
        members = emptyMap()
        worker?.cancel()
    }

    fun loadProtections() {
        if (!lifetime.isActive || annotationWorker?.isCompleted == false) return
        val request = annotation.begin()
        protectionState = CleanupProtectionState.LOADING
        annotationWorker = scope.launch {
            try {
                val snapshot = withContext(Dispatchers.IO) { annotation.requireCurrent(request); protections().load() }
                annotation.publish(request) {
                    protectionSnapshot = snapshot
                    protectionState = if (snapshot.status == ProtectionStatus.READY) CleanupProtectionState.READY else CleanupProtectionState.UNKNOWN
                    if (snapshot.status == ProtectionStatus.READY) protectionWriteFailed = false
                }
            } catch (cancelled: CancellationException) {
                annotation.publish(request) { protectionSnapshot = ProtectionSnapshot.unknown(); protectionState = CleanupProtectionState.UNKNOWN }
                throw cancelled
            }
            catch (_: Exception) { annotation.publish(request) { protectionSnapshot = ProtectionSnapshot.unknown(); protectionState = CleanupProtectionState.UNKNOWN } }
        }
    }

    fun keep(expected: ExactReviewSnapshot?, groupId: Int, memberId: Int, keep: Boolean) {
        if (!reviewIsCurrent(expected)) return
        val member = expected?.groups?.getOrNull(groupId)?.members?.getOrNull(memberId) ?: return
        manualKeepers = if (keep) manualKeepers + member else manualKeepers - member
    }

    fun open(expected: ExactReviewSnapshot?, groupId: Int, memberId: Int) {
        if (!reviewIsCurrent(expected)) return
        val token = expected?.groups?.getOrNull(groupId)?.members?.getOrNull(memberId) ?: return
        members[token]?.let { bindings?.openMember?.invoke(it) }
    }

    fun protect(expected: ExactReviewSnapshot?, groupId: Int, protect: Boolean, expectedRevision: Long) {
        if (!reviewIsCurrent(expected) || protectionState != CleanupProtectionState.READY || protectionSnapshot.revision != expectedRevision || annotationWorker?.isCompleted == false) return
        val group = expected?.groups?.getOrNull(groupId) ?: return
        val request = annotation.begin()
        protectionState = CleanupProtectionState.SAVING
        protectionWriteFailed = false
        annotationWorker = scope.launch {
            try {
                val job = currentCoroutineContext()
                val result = withContext(Dispatchers.IO) {
                    protections().setProtected(group.content, protect, expectedRevision) {
                        job.ensureActive()
                        annotation.requireCurrent(request)
                        if (!reviewIsCurrent(expected)) throw CancellationException("Review access ended")
                    }
                }
                annotation.publish(request) {
                    if (reviewIsCurrent(expected) && result.status == ProtectionWriteStatus.SAVED) {
                        protectionSnapshot = result.snapshot
                        protectionState = CleanupProtectionState.READY
                    } else {
                        protectionSnapshot = ProtectionSnapshot.unknown()
                        protectionState = CleanupProtectionState.UNKNOWN
                        protectionWriteFailed = true
                    }
                }
            } catch (cancelled: CancellationException) {
                annotation.publish(request) { protectionSnapshot = ProtectionSnapshot.unknown(); protectionState = CleanupProtectionState.UNKNOWN; protectionWriteFailed = true }
                throw cancelled
            }
            catch (_: Exception) { annotation.publish(request) { protectionSnapshot = ProtectionSnapshot.unknown(); protectionState = CleanupProtectionState.UNKNOWN; protectionWriteFailed = true } }
        }
    }

    val protectionRevision: Long get() = protectionSnapshot.revision

    fun display(access: MediaAccess, canStart: Boolean): CleanupUiState {
        val review = currentReview
        val current = reviewIsCurrent(review)
        val favoriteKeys = bindings?.currentFavorites?.invoke().orEmpty()
        val aliases = bindings?.currentAliases?.invoke().orEmpty()
        val favoriteUris = favoriteKeys + aliases.filterKeys { it in favoriteKeys }.values
        val unknown = favoriteKeys.mapNotNull { key -> legacyFavoriteReference(key)?.takeIf { it.key !in aliases } }
            .mapTo(mutableSetOf()) { it.isVideo to it.externalHandle }
        val favorites = members.mapValues { (_, item) -> when {
            item.id in favoriteUris -> FavoriteState.FAVORITE
            (item.isVideo to item.externalHandle) in unknown -> FavoriteState.UNKNOWN
            else -> FavoriteState.NOT_FAVORITE
        } }
        val knownProtections = if (protectionState == CleanupProtectionState.READY) protectionSnapshot else ProtectionSnapshot.unknown()
        val groups = if (current && review != null) review.groups.mapIndexed { groupId, group ->
            val selected = group.members.filterTo(mutableSetOf()) { it in manualKeepers }
            val result = session.review(review, group, selected, favorites, knownProtections)
            CleanupGroupUi(groupId, group.members.mapIndexed { memberId, candidate ->
                val item = members.getValue(candidate)
                CleanupMemberUi(memberId, item.name, item.isVideo, group.content.byteCount, candidate in manualKeepers,
                    CleanupFavoriteState.valueOf(favorites.getValue(candidate).name), group.content in knownProtections.keys)
            }, group.content in knownProtections.keys, CleanupReviewReason.valueOf(result.reason.name), result.extras.size)
        } else emptyList()
        return CleanupUiState(
            phase = if (phase == CleanupPhase.COMPLETE && !current) CleanupPhase.INVALIDATED else phase,
            coverage = coverage, limitedAccess = access.level == AccessLevel.PARTIAL, hasAccess = access.level != AccessLevel.NONE,
            canStart = canStart && lifetime.isActive, groups = groups, protectionState = protectionState,
            protectionWriteFailed = protectionWriteFailed, sourceLimitExceeded = sourceLimitExceeded,
        )
    }

    private fun requireRun(expected: ExactScanToken) {
        if (!lifetime.isActive || scan !== expected || bindings?.isCurrent?.invoke() != true) throw CancellationException("Scan access ended")
        try { session.requireCurrent(expected) } catch (_: IllegalStateException) { throw CancellationException("Scan was invalidated") }
    }

    private fun reviewIsCurrent(expected: ExactReviewSnapshot?): Boolean {
        if (expected == null || currentReview !== expected || phase != CleanupPhase.COMPLETE || !lifetime.isActive || bindings?.isCurrent?.invoke() != true) return false
        val token = scan ?: return false
        return try { session.requireCurrent(token); true } catch (_: IllegalStateException) { false }
    }
}

private fun CleanupCoverage.toUi() = CleanupCoverageUi(true, considered, candidates, checked, compared, empty, unavailable, changed, omitted, aliases)
