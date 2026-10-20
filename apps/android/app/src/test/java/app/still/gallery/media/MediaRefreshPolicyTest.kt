package app.still.gallery.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaRefreshPolicyTest {
    @Test fun fullAccessInvalidationHidesOldRowsAndRejectsQueuedViewerAndGridActions() {
        val observed = listOf(item())
        assertEquals(AccessLevel.FULL, MediaAccess(37, images = true, videos = true).level)
        assertEquals(observed, galleryVisibleMedia(observed, loading = false, failed = false))
        assertEquals(observed, galleryActionItems(observed, observed, loading = false, failed = false, lifetime = GalleryActionLifetime { true }))
        // Even an accidentally retained old buffer cannot remain visible or actionable
        // while the unchanged-FULL permission scope reads new rows/legacy alias proof.
        assertTrue(galleryVisibleMedia(observed, loading = true, failed = false).isEmpty())
        assertTrue(galleryActionItems(observed, observed, loading = true, failed = false, lifetime = GalleryActionLifetime { true }).isEmpty())
        assertTrue(galleryActionItems(observed, emptyList(), loading = false, failed = false, lifetime = GalleryActionLifetime { true }).isEmpty())
        assertTrue(galleryVisibleMedia(observed, loading = false, failed = true).isEmpty())
        assertTrue(galleryActionItems(observed, observed, loading = false, failed = true, lifetime = GalleryActionLifetime { true }).isEmpty())
    }

    @Test fun queuedCallbackCannotActOnReusedUriAfterTheFreshReadCompletes() {
        val old = item()
        val replacement = old.copy(providerVersion = "reset", generationAdded = 20)
        assertEquals(old.id, replacement.id)
        assertTrue(galleryActionItems(listOf(old), listOf(replacement), loading = false, failed = false, lifetime = GalleryActionLifetime { true }).isEmpty())
        assertEquals(listOf(replacement), galleryActionItems(listOf(replacement), listOf(replacement), loading = false, failed = false, lifetime = GalleryActionLifetime { true }))
        // Never silently turn a stale mixed request into a partial share or favorite write.
        assertTrue(galleryActionItems(listOf(old, replacement), listOf(replacement), loading = false, failed = false, lifetime = GalleryActionLifetime { true }).isEmpty())
    }

    @Test fun queuedActionReadsLiveAuthorityBeforeCompositionDisposal() {
        val rows = listOf(item())
        var authorized = true
        val lifetime = GalleryActionLifetime { authorized }
        val queued = { galleryActionItems(rows, rows, loading = false, failed = false, lifetime = lifetime) }
        assertEquals(rows, queued())
        authorized = false // Controller authority can end before Compose disposal.
        assertTrue(queued().isEmpty())
        authorized = true
        assertEquals(rows, queued())
    }

    @Test fun disposedGalleryCannotReviveWhenAuthorityReturns() {
        val rows = listOf(item())
        var authorized = true
        val lifetime = GalleryActionLifetime { authorized }
        val queued = { galleryActionItems(rows, rows, loading = false, failed = false, lifetime = lifetime) }
        assertEquals(rows, queued())
        authorized = false
        lifetime.invalidate()
        authorized = true // Even later authentication cannot reactivate an old scope.
        assertTrue(queued().isEmpty())
        val fresh = GalleryActionLifetime { authorized }
        assertEquals(rows, galleryActionItems(rows, rows, loading = false, failed = false, lifetime = fresh))
        assertTrue(queued().isEmpty())
    }

    private fun item() = MediaItem(
        "content://media/external_primary/images/media/7", "photo.jpg", "image/jpeg", false,
        1_000, 800, 600, 1234, 0, "album", "Album", providerVersion = "a",
        providerGeneration = 100, externalHandle = "7", generationAdded = 10, generationModified = 90,
    )
}
