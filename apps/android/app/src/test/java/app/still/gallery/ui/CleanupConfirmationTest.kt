package app.still.gallery.ui

import org.junit.Assert.*
import org.junit.Test

class CleanupConfirmationTest {
    @Test fun cancelledAndReopenedIdenticalDialogRejectsTheRetainedConfirm() {
        val identity = Any()
        val dialogs = CleanupConfirmationState(identity)
        var writes = 0
        dialogs.open(0, true)
        val old = requireNotNull(dialogs.current)
        dialogs.dismiss(old)
        assertFalse(dialogs.confirm(old, identity) { writes++ })
        dialogs.open(0, true)
        val fresh = requireNotNull(dialogs.current)
        assertNotSame(old, fresh)
        assertFalse(dialogs.confirm(old, identity) { writes++ })
        assertSame(fresh, dialogs.current)
        assertTrue(dialogs.confirm(fresh, identity) { writes++ })
        assertFalse(dialogs.confirm(fresh, identity) { writes++ })
        assertEquals(1, writes)
    }

    @Test fun consumedRequestCannotReenterAndRemovedDialogCannotRevive() {
        val identity = Any()
        val old = CleanupConfirmationState(identity)
        old.open(0, false)
        val request = requireNotNull(old.current)
        var writes = 0
        assertTrue(old.confirm(request, identity) {
            writes++
            assertFalse(old.confirm(request, identity) { writes++ })
        })
        old.open(1, true)
        val removed = requireNotNull(old.current)
        old.invalidate()
        old.open(1, true)
        assertNull(old.current)
        assertFalse(old.confirm(removed, identity) { writes++ })
        val freshIdentity = Any()
        val fresh = CleanupConfirmationState(freshIdentity)
        fresh.open(1, true)
        assertFalse(fresh.confirm(removed, freshIdentity) { writes++ })
        assertTrue(fresh.confirm(requireNotNull(fresh.current), freshIdentity) { writes++ })
        assertEquals(2, writes)
    }

    @Test fun sameShapedNewReviewCannotRetargetPendingConfirmation() {
        val row = CleanupMemberUi(0, "same.jpg", false, 3, false, CleanupFavoriteState.NOT_FAVORITE, false)
        val groups = listOf(CleanupGroupUi(0, listOf(row, row.copy(id = 1)), false, CleanupReviewReason.NEEDS_KEEPER, 0))
        val first = CleanupUiState(phase = CleanupPhase.COMPLETE, groups = groups, interactionIdentity = Any())
        val second = first.copy(interactionIdentity = Any())
        assertEquals(first.groups, second.groups)
        val oldDialog = CleanupConfirmationState(first.interactionIdentity)
        oldDialog.open(0, true)
        val old = requireNotNull(oldDialog.current)
        var writes = 0
        assertFalse(oldDialog.confirm(old, second.interactionIdentity) { writes++ })
        val newDialog = CleanupConfirmationState(second.interactionIdentity)
        newDialog.open(0, true)
        assertFalse(newDialog.confirm(old, second.interactionIdentity) { writes++ })
        assertTrue(newDialog.confirm(requireNotNull(newDialog.current), second.interactionIdentity) { writes++ })
        assertEquals(1, writes)
    }

}
