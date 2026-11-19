package app.still.gallery.ui

import app.still.gallery.media.MediaItem
import app.still.gallery.media.sourceUiToken
import java.util.concurrent.atomic.AtomicBoolean

/** Single pass over metadata; only requested URI candidates need source-token construction. */
fun similarityCurrentPhotos(captured: List<MediaItem>, current: List<MediaItem>): List<MediaItem> {
    if (captured.isEmpty() || captured.size > 200 || captured.any { it.isVideo }) return emptyList()
    val wanted = captured.associateBy { it.id }
    if (wanted.size != captured.size) return emptyList()
    val matches = HashMap<String, MediaItem>()
    for (row in current) if (row.id in wanted) {
        if (matches.put(row.id, row) != null) return emptyList()
    }
    return captured.map { expected ->
        matches[expected.id]?.takeIf { sourceUiToken(it) == sourceUiToken(expected) } ?: return emptyList()
    }
}

/** The original selected scope is an intent, not whatever happens to be selected later. */
fun similaritySelectedPhotos(captured: List<MediaItem>, selected: Set<String>, view: List<MediaItem>): List<MediaItem> {
    if (captured.size !in 2..200) return emptyList()
    val tokens = captured.map(::sourceUiToken).toSet()
    if (tokens.size != captured.size) return emptyList()
    val rows = similarityCurrentPhotos(captured, view)
    if (rows.size != captured.size) return emptyList()
    if (!selected.containsAll(tokens)) return emptyList()
    // Hidden selection is not part of this view. The usual exact selection needs no extra scan.
    val extra = selected - tokens
    if (extra.isNotEmpty() && view.any { sourceUiToken(it) in extra }) return emptyList()
    return rows
}

/** Exact immutable gallery/selection instances fence repeated reader checks without rescanning. */
class SimilaritySelectionProjection(captured: List<MediaItem>, private val selected: List<String>,
    view: List<MediaItem>, private val authorized: List<MediaItem>) {
    val rows: List<MediaItem> = similaritySelectedPhotos(captured, selected.toSet(), view).let { visible ->
        if (visible.size == captured.size) similarityCurrentPhotos(visible, authorized) else emptyList()
    }
    fun isCurrent(currentSelected: List<String>, currentAuthorized: List<MediaItem>): Boolean =
        rows.size in 2..200 && currentSelected === selected && currentAuthorized === authorized
}

/** Per explicit Compare choice, separate from analysis result and CP10 preparation identities. */
class SimilarityCompareChoice internal constructor(
    internal val request: SimilarityRequest,
    internal val result: Any,
    internal val pair: Any,
    internal val tokens: List<String>,
    internal val originCurrent: () -> Boolean,
    internal val resolve: () -> List<MediaItem>?,
) { internal val active = AtomicBoolean(true) }

class SimilarityCompareBinding(private val allowed: () -> Boolean) {
    @Volatile private var choice: SimilarityCompareChoice? = null

    fun choose(request: SimilarityRequest, result: Any, pair: Any, canBegin: () -> Boolean,
        originCurrent: () -> Boolean, resolve: () -> List<MediaItem>?): SimilarityCompareChoice? {
        if (!allowed() || !canBegin() || !originCurrent()) return null
        val rows = resolve() ?: return null
        if (!validPair(rows) || !allowed() || !canBegin() || !originCurrent()) return null
        choice?.active?.set(false)
        return SimilarityCompareChoice(request, result, pair, rows.map(::sourceUiToken), originCurrent, resolve).also { choice = it }
    }

    fun isCurrent(expected: SimilarityCompareChoice?): Boolean {
        if (expected == null || choice !== expected || !expected.active.get()) return false
        val rows = if (allowed() && expected.originCurrent()) expected.resolve() else null
        if (rows == null || !validPair(rows) || rows.map(::sourceUiToken) != expected.tokens) {
            expected.active.set(false)
            return false
        }
        return choice === expected && expected.active.get()
    }

    fun resolve(expected: SimilarityCompareChoice): List<MediaItem>? =
        if (isCurrent(expected)) expected.resolve()?.takeIf { validPair(it) && it.map(::sourceUiToken) == expected.tokens } else null

    /** The current pair is prepared anew; analysis rows never become CP10 byte handles. */
    fun <P : AutoCloseable> startComparison(request: SimilarityRequest, result: Any, pair: Any,
        comparison: CompareCoordinator<P>, expectedAction: Any, originCurrent: () -> Boolean,
        resolve: () -> List<MediaItem>?, createWork: (List<MediaItem>, () -> Boolean) -> CompareWork<P>): Boolean {
        fun canBegin() = comparison.actionIdentity === expectedAction && comparison.canStart
        val next = choose(request, result, pair, ::canBegin, originCurrent, resolve) ?: return false
        val photos = resolve(next) ?: return false
        comparison.start(expectedAction, photos.map { it.name }, { isCurrent(next) }) { createWork(photos) { isCurrent(next) } }
        return true
    }

    /** Returns true only when the removed Compare belongs to this exact analysis opening. */
    fun invalidate(request: SimilarityRequest? = null): Boolean {
        val old = choice ?: return false
        if (request != null && old.request !== request) return false
        old.active.set(false)
        choice = null
        return true
    }

    private fun validPair(rows: List<MediaItem>): Boolean = rows.size == 2 && rows.none { it.isVideo } &&
        rows.map { it.id }.toSet().size == 2 && rows.map(::sourceUiToken).toSet().size == 2
}
