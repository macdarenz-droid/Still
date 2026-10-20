package app.still.gallery.media

import org.junit.Assert.*
import org.junit.Test

class CleanupActionPolicyTest {
    @Test fun delayedReadyLoadCannotOverwriteNewerUnknownMutationOrReread() {
        val fence = CleanupRequestFence { true }
        var status = "unknown"
        val oldLoad = fence.begin()
        val mutation = fence.begin()
        assertTrue(fence.publish(mutation) { status = "unknown after failed write" })
        assertFalse(fence.publish(oldLoad) { status = "ready old revision" })
        assertEquals("unknown after failed write", status)
        val reread = fence.begin()
        assertTrue(fence.publish(reread) { status = "ready new revision" })
        assertFalse(fence.publish(mutation) { status = "unknown old failure" })
        assertEquals("ready new revision", status)
    }

    @Test fun authorityAndLifetimeLossDenyOldAnnotationRequestsAcrossNewScope() {
        var authorized = true
        val lifetime = GalleryActionLifetime { authorized }
        val old = CleanupRequestFence { lifetime.isActive }
        val request = old.begin()
        authorized = false
        assertFalse(old.publish(request) { fail("old result published") })
        lifetime.invalidate()
        authorized = true
        assertFalse(old.publish(request) { fail("removed gallery revived") })
        val fresh = CleanupRequestFence { GalleryActionLifetime { authorized }.isActive }
        assertFalse(fresh.publish(request) { fail("cross-owner request published") })
        assertTrue(fresh.publish(fresh.begin()) {})
    }

    @Test fun newerAnnotationRequestPreventsOldQueuedMutationAdmission() {
        val fence = CleanupRequestFence { true }
        val queuedWrite = fence.begin()
        fence.invalidate()
        fence.begin()
        assertThrows(java.util.concurrent.CancellationException::class.java) { fence.requireCurrent(queuedWrite) }
    }

    @Test fun freshSnapshotMustResolveExactObservedTokensInCurrentGallery() {
        val a = cleanupTestItem(1)
        val b = cleanupTestItem(2)
        assertTrue(cleanupSnapshotMatchesGallery(listOf(a, b), listOf(b, a)))
        assertFalse(cleanupSnapshotMatchesGallery(listOf(a.copy(providerGeneration = 6), b), listOf(a, b)))
        assertFalse(cleanupSnapshotMatchesGallery(listOf(a.copy(generationAdded = 9), b), listOf(a, b)))
        assertFalse(cleanupSnapshotMatchesGallery(listOf(a), listOf(a, b)))
        assertFalse(cleanupSnapshotMatchesGallery(listOf(a, a.copy(modifiedAt = 99)), listOf(a)))
    }
}
