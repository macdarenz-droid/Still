package app.still.gallery.ui

import app.still.gallery.media.DriftNavigationSession
import app.still.gallery.media.DriftSnapshot
import app.still.gallery.media.MediaItem
import app.still.gallery.media.MediaSort
import app.still.gallery.media.sourceUiToken
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class DriftRibbonGateTest {
    private class Render(val permission: () -> Boolean, val move: (Int) -> Boolean)

    private class Gallery {
        var authorized = true
        val rows = listOf("A", "B", "C").map { name ->
            MediaItem("content://media/external_primary/images/media/$name", "$name.jpg", "image/jpeg", false,
                1000, 100, 100, 100, 0, "album", "Album")
        }
        val navigation = DriftNavigationSession { authorized }
        val interaction = DriftInteraction { authorized }
        var snapshot = navigation.replace(rows, MediaSort.NAME, ZoneId.of("UTC"))

        init { interaction.replace(snapshot, sourceUiToken(rows.first())) }

        fun render(): Render = capture(snapshot, interaction.currentFocus!!)

        private fun capture(projection: DriftSnapshot, entry: DriftFocusEntry): Render {
            // This is the same local-reference shape as StillApp's ::focusedCurrent.
            fun focusedCurrent(): Boolean = navigation.isCurrent(projection) && interaction.isCurrent(projection, entry)
            return Render(::focusedCurrent) { direction ->
                if (!focusedCurrent()) false
                else navigation.move(projection, entry.sourceToken, direction)?.let {
                    interaction.focus(projection, entry, it.sourceUiToken)
                } ?: false
            }
        }

        fun index(): Int = navigation.positionOf(snapshot, interaction.focusedToken!!)!!
        fun replaceRows() {
            navigation.invalidate()
            interaction.invalidate()
            snapshot = navigation.replace(rows, MediaSort.NAME, ZoneId.of("UTC"))
            interaction.replace(snapshot, sourceUiToken(rows.first()))
        }
    }

    @Test fun actualEqualLocalReferencesPublishTheDifferentCapturedPermission() {
        val gallery = Gallery()
        val first = gallery.render()
        val gate = DriftRibbonGate(first.permission)
        assertTrue(gate())
        assertTrue(first.move(1))
        val next = gallery.render()
        assertNotSame(first.permission, next.permission)
        assertEquals(first.permission, next.permission)
        assertFalse(first.permission())
        assertTrue(next.permission())
        gate.update(next.permission)
        assertTrue(gate()) // Structural state keeps the rejected first callback here.
        assertFalse(first.move(1)) // Updating the gate never retargets a captured action.
    }

    @Test fun repeatedSwipeAndButtonNavigationWorksWithoutRecreatingTheGate() {
        val gallery = Gallery()
        var render = gallery.render()
        val gate = DriftRibbonGate(render.permission)
        fun nextButton(direction: Int): Boolean = gate() && render.move(direction)
        fun swipeEnd(direction: Int): Boolean = gate() && render.move(direction)
        for (expected in listOf(1, 2, 1, 0, 1, 2, 1, 0)) {
            val before = gallery.index()
            val direction = if (expected > before) 1 else -1
            val accepted = if (expected % 2 == 0) nextButton(direction) else swipeEnd(direction)
            assertTrue("Current button/swipe must navigate after position $before", accepted)
            assertEquals(expected, gallery.index())
            val next = gallery.render()
            assertEquals(render.permission, next.permission)
            assertNotSame(render.permission, next.permission)
            gate.update(next.permission)
            render = next
        }
        assertFalse(nextButton(-1))
        assertEquals(0, gallery.index())
    }

    @Test fun updatingAvailabilityDoesNotReviveOldFocusOrAnABAAction() {
        val gallery = Gallery()
        val originalA = gallery.render()
        val gate = DriftRibbonGate(originalA.permission)
        assertTrue(originalA.move(1))
        val b = gallery.render()
        gate.update(b.permission)
        assertTrue(gate())
        assertTrue(b.move(-1))
        val returnedA = gallery.render()
        gate.update(returnedA.permission)
        assertTrue(gate())
        assertFalse(originalA.permission())
        assertFalse(gate() && originalA.move(1))
        assertEquals(0, gallery.index())
        assertTrue(gate() && returnedA.move(1))
    }

    @Test fun replacementAndAuthorityLossDenyOldCallbacksButFreshScopeCanNavigate() {
        val gallery = Gallery()
        val old = gallery.render()
        val gate = DriftRibbonGate(old.permission)
        gallery.replaceRows()
        assertFalse(gate())
        val fresh = gallery.render()
        gate.update(fresh.permission)
        assertTrue(gate())
        assertFalse(gate() && old.move(1))
        gallery.authorized = false
        assertFalse(gate())
        assertFalse(fresh.move(1))
        gallery.authorized = true
        gallery.replaceRows()
        val authorized = gallery.render()
        gate.update(authorized.permission)
        assertTrue(gate())
        assertFalse(fresh.move(1))
        assertTrue(gate() && authorized.move(1))
    }
}
