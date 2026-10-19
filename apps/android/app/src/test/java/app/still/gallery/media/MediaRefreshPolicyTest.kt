package app.still.gallery.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaRefreshPolicyTest {
    @Test fun fullAccessInvalidationHidesOldRowsAndRejectsQueuedViewerAndGridActions() {
        val observed = listOf(item())
        assertEquals(AccessLevel.FULL, MediaAccess(37, images = true, videos = true).level)
        assertEquals(observed, galleryVisibleMedia(observed, loading = false, failed = false))
        assertEquals(observed, galleryActionItems(observed, observed, loading = false, failed = false))
        // Even an accidentally retained old buffer cannot remain visible or actionable
        // while the unchanged-FULL permission scope reads new rows/legacy alias proof.
        assertTrue(galleryVisibleMedia(observed, loading = true, failed = false).isEmpty())
        assertTrue(galleryActionItems(observed, observed, loading = true, failed = false).isEmpty())
        assertTrue(galleryActionItems(observed, emptyList(), loading = false, failed = false).isEmpty())
        assertTrue(galleryVisibleMedia(observed, loading = false, failed = true).isEmpty())
        assertTrue(galleryActionItems(observed, observed, loading = false, failed = true).isEmpty())
    }

    @Test fun queuedCallbackCannotActOnReusedUriAfterTheFreshReadCompletes() {
        val old = item()
        val replacement = old.copy(providerVersion = "reset", generationAdded = 20)
        assertEquals(old.id, replacement.id)
        assertTrue(galleryActionItems(listOf(old), listOf(replacement), loading = false, failed = false).isEmpty())
        assertEquals(listOf(replacement), galleryActionItems(listOf(replacement), listOf(replacement), loading = false, failed = false))
        // Never silently turn a stale mixed request into a partial share or favorite write.
        assertTrue(galleryActionItems(listOf(old, replacement), listOf(replacement), loading = false, failed = false).isEmpty())
    }

    private fun item() = MediaItem(
        "content://media/external_primary/images/media/7", "photo.jpg", "image/jpeg", false,
        1_000, 800, 600, 1234, 0, "album", "Album", providerVersion = "a",
        providerGeneration = 100, externalHandle = "7", generationAdded = 10, generationModified = 90,
    )
}
