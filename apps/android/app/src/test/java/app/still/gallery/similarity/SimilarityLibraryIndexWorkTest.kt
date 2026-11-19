package app.still.gallery.similarity

import app.still.gallery.catalogue.CatalogueNamespace
import app.still.gallery.media.*
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SimilarityLibraryIndexWorkTest {
    private val incarnation = "a".repeat(64)
    private val namespace = CatalogueNamespace("media", "external", "provider-v1", 30)
    private val access = MediaAccess(sdk = 30, legacy = true)
    private val feature = SimilarityFeature(7, 1.0, DoubleArray(48) { 0.5 }, DoubleArray(12) { 0.25 })

    private fun item(id: Int) = MediaItem(
        id = "content://media/external/images/media/$id", name = "photo-$id.jpg", mimeType = "image/jpeg", isVideo = false,
        capturedAt = id.toLong(), width = 128, height = 128, bytes = 32, durationMs = 0, albumId = "camera", albumName = "Camera",
        modifiedAt = id.toLong(), providerAuthority = "media", volumeName = "external", providerVersion = "provider-v1",
        providerGeneration = 30, externalHandle = id.toString(), generationAdded = id.toLong(), generationModified = id.toLong(),
        dateAddedSeconds = id.toLong(),
    )

    private class FakeReader(
        private val items: List<MediaItem>,
        private val failValidation: Int? = null,
    ) : CleanupSourceReader {
        var validations = 0
        var opens = 0
        override fun checkAccess() = Unit
        override suspend fun snapshot(favorites: Set<String>) = CleanupSourceSnapshot(items, listOf(
            CatalogueNamespace("media", "external", "provider-v1", 30),
        ), emptyMap(), MediaAccess(sdk = 30, legacy = true))
        override suspend fun probe(item: MediaItem) = item
        override suspend fun validateScope(snapshot: CleanupSourceSnapshot) {
            validations++
            if (validations == failValidation) throw CleanupSourceChangedException()
        }
        override fun open(item: MediaItem): InputStream { opens++; return ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)) }
    }

    private inner class FakeRepository : SimilarityIndexRepository {
        private val values = linkedMapOf<String, SimilarityIndexEntry>()
        val committedPages = mutableListOf<Int>()
        override fun revisions() = values.mapValues { it.value.identity.sourceRevision }
        override fun candidates(feature: SimilarityFeature, maximum: Int) = SimilarityIndexCandidates(emptyList(), false)
        override fun features(sourceRevisions: Map<String, String>, sourceKeys: List<String>) = emptyMap<String, SimilarityFeature>()
        override fun reconcileNamespaces(namespaces: List<SimilarityIndexNamespace>, checkCurrent: () -> Unit): Map<String, String> =
            namespaces.associate { it.namespaceKey to incarnation }
        override fun retainCurrent(identities: Map<String, String>, checkCurrent: () -> Unit) {
            checkCurrent()
            values.keys.retainAll(identities.keys)
        }
        override fun upsertPage(entries: List<SimilarityIndexEntry>, checkCurrent: () -> Unit) {
            val page = entries.toList()
            page.forEach { checkCurrent() }
            values.putAll(page.associateBy { it.identity.sourceKey })
            committedPages += page.size
        }
    }

    private val decoder = SimilarityFeatureDecoder { _, length, checkCurrent ->
        checkCurrent()
        assertTrue(length > 0)
        SimilarityPixelResult.Accepted(feature)
    }

    @Test fun explicitRunCommitsBoundedCompletePagesAndRetainsDescriptorsAcrossTheNextSnapshot() = runBlocking {
        val items = (1..201).map(::item)
        val repository = FakeRepository()
        val reader = FakeReader(items)
        val work = SimilarityLibraryIndexWork(reader, repository, emptySet(), {}, decoder)
        val progress = mutableListOf<SimilarityIndexProgress>()
        val complete = work.run(onSnapshot = {}, onProgress = { progress += it })

        assertEquals(listOf(200, 1), repository.committedPages)
        assertEquals(201, repository.revisions().size)
        assertEquals(201, reader.opens)
        assertEquals(201, complete.processed)
        assertEquals(0, complete.unattempted)
        assertTrue(complete.complete)
        assertEquals(201, progress.last().indexed)

        val next = SimilarityLibraryIndexWork(FakeReader(items), repository, emptySet(), {}, decoder)
            .run(onSnapshot = {}, onProgress = {})
        assertTrue(next.complete)
        assertEquals(201, next.alreadyIndexed)
        assertTrue("The unchanged snapshot should require no rereads", repository.committedPages == listOf(200, 1))
    }

    @Test fun namespaceRevisionChangePrunesOldDescriptorBeforeAReplacementPageIsWritten() = runBlocking {
        val old = item(1)
        val repository = FakeRepository()
        val first = SimilarityLibraryIndexWork(FakeReader(listOf(old)), repository, emptySet(), {}, decoder)
        first.run(onSnapshot = {}, onProgress = {})
        val replacement = old.copy(generationModified = 31, modifiedAt = 31)
        val second = SimilarityLibraryIndexWork(FakeReader(listOf(replacement)), repository, emptySet(), {}, decoder)
        val result = second.run(onSnapshot = {}, onProgress = {})

        assertEquals(1, repository.revisions().size)
        assertNotEquals(similarityIndexIdentity(old, incarnation)!!.sourceRevision,
            repository.revisions().values.single())
        assertEquals(1, result.indexed)
        assertEquals(listOf(1, 1), repository.committedPages)
    }

    @Test fun missingStableSourceIdentityIsReportedAndPreventsAFullCompletionClaim() = runBlocking {
        val unidentified = item(2).copy(generationAdded = null, dateAddedSeconds = 0)
        val repository = FakeRepository()
        val result = SimilarityLibraryIndexWork(FakeReader(listOf(item(1), unidentified)), repository,
            emptySet(), {}, decoder).run(onSnapshot = {}, onProgress = {})

        assertEquals(2, result.currentPhotos)
        assertEquals(1, result.indexablePhotos)
        assertEquals(1, result.identityUnavailable)
        assertEquals(1, result.indexed)
        assertEquals(1, result.processed)
        assertEquals(0, result.unattempted)
        assertFalse(result.complete)
    }

    @Test fun providerScopeChangeBeforePageCommitLeavesThatPageUnindexed() = runBlocking {
        val repository = FakeRepository()
        val reader = FakeReader(listOf(item(1)), failValidation = 5)
        val work = SimilarityLibraryIndexWork(reader, repository, emptySet(), {}, decoder)

        val failure = runCatching { work.run(onSnapshot = {}, onProgress = {}) }.exceptionOrNull()
        assertTrue(failure is SimilarityException)
        assertEquals(SimilarityFailure.SOURCE_CHANGED, (failure as SimilarityException).reason)
        assertTrue(repository.committedPages.isEmpty())
        assertTrue(repository.revisions().isEmpty())
    }
}
