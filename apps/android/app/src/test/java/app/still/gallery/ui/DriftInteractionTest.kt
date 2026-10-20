package app.still.gallery.ui

import app.still.gallery.media.DriftNavigationSession
import app.still.gallery.media.MediaItem
import app.still.gallery.media.MediaSort
import app.still.gallery.media.sourceUiToken
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class DriftInteractionTest {
    @Test fun dismissedOverlayCannotEditOrCloseReopeningButActiveOptionsCanKeepEditing() {
        var authorized = true
        val overlays = DriftOverlaySession { authorized }
        val cancelled = overlays.open()!!
        assertTrue(overlays.close(cancelled))
        val reopened = overlays.open()!!
        var edits = 0
        assertFalse(overlays.edit(cancelled) { edits++ })
        assertFalse(overlays.close(cancelled))
        assertTrue(overlays.edit(reopened) { edits++ })
        assertTrue(overlays.edit(reopened) { edits++ })
        assertTrue(overlays.isCurrent(reopened))
        assertEquals(2, edits)
        authorized = false
        assertFalse(overlays.edit(reopened) { edits++ })
        overlays.invalidate()
        authorized = true
        assertFalse(overlays.close(reopened))
        assertFalse(overlays.edit(reopened) { edits++ })
        assertEquals(2, edits)
    }

    @Test fun railPagingRenewsIntentEvenWhenFocusAndPageReturnToTheirOriginalValues() {
        val projection = Any()
        val interaction = DriftInteraction { true }
        interaction.replace(projection, "A")
        val pageZero = interaction.currentFocus
        assertTrue(interaction.page(projection, pageZero, 7))
        assertTrue(interaction.page(projection, interaction.currentFocus, 14))
        assertFalse(interaction.page(projection, pageZero, 7))
        assertEquals(14, interaction.railStart)
        assertTrue(interaction.page(projection, interaction.currentFocus, 0))
        assertFalse(interaction.page(projection, pageZero, 7))
        assertEquals(0, interaction.railStart)
        assertEquals("A", interaction.focusedToken)
        assertTrue(interaction.page(projection, interaction.currentFocus, 7))
    }

    @Test fun retainedBroadBatchCannotActOnNewNarrowViewSelection() {
        fun row(id: String) = MediaItem(id, "$id.jpg", "image/jpeg", false, 1000, 100, 100, 10, 0, "album", "Album")
        val rows = listOf(row("A"), row("B"))
        val navigation = DriftNavigationSession { true }
        val broad = navigation.replace(rows, MediaSort.NAME, ZoneId.of("UTC"))
        val selection = setOf(sourceUiToken(rows[1]))
        val narrow = navigation.replace(listOf(rows[1]), MediaSort.NAME, ZoneId.of("UTC"))
        assertTrue(driftBatchItems(navigation, broad, selection, rows).isEmpty())
        assertEquals(listOf(rows[1]), driftBatchItems(navigation, narrow, selection, listOf(rows[1])))
    }

    @Test fun nonfirstFocusSurvivesLoadingAndSafeRefreshWithoutRevivingOldIntent() {
        fun row(id: String, watermark: Long, modified: Long = 2) = MediaItem(
            "content://media/external_primary/images/media/$id", "$id.jpg", "image/jpeg", false,
            1000, 100, 100, 100, 0, "album", "Album", providerVersion = "provider-v1",
            providerGeneration = watermark, externalHandle = id, generationAdded = 1, generationModified = modified,
        )
        val before = listOf(row("1", 10), row("2", 10))
        val after = listOf(row("1", 11), row("2", 11))
        val navigation = DriftNavigationSession { true }
        val interaction = DriftInteraction { true }
        val memory = DriftFocusMemory()
        val old = navigation.replace(before, MediaSort.NEWEST, ZoneId.of("UTC"))
        interaction.replace(old, sourceUiToken(before[0]))
        memory.capture(interaction.focusedToken)
        assertTrue(driftRememberOpenedSource(navigation, old, interaction, memory, sourceUiToken(before[1])))
        assertEquals(sourceUiToken(before[1]), interaction.focusedToken)
        assertEquals(sourceUiToken(before[1]), memory.token)
        val oldIntent = interaction.currentFocus
        memory.capture(interaction.focusedToken)
        navigation.invalidate(); interaction.invalidate()
        memory.settle(null, loading = true) // The empty interim view is not a new choice.
        assertEquals(sourceUiToken(before[1]), memory.token)
        memory.reconcile(after)
        val fresh = navigation.replace(after, MediaSort.NEWEST, ZoneId.of("UTC"))
        val restored = navigation.target(fresh, memory.token!!)!!
        assertEquals(1, restored.index)
        interaction.replace(fresh, restored.sourceUiToken)
        assertFalse(interaction.isCurrent(old, oldIntent))
        assertTrue(interaction.isCurrent(fresh, interaction.currentFocus))
        // Mode/sort rebuilds retain only the exact current token with a new intent.
        val mode = navigation.replace(after, MediaSort.NAME, ZoneId.of("UTC"))
        assertEquals(1, navigation.target(mode, memory.token!!)!!.index)
        memory.reconcile(listOf(row("1", 12), row("2", 12, modified = 3)))
        assertNull(memory.token) // A same-URI edit cannot inherit the position intent.
    }

    @Test fun cancelledDatePickerCannotNavigateOrDismissIdenticalReopening() {
        val focus = Any()
        val dates = DriftDatePicker { focus }
        val old = dates.open(focus)!!
        assertTrue(dates.dismiss(old))
        var jumps = 0
        assertFalse(dates.confirm(old) { jumps++; true })
        assertEquals(0, jumps)
        val reopened = dates.open(focus)!!
        assertFalse(dates.dismiss(old))
        assertFalse(dates.confirm(old) { jumps++; true })
        assertSame(reopened, dates.current)
        assertTrue(dates.confirm(reopened) { jumps++; true })
        assertFalse(dates.confirm(reopened) { jumps++; true })
        assertEquals(1, jumps)
    }

    @Test fun datePickerCannotRetargetAnEqualLookingNewFocusOrDisposedRibbon() {
        data class Focus(val source: String)
        var focus: Any? = Focus("same-source")
        val dates = DriftDatePicker { focus }
        val old = dates.open(focus!!)!!
        focus = Focus("same-source")
        assertEquals(old.focus, focus)
        var jumps = 0
        assertFalse(dates.confirm(old) { jumps++; true })
        val fresh = dates.open(focus!!)!!
        dates.invalidate()
        assertFalse(dates.dismiss(fresh))
        assertFalse(dates.confirm(fresh) { jumps++; true })
        assertEquals(0, jumps)
    }

    @Test fun retainedViewerCallbacksCannotCloseOrActOnReopenedSameSource() {
        val viewers = DriftViewerSession { true }
        val first = viewers.open("same-source-and-refresh")!!
        assertTrue(viewers.close(first))
        val reopened = viewers.open("same-source-and-refresh")!!
        assertFalse(viewers.isCurrent(first))
        assertFalse(viewers.close(first))
        assertSame(reopened, viewers.current)
        assertTrue(viewers.isCurrent(reopened))
        viewers.invalidate()
        assertFalse(viewers.isCurrent(reopened))
        assertFalse(viewers.close(reopened))
        assertNull(viewers.current)
    }

    @Test fun equalLookingReplacementCannotReceiveOldGestureOrDateAction() {
        data class Projection(val rows: List<String>)
        val old = Projection(listOf("same-row"))
        val fresh = Projection(listOf("same-row"))
        assertEquals(old, fresh)
        val interaction = DriftInteraction { true }
        interaction.replace(old, "same-token")
        val oldIntent = interaction.currentFocus
        interaction.replace(fresh, "same-token")
        assertFalse(interaction.focus(old, oldIntent, "old-day-target"))
        assertEquals("same-token", interaction.focusedToken)
        assertTrue(interaction.focus(fresh, interaction.currentFocus, "current-day-target"))
    }

    @Test fun queuedSwipeCannotOverrideARealLaterButtonChoice() {
        val projection = Any()
        val interaction = DriftInteraction { true }
        interaction.replace(projection, "first")
        val firstIntent = interaction.currentFocus
        assertTrue(interaction.focus(projection, firstIntent, "button-choice"))
        assertFalse(interaction.focus(projection, firstIntent, "late-swipe"))
        assertEquals("button-choice", interaction.focusedToken)
        assertTrue(interaction.focus(projection, interaction.currentFocus, "next-choice"))
    }

    @Test fun authorityLossAndInvalidationDenyIntentBeforeCompositionDisposal() {
        var allowed = true
        val projection = Any()
        val interaction = DriftInteraction { allowed }
        interaction.replace(projection, "source")
        allowed = false
        assertFalse(interaction.focus(projection, interaction.currentFocus, "blocked"))
        interaction.invalidate()
        allowed = true
        assertFalse(interaction.focus(projection, null, "revived"))
        val fresh = Any()
        interaction.replace(fresh, "new-source")
        assertTrue(interaction.focus(fresh, interaction.currentFocus, "new-choice"))
    }

    @Test fun returningToSameSourceDoesNotReviveEarlierFocusCallbacks() {
        val projection = Any()
        val interaction = DriftInteraction { true }
        interaction.replace(projection, "A")
        val originalA = interaction.currentFocus
        assertTrue(interaction.focus(projection, originalA, "B"))
        assertTrue(interaction.focus(projection, interaction.currentFocus, "A"))
        val returnedA = interaction.currentFocus
        assertEquals(originalA!!.sourceToken, returnedA!!.sourceToken)
        assertFalse(interaction.focus(projection, originalA, "queued-old-drag"))
        assertSame(returnedA, interaction.currentFocus)
        assertTrue(interaction.focus(projection, returnedA, "current-action"))
        val sameSource = interaction.currentFocus
        assertTrue(interaction.focus(projection, sameSource, "current-action"))
        assertNotSame(sameSource, interaction.currentFocus)
        assertFalse(interaction.isCurrent(projection, sameSource))
    }

    @Test fun seventeenThousandItemsComposeOnlyTheCurrentWindow() {
        for (index in listOf(0, 1, 2, 8500, 16998, 16999)) {
            val cards = driftCardIndices(17000, index).toList()
            assertTrue(index in cards)
            assertTrue(cards.size <= 5)
            assertTrue(cards.all { it in 0 until 17000 })
        }
        assertEquals(listOf(0, 1, 2), driftCardIndices(17000, 0).toList())
        assertEquals(listOf(16997, 16998, 16999), driftCardIndices(17000, 16999).toList())
        assertTrue(driftCardIndices(0, 0).isEmpty())
        assertTrue(driftCardIndices(2, 2).isEmpty())
    }
}
