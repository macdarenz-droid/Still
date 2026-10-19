package app.still.gallery.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaPolicyTest {
    @Test fun legacyGrantDoesNotImplyAndroid13Access() {
        assertEquals(AccessLevel.FULL, MediaAccess(32, legacy = true).level)
        assertEquals(AccessLevel.NONE, MediaAccess(33, legacy = true).level)
    }

    @Test fun android13SingleMediaTypeIsPartial() {
        val access = MediaAccess(33, images = true)
        assertEquals(AccessLevel.PARTIAL, access.level)
        assertTrue(access.canReadImages)
        assertFalse(access.canReadVideos)
    }

    @Test fun android14SelectedAccessQueriesBothCollectionsWithoutClaimingFullAccess() {
        val access = MediaAccess(34, selected = true)
        assertEquals(AccessLevel.PARTIAL, access.level)
        assertTrue(access.canReadImages)
        assertTrue(access.canReadVideos)
        assertEquals(AccessLevel.NONE, MediaAccess(33, selected = true).level)
    }

    @Test fun fullAccessRequiresBothGrantsAndRevocationRemovesAccess() {
        assertEquals(AccessLevel.FULL, MediaAccess(35, images = true, videos = true).level)
        assertEquals(AccessLevel.NONE, MediaAccess(35).level)
    }

    @Test fun permissionLossPrunesOnlyVisibleSelectionAndDoesNotAlterSavedFavorites() {
        val favorites = setOf("a", "b")
        assertEquals(setOf("b"), visibleSelection(favorites, listOf(item("b"))))
        assertEquals(setOf("a", "b"), favorites)
    }

    @Test fun mixedSharingDoesNotMislabelVideosAsImages() {
        assertEquals("image/*", shareMimeType(listOf(item("image"))))
        assertEquals("video/*", shareMimeType(listOf(item("video", true))))
        assertEquals("*/*", shareMimeType(listOf(item("image"), item("video", true))))
    }

    @Test fun aDecodeCompletingAfterPermissionInvalidationCannotRepopulateCache() {
        val epoch = AccessEpoch()
        val cache = mutableListOf("old")
        val startedBeforeRevoke = epoch.snapshot()
        epoch.invalidate { cache.clear() }
        assertFalse(epoch.commitIfCurrent(startedBeforeRevoke) { cache += "revoked-photo" })
        assertTrue(cache.isEmpty())
        assertTrue(epoch.commitIfCurrent(epoch.snapshot()) { cache += "permitted-photo" })
        assertEquals(listOf("permitted-photo"), cache)
    }

    private fun item(id: String, video: Boolean = false) = MediaItem(
        id, id, if (video) "video/mp4" else "image/jpeg", video, 0, 0, 0, 0, 0, "", "",
    )
}
