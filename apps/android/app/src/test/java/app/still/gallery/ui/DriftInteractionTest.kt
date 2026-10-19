package app.still.gallery.ui

import org.junit.Assert.*
import org.junit.Test

class DriftInteractionTest {
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
        interaction.replace(fresh, "same-token")
        assertFalse(interaction.focus(old, "same-token", "old-day-target"))
        assertEquals("same-token", interaction.focusedToken)
        assertTrue(interaction.focus(fresh, "same-token", "current-day-target"))
    }

    @Test fun queuedSwipeCannotOverrideARealLaterButtonChoice() {
        val projection = Any()
        val interaction = DriftInteraction { true }
        interaction.replace(projection, "first")
        assertTrue(interaction.focus(projection, "first", "button-choice"))
        assertFalse(interaction.focus(projection, "first", "late-swipe"))
        assertEquals("button-choice", interaction.focusedToken)
        assertTrue(interaction.focus(projection, "button-choice", "next-choice"))
    }

    @Test fun authorityLossAndInvalidationDenyIntentBeforeCompositionDisposal() {
        var allowed = true
        val projection = Any()
        val interaction = DriftInteraction { allowed }
        interaction.replace(projection, "source")
        allowed = false
        assertFalse(interaction.focus(projection, "source", "blocked"))
        interaction.invalidate()
        allowed = true
        assertFalse(interaction.focus(projection, null, "revived"))
        val fresh = Any()
        interaction.replace(fresh, "new-source")
        assertTrue(interaction.focus(fresh, "new-source", "new-choice"))
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
