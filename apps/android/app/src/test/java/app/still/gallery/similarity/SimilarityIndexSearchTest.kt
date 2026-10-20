package app.still.gallery.similarity

import app.still.gallery.media.MediaItem
import org.junit.Assert.*
import org.junit.Test

class SimilarityIndexSearchTest {
    private val incarnation = "a".repeat(64)
    private val namespaces = mapOf(similarityIndexNamespaceKey("media", "external") to incarnation)
    private fun identity(item: MediaItem) = requireNotNull(similarityIndexIdentity(item, incarnation))
    private fun item(id: Long) = MediaItem(
        id = "content://media/external/images/media/$id", name = "photo-$id.jpg", mimeType = "image/jpeg", isVideo = false,
        capturedAt = 1_700_000_000_000 + id, width = 256, height = 192, bytes = 4_096, durationMs = 0,
        albumId = "album", albumName = "Camera", modifiedAt = id,
        providerAuthority = "media", volumeName = "external", providerVersion = "v1", providerGeneration = 40,
        externalHandle = id.toString(), generationAdded = id, generationModified = id, dateAddedSeconds = id,
    )

    private fun feature(hash: Long) = SimilarityFeature(hash, 4.0 / 3.0, DoubleArray(48) { 0.5 }, DoubleArray(12) { 1.0 / 12.0 })
    private fun analyzed(item: MediaItem, hash: Long) = SimilarityAnalyzedSource(item, feature(hash), "a".repeat(64), 4)

    private inner class FakeIndex(
        private val stored: List<Pair<MediaItem, SimilarityFeature>>,
        private val maximum: Int = 100,
        private val onCandidates: () -> Unit = {},
    ) : SimilarityFeatureIndex {
        private val identities = stored.associate { (item, _) ->
            val identity = identity(item)
            identity.sourceKey to (identity.sourceRevision to item)
        }
        override fun revisions() = identities.mapValues { it.value.first }
        override fun candidates(feature: SimilarityFeature, maximum: Int): SimilarityIndexCandidates {
            val blocks = similarityHashBuckets(feature.perceptualHash)
            val keys = stored.filter { (_, other) ->
                val otherBlocks = similarityHashBuckets(other.perceptualHash)
                blocks.indices.any { blocks[it] == otherBlocks[it] }
            }.map { identity(it.first).sourceKey }.sorted().distinct()
            val cap = minOf(maximum, this.maximum)
            onCandidates()
            return SimilarityIndexCandidates(keys.take(cap), keys.size > cap)
        }
        override fun features(sourceRevisions: Map<String, String>, sourceKeys: List<String>): Map<String, SimilarityFeature> =
            stored.mapNotNull { (item, feature) ->
                val identity = identity(item)
                if (identity.sourceKey in sourceKeys && sourceRevisions[identity.sourceKey] == identity.sourceRevision) identity.sourceKey to feature else null
            }.toMap()
    }

    @Test fun selectedPhotosFindOnlyCurrentIndexedDirectCandidatesAndKeepComparePairIdentity() {
        val first = item(1); val second = item(2); val match = item(3); val stale = item(4)
        val selected = listOf(
            SimilarityAnalyzedSource(first, feature(0), "a".repeat(64), 4),
            SimilarityAnalyzedSource(second, feature(1), "b".repeat(64), 4),
        )
        val index = FakeIndex(listOf(match to feature(1), stale to feature(2)))
        val query = searchSimilarityIndex(selected, listOf(first, second), listOf(first, second, match), index, namespaces)
        assertEquals(2, query.additionalPairs.size)
        assertEquals(2, query.coverage.pairChecks)
        assertEquals(3, query.coverage.currentAuthorizedPhotos)
        assertEquals(1, query.coverage.indexedCurrentPhotos)
        assertFalse(query.coverage.omittedWork)
        assertTrue(query.additionalPairs.all { it.first.id == first.id || it.first.id == second.id })
        assertTrue(query.additionalPairs.all { it.second.id == match.id })

        val session = SimilaritySession()
        val ticket = session.begin(listOf(first, second)) {}
        val coverage = SimilarityCoverage(selected = 2, attempted = 2, featured = 2, admittedBytes = 8)
        val original = session.finish(ticket, selected, coverage)
        val final = session.finishWithIndex(original, query, listOf(first, second, match, stale))
        assertEquals(3, final.pairs.size)
        assertTrue(final.pairs.any { session.resolve(final, it) != null })
        assertEquals(1, final.indexCoverage?.indexedCurrentPhotos)
    }

    @Test fun staleSelectionOrPairOutsideCurrentScopeCannotEnterThePublishedResult() {
        val first = item(10); val second = item(11); val match = item(12)
        val selected = listOf(analyzed(first, 0), analyzed(second, 1))
        val query = searchSimilarityIndex(selected, listOf(first, second), listOf(first, second, match), FakeIndex(listOf(match to feature(1))), namespaces)
        val session = SimilaritySession()
        val ticket = session.begin(listOf(first, second)) {}
        val original = session.finish(ticket, selected, SimilarityCoverage(2, 2, 2, admittedBytes = 8))
        assertTrue(runCatching { session.finishWithIndex(original, query, listOf(second, match)) }.isFailure)
    }

    @Test fun retrievalBudgetReportsCandidateOmissionInsteadOfClaimingFullCoverage() {
        val first = item(20); val second = item(21); val match = item(22); val more = item(23)
        val selected = listOf(analyzed(first, 0), analyzed(second, 1))
        val query = searchSimilarityIndex(selected, listOf(first, second), listOf(first, second, match, more),
            FakeIndex(listOf(match to feature(1), more to feature(2)), maximum = 1), namespaces, maximumPairChecks = 1)
        assertTrue(query.coverage.omittedWork)
        assertEquals(1, query.coverage.pairChecks)
    }

    @Test fun indexedPhotoCoverageStopsAtThePairCheckBudgetInsideLoadedFeatureChunk() {
        val first = item(24); val second = item(25)
        val indexed = (26L..28L).map(::item)
        val selected = listOf(analyzed(first, 0), analyzed(second, 1))
        val query = searchSimilarityIndex(selected, listOf(first, second), listOf(first, second) + indexed,
            FakeIndex(indexed.map { it to feature(0) }), namespaces, maximumPairChecks = 1)

        assertEquals(1, query.coverage.pairChecks)
        assertEquals(1, query.coverage.indexedPhotosSearched)
        assertTrue(query.coverage.omittedWork)
    }

    @Test fun excludesSelectedPhotosFromTheIndexEvenWhenTheirOwnReadWasSkipped() {
        val readable = item(30); val skippedSelection = item(31); val other = item(32)
        val onlyReadable = listOf(analyzed(readable, 7))
        val query = searchSimilarityIndex(onlyReadable, listOf(readable, skippedSelection),
            listOf(readable, skippedSelection, other), FakeIndex(listOf(skippedSelection to feature(7), other to feature(7))), namespaces)
        assertTrue(query.additionalPairs.all { it.second.id != skippedSelection.id })
        assertEquals(listOf(other.id), query.additionalPairs.map { it.second.id })
    }

    @Test fun distinctIndexedSourcesSearchedAndSelectedToLibraryComparisonsAreSeparateCounts() {
        val first = item(40); val second = item(41)
        val indexed = (100..129).map { item(it.toLong()) }
        val selected = listOf(analyzed(first, 0), analyzed(second, 0))
        val index = FakeIndex(indexed.map { it to feature(0) })
        val query = searchSimilarityIndex(selected, listOf(first, second), listOf(first, second) + indexed, index, namespaces)

        assertEquals(30, query.coverage.indexedPhotosSearched)
        assertEquals(30, query.coverage.indexedCurrentPhotos)
        assertEquals(60, query.coverage.pairChecks)
    }

    @Test fun authorityWithdrawalAfterAnEmptyCandidateLookupFencesTheQueryReturn() {
        val first = item(50); val second = item(51)
        var authorized = true
        val index = FakeIndex(emptyList(), onCandidates = { authorized = false })

        val result = runCatching {
            searchSimilarityIndex(listOf(analyzed(first, 0)), listOf(first, second), listOf(first, second), index, namespaces, checkCurrent = {
                check(authorized) { "Authority was withdrawn during search" }
            })
        }

        assertTrue(result.isFailure)
    }
}
