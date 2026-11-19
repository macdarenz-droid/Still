package app.still.gallery.media

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Collections

/** A location in one current view, never permission to read or act on a source. */
data class DriftTarget(val sourceUiToken: String, val index: Int)

/** A real observed local date; null is the explicit Unknown date target. */
data class DriftDay(val date: LocalDate?, val count: Int, val first: DriftTarget)

data class DriftDayWindow(
    val days: List<DriftDay>,
    val start: Int,
    val totalDays: Int,
    val previousStart: Int?,
    val nextStart: Int?,
)

/**
 * Opaque memory-only snapshot identity. isUsable describes construction, not current
 * access: callers must use the session and the existing gallery action gates.
 * Retaining this object does not retain the source index after invalidation.
 */
class DriftSnapshot internal constructor(
    val itemCount: Int,
    val sort: MediaSort,
    val zone: ZoneId,
    val isUsable: Boolean,
)

/**
 * Navigation over an already eligible, ordered view. No query, URI-only matching,
 * saved state, or authority is introduced here. Build once when that view, sort, or
 * timezone changes; invalidate before refresh, permission loss, or disposal.
 *
 * Every lookup checks the exact current snapshot and the required live predicate.
 * Native actions must additionally recheck galleryActionItems/current authority.
 */
class DriftNavigationSession(private val stillCurrent: () -> Boolean) {
    private data class Projection(
        val snapshot: DriftSnapshot,
        val tokens: List<String>,
        val positions: Map<String, Int>,
        val days: List<DriftDay>,
        val dayPositions: Map<LocalDate?, Int>,
        val tokenDayPositions: Map<String, Int>,
    )

    private data class DayCount(val first: DriftTarget, var count: Int = 1)

    private var projection: Projection? = null

    /** Replacing even an identical view fences every previous snapshot. */
    @Synchronized
    fun replace(orderedEligible: List<MediaItem>, sort: MediaSort, zone: ZoneId): DriftSnapshot {
        projection = null
        val tokens = ArrayList<String>(orderedEligible.size)
        val positions = HashMap<String, Int>(orderedEligible.size)
        val counts = HashMap<LocalDate?, DayCount>()
        val tokenDates = HashMap<String, LocalDate?>(orderedEligible.size)
        for ((index, item) in orderedEligible.withIndex()) {
            val token = sourceUiToken(item)
            if (positions.put(token, index) != null) {
                return DriftSnapshot(orderedEligible.size, sort, zone, false)
            }
            tokens.add(token)
            val date = if (item.capturedAt <= 0) null else Instant.ofEpochMilli(item.capturedAt).atZone(zone).toLocalDate()
            tokenDates[token] = date
            val count = counts[date]
            if (count == null) counts[date] = DayCount(DriftTarget(token, index)) else count.count++
        }
        // NAME remains a global filename order. Only its date-navigation rail is
        // newest first; no claim is made that a day's rows are contiguous.
        val knownDates = counts.keys.filterNotNull().sorted()
        val dates = ArrayList<LocalDate?>(counts.size)
        dates.addAll(if (sort == MediaSort.OLDEST) knownDates else knownDates.asReversed())
        if (counts.containsKey(null)) dates.add(null)
        val days = dates.map { date -> counts.getValue(date).let { DriftDay(date, it.count, it.first) } }
        val dayPositions = dates.withIndex().associate { (index, date) -> date to index }
        val tokenDayPositions = tokenDates.mapValues { (_, date) -> dayPositions.getValue(date) }
        val snapshot = DriftSnapshot(orderedEligible.size, sort, zone, true)
        projection = Projection(snapshot, tokens, positions, days, dayPositions, tokenDayPositions)
        return snapshot
    }

    @Synchronized
    fun invalidate() { projection = null }

    @Synchronized
    fun isCurrent(snapshot: DriftSnapshot): Boolean = live(snapshot) != null

    @Synchronized
    fun first(snapshot: DriftSnapshot): DriftTarget? = live(snapshot)?.let { current ->
        current.tokens.firstOrNull()?.let { DriftTarget(it, 0) }
    }

    @Synchronized
    fun target(snapshot: DriftSnapshot, sourceUiToken: String): DriftTarget? = live(snapshot)?.let { current ->
        current.positions[sourceUiToken]?.let { DriftTarget(sourceUiToken, it) }
    }

    @Synchronized
    fun positionOf(snapshot: DriftSnapshot, sourceUiToken: String): Int? = live(snapshot)?.positions?.get(sourceUiToken)

    /** Only one explicit step, bounded at either end; invalid tokens never fall back. */
    @Synchronized
    fun move(snapshot: DriftSnapshot, sourceUiToken: String, direction: Int): DriftTarget? {
        if (direction != -1 && direction != 1) return null
        val current = live(snapshot) ?: return null
        val index = current.positions[sourceUiToken] ?: return null
        val next = index + direction
        return current.tokens.getOrNull(next)?.let { DriftTarget(it, next) }
    }

    /** Exact date only; null selects Unknown. Absent dates do not choose a neighbor. */
    @Synchronized
    fun jump(snapshot: DriftSnapshot, date: LocalDate?): DriftTarget? = live(snapshot)?.let { current ->
        current.dayPositions[date]?.let { current.days[it].first }
    }

    @Synchronized
    fun dayOf(snapshot: DriftSnapshot, sourceUiToken: String): DriftDay? = live(snapshot)?.let { current ->
        current.tokenDayPositions[sourceUiToken]?.let { current.days[it] }
    }

    /**
     * At most seven real targets, centered on the anchor unless a paging start is
     * supplied. previousStart/nextStart are bounded offsets for the same snapshot.
     * Empty current views have an empty window; invalid anchors/offsets return null.
     */
    @Synchronized
    fun rail(snapshot: DriftSnapshot, anchorSourceUiToken: String? = null, start: Int? = null): DriftDayWindow? {
        val current = live(snapshot) ?: return null
        val anchor = if (anchorSourceUiToken == null) 0 else current.tokenDayPositions[anchorSourceUiToken] ?: return null
        val total = current.days.size
        if (total == 0) return if (start == null || start == 0) DriftDayWindow(emptyList(), 0, 0, null, null) else null
        val offset = start ?: (anchor - 3).coerceIn(0, (total - 7).coerceAtLeast(0))
        if (offset < 0 || offset >= total) return null
        val end = (offset.toLong() + 7).coerceAtMost(total.toLong()).toInt()
        return DriftDayWindow(
            Collections.unmodifiableList(ArrayList(current.days.subList(offset, end))),
            offset,
            total,
            if (offset > 0) (offset - 7).coerceAtLeast(0) else null,
            if (end < total) end else null,
        )
    }

    private fun live(snapshot: DriftSnapshot): Projection? {
        val current = projection ?: return null
        if (current.snapshot !== snapshot || !snapshot.isUsable) return null
        if (!stillCurrent()) {
            projection = null
            return null
        }
        return current
    }
}
