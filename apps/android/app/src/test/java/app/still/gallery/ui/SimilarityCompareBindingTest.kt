package app.still.gallery.ui

import app.still.gallery.media.MediaItem
import app.still.gallery.media.sourceUiToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test

class SimilarityCompareBindingTest {
    @Test fun selectionIsBoundedCompleteAndDoesNotFilterByCaptureDate() {
        val photos = (1..200).map(::photo)
        assertEquals(photos, similaritySelectedPhotos(photos, photos.map(::sourceUiToken).toSet(), photos.reversed()))
        for (count in listOf(0, 1, 201)) {
            val chosen = (1..count).map(::photo)
            assertTrue(similaritySelectedPhotos(chosen, chosen.map(::sourceUiToken).toSet(), chosen).isEmpty())
        }
        val pair = photos.take(2)
        assertTrue(similaritySelectedPhotos(pair, pair.map(::sourceUiToken).toSet(), pair.take(1)).isEmpty())
        assertTrue(similaritySelectedPhotos(pair, pair.map(::sourceUiToken).toSet(), pair + pair[0]).isEmpty())
        val video = pair[1].copy(isVideo = true)
        assertTrue(similaritySelectedPhotos(listOf(pair[0], video), setOf(sourceUiToken(pair[0]), sourceUiToken(video)), listOf(pair[0], video)).isEmpty())
    }

    @Test fun changedSelectionOrReusedSourceCannotReplaceTheCapturedScope() {
        val a = photo(1); val b = photo(2); val c = photo(3)
        val pair = listOf(a, b)
        assertTrue(similaritySelectedPhotos(pair, setOf(sourceUiToken(a), sourceUiToken(c)), listOf(a, b, c)).isEmpty())
        assertTrue(similaritySelectedPhotos(pair, pair.map(::sourceUiToken).toSet(), listOf(a, b.copy(generationModified = 9))).isEmpty())
        assertTrue(similaritySelectedPhotos(listOf(a, a), setOf(sourceUiToken(a)), listOf(a, b)).isEmpty())
    }

    @Test fun largeViewIsResolvedLinearlyAndRepeatedSourceChecksDoNotWalkItAgain() {
        val all = (1..17_000).map(::photo)
        val view = CountingList(all)
        val selected = all.takeLast(200)
        val tokens = selected.map(::sourceUiToken)
        assertEquals(selected, similaritySelectedPhotos(selected, tokens.toSet(), view))
        assertTrue("one metadata pass, not selected × library", view.reads <= 17_000 * 2)
        view.reads = 0
        val projection = SimilaritySelectionProjection(selected, tokens, view, view)
        assertEquals(selected, projection.rows)
        assertTrue(view.reads <= 17_000 * 3)
        view.reads = 0
        repeat(20_000) { assertTrue(projection.isCurrent(tokens, view)) }
        assertEquals("stream checks must not traverse metadata", 0, view.reads)
        assertFalse(projection.isCurrent(tokens.toMutableList(), view))
        assertFalse(projection.isCurrent(tokens, all.toMutableList()))
        val reused = all.dropLast(1) + all.last().copy(generationModified = 100)
        assertTrue(similaritySelectedPhotos(selected, tokens.toSet(), reused).isEmpty())
        assertTrue(similaritySelectedPhotos(selected, tokens.toSet(), all + all.last()).isEmpty())
        assertTrue(similarityCurrentPhotos(selected, all + all.last().copy(generationModified = 100)).isEmpty())
    }

    private class CountingList(private val rows: List<MediaItem>) : AbstractList<MediaItem>() {
        var reads = 0
        override val size: Int get() = rows.size
        override fun get(index: Int): MediaItem { reads++; return rows[index] }
    }

    @Test fun exactChoiceIdentityRejectsPairABAAndForeignOriginClose() {
        val binding = SimilarityCompareBinding { true }
        val request = SimilarityRequest(); val result = Any(); val pairA = Any(); val pairB = Any()
        val rows = listOf(photo(1), photo(2))
        val oldA = binding.choose(request, result, pairA, { true }, { true }, { rows })!!
        val b = binding.choose(request, result, pairB, { true }, { true }, { rows })!!
        val newA = binding.choose(request, result, pairA, { true }, { true }, { rows })!!
        assertFalse(binding.isCurrent(oldA)); assertFalse(binding.isCurrent(b)); assertTrue(binding.isCurrent(newA))
        assertFalse(binding.invalidate(SimilarityRequest()))
        assertTrue(binding.isCurrent(newA))
        assertTrue(binding.invalidate(request))
        assertFalse(binding.isCurrent(newA))
    }

    @Test fun withdrawnSourcesAndAuthorityNeverReviveTheOldChoice() {
        var allowed = true
        var currentRows = listOf(photo(1), photo(2))
        val original = currentRows
        val binding = SimilarityCompareBinding { allowed }
        val choice = binding.choose(SimilarityRequest(), Any(), Any(), { true }, { true }, { currentRows })!!
        currentRows = listOf(photo(1), photo(2).copy(generationModified = 55))
        assertFalse(binding.isCurrent(choice))
        currentRows = original
        assertFalse(binding.isCurrent(choice))
        val next = binding.choose(SimilarityRequest(), Any(), Any(), { true }, { true }, { currentRows })!!
        allowed = false; assertFalse(binding.isCurrent(next))
        allowed = true; assertFalse(binding.isCurrent(next))
    }

    @Test fun refusedOrStaleCompareActionCannotReplaceAnActiveOrigin() {
        val binding = SimilarityCompareBinding { true }
        val rows = listOf(photo(1), photo(2))
        val active = binding.choose(SimilarityRequest(), Any(), Any(), { true }, { true }, { rows })!!
        assertNull(binding.choose(SimilarityRequest(), Any(), Any(), { false }, { true }, { rows }))
        assertNull(binding.choose(SimilarityRequest(), Any(), Any(), { true }, { false }, { rows }))
        assertNull(binding.choose(SimilarityRequest(), Any(), Any(), { true }, { true }, { rows.take(1) }))
        assertTrue(binding.isCurrent(active))
    }

    @Test fun productionCompareEntryPreparesFreshCurrentPairWithoutChangingGallerySelection() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val compare = CompareCoordinator<Preview>(scope, { true }, { CompareCleanup.CLEAN }, Dispatchers.Unconfined, Dispatchers.Unconfined)
        val binding = SimilarityCompareBinding { true }
        val request = SimilarityRequest(); val result = Any(); val pair = Any()
        val rows = listOf(photo(1), photo(2))
        val gallerySelection = setOf(sourceUiToken(photo(3)))
        var preparations = 0
        var stagedRows = emptyList<MediaItem>()
        fun work(photos: List<MediaItem>, check: () -> Boolean): CompareWork<Preview> {
            preparations++; stagedRows = photos
            return object : CompareWork<Preview> {
                override suspend fun prepare(progress: (CompareProgress) -> Unit): List<Preview> { kotlin.check(check()); return listOf(Preview(), Preview()) }
                override fun invalidate() = Unit
                override suspend fun cleanup() = CompareCleanup.CLEAN
            }
        }
        try {
            compare.initialize()
            val oldAction = compare.actionIdentity
            assertTrue(binding.startComparison(request, result, pair, compare, oldAction, { true }, { rows }, ::work))
            assertEquals(rows, stagedRows); assertEquals(1, preparations)
            assertEquals(setOf(sourceUiToken(photo(3))), gallerySelection)
            compare.close(compare.state.request)
            assertFalse(binding.startComparison(request, result, pair, compare, oldAction, { true }, { rows }, ::work))
            assertEquals(1, preparations)
            assertTrue(binding.startComparison(request, result, pair, compare, compare.actionIdentity, { true }, { rows }, ::work))
            assertEquals(2, preparations)
        } finally { compare.dispose(); scope.cancel() }
    }

    @Test fun originWithdrawalFencesAlreadyPreparedCompareBeforeItIsClosed() {
        var originCurrent = true
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val compare = CompareCoordinator<Preview>(scope, { true }, { CompareCleanup.CLEAN }, Dispatchers.Unconfined, Dispatchers.Unconfined)
        val binding = SimilarityCompareBinding { true }
        var cleaned = 0
        try {
            compare.initialize()
            binding.startComparison(SimilarityRequest(), Any(), Any(), compare, compare.actionIdentity,
                { originCurrent }, { listOf(photo(1), photo(2)) }) { _, check -> object : CompareWork<Preview> {
                    override suspend fun prepare(progress: (CompareProgress) -> Unit): List<Preview> { kotlin.check(check()); return listOf(Preview(), Preview()) }
                    override fun invalidate() = Unit
                    override suspend fun cleanup(): CompareCleanup { cleaned++; return CompareCleanup.CLEAN }
                } }
            assertTrue(compare.state.visible)
            originCurrent = false
            assertFalse(compare.state.visible)
            assertTrue(compare.state.previews.isEmpty())
            assertEquals(1, cleaned)
            assertTrue(compare.canStart)
        } finally { compare.dispose(); scope.cancel() }
    }

    private class Preview : AutoCloseable { override fun close() = Unit }
    private fun photo(id: Int) = MediaItem("content://media/external_primary/images/media/$id", "same.jpg", "image/jpeg", false,
        if (id % 2 == 0) 1_735_689_600_000 else 946_684_800_000, 500, 500, 100, 0, "album", "Album",
        providerVersion = "v1", providerGeneration = 8, externalHandle = "$id", generationAdded = 1, generationModified = 2)
}
