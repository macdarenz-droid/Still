package app.still.gallery.media

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DriftPolicyTest {
    private val utc = ZoneId.of("UTC")

    @Test fun selectionFollowsTheExactSourceTokenInsteadOfItsOldPosition() {
        val session = DriftNavigationSession { true }
        val a = item("a", "2026-06-08T12:00:00Z")
        val b = item("b", "2026-06-09T12:00:00Z")
        val old = session.replace(listOf(a, b), MediaSort.NAME, utc)
        val token = sourceUiToken(a)
        assertEquals(0, session.positionOf(old, token))
        val current = session.replace(listOf(b, a), MediaSort.NEWEST, utc)
        assertNull(session.target(old, token))
        assertEquals(DriftTarget(token, 1), session.target(current, token))
        assertEquals(sourceUiToken(b), session.first(current)?.sourceUiToken)
        assertNull(session.target(current, "missing"))
    }

    @Test fun reusedUriNeverRetargetsTheOldSelectionOrCallback() {
        val session = DriftNavigationSession { true }
        val before = item("same-uri", "2026-06-08T12:00:00Z")
        val replaced = before.copy(generationAdded = 40, generationModified = 50, providerGeneration = 60)
        assertEquals(before.id, replaced.id) // A URI-only selection would select the replacement.
        val old = session.replace(listOf(before), MediaSort.NEWEST, utc)
        val oldToken = sourceUiToken(before)
        val current = session.replace(listOf(replaced), MediaSort.NEWEST, utc)
        assertNotEquals(oldToken, sourceUiToken(replaced))
        assertNull(session.target(old, oldToken))
        assertNull(session.target(current, oldToken))
        assertNull(session.move(current, oldToken, 1))
        assertEquals(0, session.positionOf(current, sourceUiToken(replaced)))
    }

    @Test fun identicalRefreshAndForeignSessionStillFenceOldSnapshots() {
        val first = DriftNavigationSession { true }
        val second = DriftNavigationSession { true }
        val row = item("same", "2026-06-08T12:00:00Z")
        val old = first.replace(listOf(row), MediaSort.NEWEST, utc)
        val current = first.replace(listOf(row), MediaSort.NEWEST, utc)
        val foreign = second.replace(listOf(row), MediaSort.NEWEST, utc)
        for (snapshot in listOf(old, foreign)) {
            assertDenied(first, snapshot, sourceUiToken(row))
            assertNull(first.rail(snapshot, sourceUiToken(row), 0))
            assertNull(first.jump(snapshot, LocalDate.of(2026, 6, 8)))
        }
        assertTrue(first.isCurrent(current))
        assertTrue(second.isCurrent(foreign))
        assertDenied(second, current, sourceUiToken(row))
        first.invalidate()
        assertDenied(first, current, sourceUiToken(row))
    }

    @Test fun liveScopeFailureDeniesEveryLookupEvenWithoutExplicitInvalidation() {
        var authorized = true
        val session = DriftNavigationSession { authorized }
        val row = item("a", "2026-06-08T12:00:00Z")
        val snapshot = session.replace(listOf(row), MediaSort.NEWEST, utc)
        assertTrue(session.isCurrent(snapshot))
        authorized = false
        assertDenied(session, snapshot, sourceUiToken(row))
        authorized = true
        assertDenied(session, snapshot, sourceUiToken(row)) // Observed scope failure cannot revive.
    }

    @Test fun callerListMutationCannotChangeAnAlreadyBuiltProjection() {
        val session = DriftNavigationSession { true }
        val old = item("old", "2026-06-08T12:00:00Z")
        val replacement = item("replacement", "2026-06-09T12:00:00Z")
        val input = mutableListOf(old)
        val snapshot = session.replace(input, MediaSort.NEWEST, utc)
        input[0] = replacement
        input.clear()
        assertEquals(DriftTarget(sourceUiToken(old), 0), session.first(snapshot))
        assertEquals(1, session.dayOf(snapshot, sourceUiToken(old))?.count)
        assertNull(session.target(snapshot, sourceUiToken(replacement)))
        val fresh = session.replace(input, MediaSort.NEWEST, utc)
        assertNull(session.first(fresh))
        assertDenied(session, snapshot, sourceUiToken(old))
    }

    @Test fun duplicateExactTokensMakeTheWholeProjectionUnusableAndFencePriorView() {
        val session = DriftNavigationSession { true }
        val row = item("a", "2026-06-08T12:00:00Z")
        val valid = session.replace(listOf(row), MediaSort.NEWEST, utc)
        val duplicate = session.replace(listOf(row, row.copy()), MediaSort.NEWEST, utc)
        assertFalse(duplicate.isUsable)
        assertEquals(2, duplicate.itemCount)
        assertDenied(session, duplicate, sourceUiToken(row))
        assertDenied(session, valid, sourceUiToken(row))
        val recovered = session.replace(listOf(row), MediaSort.NEWEST, utc)
        assertTrue(recovered.isUsable)
        assertTrue(session.isCurrent(recovered))
        assertDenied(session, duplicate, sourceUiToken(row))
    }

    @Test fun explicitPreviousAndNextAreBoundedAndRejectArbitrarySteps() {
        val session = DriftNavigationSession { true }
        val rows = listOf(item("a"), item("b"), item("c"))
        val snapshot = session.replace(rows, MediaSort.NAME, utc)
        val tokens = rows.map(::sourceUiToken)
        assertNull(session.move(snapshot, tokens.first(), -1))
        assertEquals(DriftTarget(tokens[1], 1), session.move(snapshot, tokens.first(), 1))
        assertEquals(DriftTarget(tokens[1], 1), session.move(snapshot, tokens.last(), -1))
        assertNull(session.move(snapshot, tokens.last(), 1))
        for (step in listOf(Int.MIN_VALUE, -2, 0, 2, Int.MAX_VALUE)) assertNull(session.move(snapshot, tokens[1], step))
    }

    @Test fun localMidnightUsesTheExplicitTimezoneAndChangingZoneFencesTheOldDates() {
        val session = DriftNavigationSession { true }
        val row = item("a", "2026-06-09T00:30:00Z")
        val token = sourceUiToken(row)
        val london = session.replace(listOf(row), MediaSort.NEWEST, ZoneId.of("Europe/London"))
        assertEquals(LocalDate.of(2026, 6, 9), session.dayOf(london, token)?.date)
        val losAngeles = session.replace(listOf(row), MediaSort.NEWEST, ZoneId.of("America/Los_Angeles"))
        assertNull(session.dayOf(london, token))
        assertEquals(LocalDate.of(2026, 6, 8), session.dayOf(losAngeles, token)?.date)
        assertNull(session.jump(losAngeles, LocalDate.of(2026, 6, 9)))
        assertEquals(token, session.jump(losAngeles, LocalDate.of(2026, 6, 8))?.sourceUiToken)
    }

    @Test fun springAndAutumnDstTransitionsGroupByLocalCalendarDay() {
        val session = DriftNavigationSession { true }
        val rows = listOf(
            item("spring-before", "2026-03-08T06:59:00Z"),
            item("spring-after", "2026-03-08T07:01:00Z"),
            item("autumn-first", "2026-11-01T05:30:00Z"),
            item("autumn-second", "2026-11-01T06:30:00Z"),
        )
        val snapshot = session.replace(rows, MediaSort.NAME, ZoneId.of("America/New_York"))
        val rail = session.rail(snapshot)!!
        assertEquals(listOf(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 3, 8)), rail.days.map { it.date })
        assertEquals(listOf(2, 2), rail.days.map { it.count })
        assertEquals(sourceUiToken(rows[0]), session.jump(snapshot, LocalDate.of(2026, 3, 8))?.sourceUiToken)
        assertEquals(sourceUiToken(rows[2]), session.jump(snapshot, LocalDate.of(2026, 11, 1))?.sourceUiToken)
    }

    @Test fun unknownDatesHaveARealReachableTargetAndRemainLastForEitherDateSort() {
        val session = DriftNavigationSession { true }
        val known = item("known", "2026-06-08T12:00:00Z")
        val zero = item("zero").copy(capturedAt = 0)
        val negative = item("negative").copy(capturedAt = Long.MIN_VALUE)
        for (sort in MediaSort.entries) {
            val snapshot = session.replace(listOf(zero, known, negative), sort, utc)
            val days = session.rail(snapshot)!!.days
            assertEquals(2, days.size)
            assertEquals(LocalDate.of(2026, 6, 8), days.first().date)
            assertNull(days.last().date)
            assertEquals(2, days.last().count)
            assertEquals(DriftTarget(sourceUiToken(zero), 0), session.jump(snapshot, null))
            assertNull(session.dayOf(snapshot, sourceUiToken(negative))!!.date)
        }
    }

    @Test fun nameDateJumpKeepsInterleavedFilenameOrderAndChoosesFirstRealMatch() {
        val session = DriftNavigationSession { true }
        val june8First = item("a", "2026-06-08T12:00:00Z").copy(name = "A.jpg")
        val june9 = item("b", "2026-06-09T12:00:00Z").copy(name = "B.jpg")
        val june8Second = item("c", "2026-06-08T13:00:00Z").copy(name = "C.jpg")
        val rows = libraryOrder(listOf(june8Second, june9, june8First), MediaFilter.ALL, MediaSort.NAME)
        val snapshot = session.replace(rows, MediaSort.NAME, utc)
        assertEquals(listOf(june8First, june9, june8Second), rows)
        assertEquals(DriftTarget(sourceUiToken(june8First), 0), session.jump(snapshot, LocalDate.of(2026, 6, 8)))
        assertEquals(2, session.dayOf(snapshot, sourceUiToken(june8Second))?.count)
        assertEquals(DriftTarget(sourceUiToken(june9), 1), session.move(snapshot, sourceUiToken(june8First), 1))
        assertEquals(DriftTarget(sourceUiToken(june8Second), 2), session.move(snapshot, sourceUiToken(june9), 1))
        assertEquals(listOf(LocalDate.of(2026, 6, 9), LocalDate.of(2026, 6, 8)), session.rail(snapshot)!!.days.map { it.date })
    }

    @Test fun railDateOrderMatchesDateSortWithoutReorderingTheGivenView() {
        val session = DriftNavigationSession { true }
        val old = item("old", "2026-06-08T12:00:00Z")
        val recent = item("new", "2026-06-09T12:00:00Z")
        for (sort in listOf(MediaSort.NEWEST, MediaSort.OLDEST)) {
            val rows = libraryOrder(listOf(old, recent), MediaFilter.ALL, sort)
            val snapshot = session.replace(rows, sort, utc)
            assertEquals(rows.map(::sourceUiToken), session.rail(snapshot)!!.days.map { it.first.sourceUiToken })
            assertEquals(0, session.first(snapshot)?.index)
        }
    }

    @Test fun seventeenThousandRowsKeepEveryDayReachableThroughBoundedWindows() {
        val session = DriftNavigationSession { true }
        val base = Instant.parse("2026-06-08T12:00:00Z").toEpochMilli()
        val rows = (0 until 16_999).map { index -> item("row-$index").copy(capturedAt = base + index * 86_400_000L) } + item("unknown")
        val snapshot = session.replace(rows, MediaSort.OLDEST, utc)
        assertEquals(17_000, snapshot.itemCount)
        assertEquals(DriftTarget(sourceUiToken(rows.last()), 16_999), session.target(snapshot, sourceUiToken(rows.last())))
        val reached = mutableSetOf<String>()
        var start: Int? = 0
        while (start != null) {
            val window = session.rail(snapshot, start = start)!!
            assertTrue(window.days.size in 1..7)
            assertEquals(17_000, window.totalDays)
            window.days.forEach { day ->
                reached.add(day.first.sourceUiToken)
                assertEquals(day.first, session.jump(snapshot, day.date))
            }
            if (window.previousStart != null) assertTrue(window.previousStart < window.start)
            if (window.nextStart != null) assertTrue(window.nextStart > window.start)
            start = window.nextStart
        }
        assertEquals(rows.map(::sourceUiToken).toSet(), reached)
        assertEquals(DriftTarget(sourceUiToken(rows.last()), 16_999), session.jump(snapshot, null))
        assertNull(session.rail(snapshot, sourceUiToken(rows.last()))!!.days.last().date)
        val centered = session.rail(snapshot, sourceUiToken(rows[8_500]))!!
        assertEquals(7, centered.days.size)
        assertEquals(sourceUiToken(rows[8_500]), centered.days[3].first.sourceUiToken)
        assertNull(session.rail(snapshot, start = -1))
        assertNull(session.rail(snapshot, start = 17_000))
        assertNull(session.rail(snapshot, start = Int.MAX_VALUE))
        assertNull(session.rail(snapshot, "missing", 0))
    }

    @Test fun emptyViewHasNoNavigationOrPhantomDatesAndOneItemCannotWrap() {
        val session = DriftNavigationSession { true }
        val empty = session.replace(emptyList(), MediaSort.NEWEST, utc)
        assertTrue(empty.isUsable)
        assertTrue(session.isCurrent(empty))
        assertNull(session.first(empty))
        assertNull(session.jump(empty, null))
        assertEquals(DriftDayWindow(emptyList(), 0, 0, null, null), session.rail(empty))
        assertNull(session.rail(empty, start = 1))
        val row = item("one").copy(capturedAt = Long.MAX_VALUE)
        val single = session.replace(listOf(row), MediaSort.NEWEST, utc)
        assertEquals(1, session.rail(single)!!.days.size)
        assertNull(session.move(single, sourceUiToken(row), -1))
        assertNull(session.move(single, sourceUiToken(row), 1))
        assertNull(session.jump(single, LocalDate.of(2026, 6, 8)))
    }

    private fun assertDenied(session: DriftNavigationSession, snapshot: DriftSnapshot, token: String) {
        assertFalse(session.isCurrent(snapshot))
        assertNull(session.first(snapshot))
        assertNull(session.target(snapshot, token))
        assertNull(session.positionOf(snapshot, token))
        assertNull(session.move(snapshot, token, 1))
        assertNull(session.jump(snapshot, null))
        assertNull(session.dayOf(snapshot, token))
        assertNull(session.rail(snapshot))
    }

    private fun item(id: String, time: String? = null) = MediaItem(
        id = id,
        name = "$id.jpg",
        mimeType = "image/jpeg",
        isVideo = false,
        capturedAt = time?.let { Instant.parse(it).toEpochMilli() } ?: 0,
        width = 100,
        height = 100,
        bytes = 100,
        durationMs = 0,
        albumId = "album",
        albumName = "Album",
        providerVersion = "epoch-a",
        providerGeneration = 30,
        externalHandle = id,
        generationAdded = 10,
        generationModified = 20,
    )
}
