package app.still.gallery.ui

import app.still.gallery.media.MediaItem
import app.still.gallery.media.sourceUiToken
import org.junit.Assert.*
import org.junit.Test

class ComparePolicyTest {
    @Test fun comparisonCapturesExactCurrentPairInsteadOfRetargetingAChangedSelection() {
        val a = photo("1"); val b = photo("2"); val c = photo("3")
        val pair = listOf(a, b)
        val tokens = pair.map(::sourceUiToken).toSet()
        assertEquals(pair, compareSelectedPhotos(pair, tokens, listOf(c, b, a)))
        assertTrue(compareSelectedPhotos(pair, setOf(sourceUiToken(a), sourceUiToken(c)), listOf(a, b, c)).isEmpty())
        assertTrue(compareSelectedPhotos(pair, tokens, listOf(a, b.copy(generationModified = 5))).isEmpty())
        assertTrue(compareSelectedPhotos(pair, tokens, listOf(a)).isEmpty())
        assertTrue(compareSelectedPhotos(pair, tokens, listOf(a, a, b)).isEmpty())
    }

    @Test fun compareDoesNotBroadenSingleVideoAliasedOrThreeItemScope() {
        val a = photo("1"); val b = photo("2"); val c = photo("3")
        val view = listOf(a, b, c)
        assertTrue(compareSelectedPhotos(listOf(a), setOf(sourceUiToken(a)), view).isEmpty())
        assertTrue(compareSelectedPhotos(listOf(a, b, c), view.map(::sourceUiToken).toSet(), view).isEmpty())
        val video = b.copy(isVideo = true)
        assertTrue(compareSelectedPhotos(listOf(a, video), setOf(sourceUiToken(a), sourceUiToken(video)), listOf(a, video)).isEmpty())
        assertTrue(compareSelectedPhotos(listOf(a, a), setOf(sourceUiToken(a)), view).isEmpty())
    }

    @Test fun samplesExtremeDimensionsBeforeAllocationWithCheckedArithmetic() {
        for ((width, height) in listOf(1 to 1, 1536 to 1536, 1537 to 3000, 12000 to 8000, Int.MAX_VALUE to Int.MAX_VALUE, Int.MAX_VALUE to 1)) {
            val sample = compareSampleSize(width, height)
            assertEquals(0, sample and (sample - 1))
            val w = (width.toLong() + sample - 1) / sample
            val h = (height.toLong() + sample - 1) / sample
            assertTrue(w <= 1536 && h <= 1536 && w * h <= 4_000_000)
            if (sample > 1) assertTrue((width.toLong() + sample / 2 - 1) / (sample / 2) > 1536 ||
                (height.toLong() + sample / 2 - 1) / (sample / 2) > 1536)
        }
        assertThrows(IllegalArgumentException::class.java) { compareSampleSize(0, 100) }
        assertThrows(IllegalArgumentException::class.java) { compareSampleSize(100, -1) }
    }

    @Test fun actualDecoderOutputMustMeetDimensionsPixelsAndAllocationBounds() {
        assertTrue(comparePreviewFits(1536, 1536, 1536L * 1536 * 4))
        assertFalse(comparePreviewFits(1537, 1, 1537 * 4L))
        assertFalse(comparePreviewFits(1, 1537, 1537 * 4L))
        assertFalse(comparePreviewFits(100, 100, 16_000_001))
        assertFalse(comparePreviewFits(0, 10, 400))
        assertFalse(comparePreviewFits(1, 1, 0))
        assertFalse(comparePreviewFits(Int.MAX_VALUE, Int.MAX_VALUE, Long.MAX_VALUE))
    }

    @Test fun allEightExifTransformsPreserveCornersIncludingMirrorsAndSwappedAxes() {
        val expected = listOf(1 to 2, -1 to 2, -1 to -2, 1 to -2, 2 to 1, -2 to 1, -2 to -1, 2 to -1)
        for (exif in 1..8) {
            val matrix = compareOrientation(exif)
            val mapped = matrix.xx + matrix.xy * 2 to matrix.yx + matrix.yy * 2
            assertEquals("EXIF $exif", expected[exif - 1], mapped)
            assertEquals(exif >= 5, matrix.swapsAxes)
            assertEquals(1, kotlin.math.abs(matrix.xx * matrix.yy - matrix.xy * matrix.yx))
        }
        assertThrows(IllegalArgumentException::class.java) { compareOrientation(0) }
        assertThrows(IllegalArgumentException::class.java) { compareOrientation(9) }
    }

    @Test fun linkedTransformsClampAndResetPanWhenZoomReturnsToOne() {
        val zoomed = CompareTransform().change(99f, 100f, -100f)
        assertEquals(CompareTransform(5f, 2f, -2f), zoomed)
        assertEquals(CompareTransform(), zoomed.change(.01f))
        assertEquals(CompareTransform(), CompareTransform().change(1f, .9f, -.9f))
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, 0f, -1f)) assertEquals(zoomed, zoomed.change(invalid))
        assertEquals(zoomed, zoomed.change(dx = Float.NaN))
        assertEquals(zoomed, zoomed.change(dy = Float.NEGATIVE_INFINITY))
    }

    @Test fun sameLookingReopenAndAuthorityReturnCannotReviveOldRequests() {
        var allowed = true
        val fence = CompareRequestFence { allowed }
        val first = fence.open()!!
        val second = fence.open()!!
        assertFalse(fence.isCurrent(first))
        assertTrue(fence.isCurrent(second))
        allowed = false
        assertFalse(fence.isCurrent(second))
        allowed = true
        assertFalse(fence.isCurrent(second))
        val third = fence.open()!!
        fence.invalidate()
        assertFalse(fence.isCurrent(third))
    }

    private fun photo(id: String) = MediaItem("content://media/external_primary/images/media/$id", "same.jpg", "image/jpeg", false,
        100, 500, 500, 100, 0, "album", "Album", providerVersion = "v1", providerGeneration = 5,
        externalHandle = id, generationAdded = 1, generationModified = 2)
}
