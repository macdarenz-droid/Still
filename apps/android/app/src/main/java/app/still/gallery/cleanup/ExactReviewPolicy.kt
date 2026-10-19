package app.still.gallery.cleanup

import java.util.Collections
import java.util.concurrent.atomic.AtomicLong

/** Evidence about one complete readable stream, never an original or pinned AssetVersion. */
data class ReadableContentKey(
    val sha256: String,
    val byteCount: Long,
    val algorithm: String = ALGORITHM,
) {
    init {
        require(algorithm == ALGORITHM) { "Unsupported readable-content algorithm" }
        require(sha256.matches(Regex("[0-9a-f]{64}"))) { "Use a canonical SHA-256 digest" }
        require(byteCount > 0) { "Readable content must have a positive actual length" }
    }
    companion object { const val ALGORITHM = "sha256-readable-v1" }
}

enum class ReadableMediaKind { IMAGE, VIDEO }
enum class FavoriteState { FAVORITE, NOT_FAVORITE, UNKNOWN }
enum class ExactReviewReason { READY, NEEDS_KEEPER, FAVORITE_UNKNOWN, PROTECTION_UNKNOWN }

class ExactScanToken internal constructor(internal val owner: Any, internal val sequence: Long)

/** Instance/scan-bound identity. Native code maps this object to its fresh source; never persist it. */
class ExactCandidateToken internal constructor(
    internal val scan: ExactScanToken,
    val sourceReference: String,
    val kind: ReadableMediaKind,
)

data class ExactReadEvidence(val source: ExactCandidateToken, val content: ReadableContentKey)

class ExactMatchGroup internal constructor(
    val content: ReadableContentKey,
    val kind: ReadableMediaKind,
    members: List<ExactCandidateToken>,
) {
    val members: List<ExactCandidateToken> = Collections.unmodifiableList(members.toList())
}

class ExactReviewSnapshot internal constructor(internal val scan: ExactScanToken, groups: List<ExactMatchGroup>) {
    val groups: List<ExactMatchGroup> = Collections.unmodifiableList(groups.toList())
}

data class ExactGroupReview(
    val keepers: Set<ExactCandidateToken>,
    val extras: Set<ExactCandidateToken>,
    val reason: ExactReviewReason,
)

class StaleExactReviewException : IllegalStateException("This readable-content review is no longer current")

/**
 * Memory-only policy. Begin BEFORE provider IO; invalidate immediately on refresh,
 * access/source change, lock and disposal. Native IO owns complete-read/close,
 * provenance, current authority, cancellation, bounded admission and coverage.
 * No partial/failed read is evidence. UI publication/actions require this token
 * AND current native authority; a group or extra grants no mutation capability.
 */
class ExactReviewSession {
    private val owner = Any()
    private val sequence = AtomicLong()
    private val candidates = linkedMapOf<String, ExactCandidateToken>()
    private var activeScan: ExactScanToken? = null
    private var completed: ExactReviewSnapshot? = null

    @Synchronized fun beginScan(): ExactScanToken {
        val token = ExactScanToken(owner, advance())
        candidates.clear()
        completed = null
        activeScan = token
        return token
    }

    /** Atomic memory invalidation never waits for provider IO or grouping. */
    fun invalidate() { advance() }

    fun requireCurrent(scan: ExactScanToken) {
        if (scan.owner !== owner || scan.sequence != sequence.get()) throw StaleExactReviewException()
    }

    @Synchronized fun candidate(scan: ExactScanToken, sourceReference: String, kind: ReadableMediaKind): ExactCandidateToken {
        requireCurrent(scan)
        check(activeScan === scan && completed == null) { "This scan is not accepting sources" }
        require(sourceReference.isNotBlank() && sourceReference.length <= 4096) { "Invalid source reference" }
        require(!candidates.containsKey(sourceReference)) { "A source alias was registered twice" }
        val token = ExactCandidateToken(scan, sourceReference, kind)
        candidates[sourceReference] = token
        requireCurrent(scan)
        return token
    }

    @Synchronized fun finish(scan: ExactScanToken, evidence: List<ExactReadEvidence>): ExactReviewSnapshot {
        requireCurrent(scan)
        check(activeScan === scan && completed == null) { "This scan was already finished" }
        val seen = mutableSetOf<ExactCandidateToken>()
        for (item in evidence) {
            require(item.source.scan === scan && candidates[item.source.sourceReference] === item.source) { "Evidence belongs to another source or scan" }
            require(seen.add(item.source)) { "A readable source was counted twice" }
        }
        val groups = evidence.groupBy { it.content to it.source.kind }.filterValues { it.size >= 2 }.map { (key, members) ->
            ExactMatchGroup(key.first, key.second, members.map { it.source })
        }
        requireCurrent(scan)
        return ExactReviewSnapshot(scan, groups).also { completed = it }
    }

    @Synchronized fun review(
        snapshot: ExactReviewSnapshot,
        group: ExactMatchGroup,
        manualKeepers: Set<ExactCandidateToken>,
        favorites: Map<ExactCandidateToken, FavoriteState>,
        protections: ProtectionSnapshot,
    ): ExactGroupReview {
        requireCurrent(snapshot.scan)
        require(completed === snapshot && snapshot.groups.any { it === group }) { "The group does not belong to this current review" }
        val members = group.members.toSet()
        require(manualKeepers.all { it in members }) { "A keeper belongs to another matching group" }
        val keepers = manualKeepers.toMutableSet()
        keepers += members.filter { favorites[it] == FavoriteState.FAVORITE }
        if (protections.status == ProtectionStatus.READY && group.content in protections.keys) keepers += members
        val reason = when {
            protections.status != ProtectionStatus.READY -> ExactReviewReason.PROTECTION_UNKNOWN
            members.any { favorites[it] == null || favorites[it] == FavoriteState.UNKNOWN } -> ExactReviewReason.FAVORITE_UNKNOWN
            else -> {
                if (keepers.isEmpty()) ExactReviewReason.NEEDS_KEEPER else ExactReviewReason.READY
            }
        }
        requireCurrent(snapshot.scan)
        return ExactGroupReview(
            Collections.unmodifiableSet(keepers.toSet()),
            Collections.unmodifiableSet(if (reason == ExactReviewReason.READY) members - keepers else emptySet()),
            reason,
        )
    }

    private fun advance(): Long = sequence.updateAndGet { current ->
        check(current < Long.MAX_VALUE) { "Readable-content scan generations are exhausted" }
        current + 1
    }
}
