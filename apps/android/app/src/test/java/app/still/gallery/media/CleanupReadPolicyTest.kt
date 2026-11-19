package app.still.gallery.media

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class CleanupReadPolicyTest {
    @Test fun completeShortChunksUseActualLengthAndSuccessfulClose() {
        var closed = false
        val stream = object : ByteArrayInputStream("abc".toByteArray()) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = super.read(buffer, offset, minOf(1, length))
            override fun close() { closed = true; super.close() }
        }
        val result = readCleanupBytes({ stream }, {})
        assertEquals(3L, result.byteCount)
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", result.sha256)
        assertTrue(closed)
    }

    @Test fun emptyAndPartialErrorCannotProduceEvidence() {
        assertThrows(CleanupEmptyReadException::class.java) { readCleanupBytes({ ByteArrayInputStream(byteArrayOf()) }, {}) }
        var closed = false
        val broken = object : InputStream() {
            var first = true
            override fun read(): Int = throw IOException("synthetic")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (!first) throw IOException("synthetic")
                first = false; buffer[0] = 1; return 1
            }
            override fun close() { closed = true }
        }
        assertThrows(IOException::class.java) { readCleanupBytes({ broken }, {}) }
        assertTrue(closed)
    }

    @Test fun closeFailureAndCancellationAfterReadRejectDigest() {
        val closeFailure = object : ByteArrayInputStream(byteArrayOf(1)) { override fun close() { throw IOException("synthetic close") } }
        assertThrows(IOException::class.java) { readCleanupBytes({ closeFailure }, {}) }
        var cancel = false; var closed = false
        val stream = object : ByteArrayInputStream(byteArrayOf(1, 2)) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int { cancel = true; return super.read(buffer, offset, length) }
            override fun close() { closed = true }
        }
        assertThrows(CancellationException::class.java) { readCleanupBytes({ stream }, { if (cancel) throw CancellationException() }) }
        assertTrue(closed)
    }

    @Test fun limitsOverflowAndNonprogressNeverYieldPrefixEvidence() {
        assertThrows(CleanupReadLimitException::class.java) { readCleanupBytes({ ByteArrayInputStream(byteArrayOf(1, 2, 3)) }, {}, 2) }
        assertThrows(CleanupReadLimitException::class.java) { cleanupCountBytes(Long.MAX_VALUE - 1, 2, Long.MAX_VALUE) }
        assertEquals(Long.MAX_VALUE, cleanupCountBytes(Long.MAX_VALUE - 1, 1, Long.MAX_VALUE))
        val stalled = object : InputStream() { override fun read() = 0; override fun read(buffer: ByteArray, offset: Int, length: Int) = 0 }
        assertThrows(IOException::class.java) { readCleanupBytes({ stalled }, {}) }
    }

    @Test fun streamBufferIsBoundedAndCancellationBeforeOpenDoesNotRead() {
        var maxRequested = 0
        val stream = object : ByteArrayInputStream(ByteArray(CleanupBounds.BUFFER_BYTES * 2 + 17) { 1 }) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int { maxRequested = maxOf(maxRequested, length); return super.read(buffer, offset, length) }
        }
        assertEquals((CleanupBounds.BUFFER_BYTES * 2 + 17).toLong(), readCleanupBytes({ stream }, {}).byteCount)
        assertEquals(CleanupBounds.BUFFER_BYTES, maxRequested)
        var opened = false
        assertThrows(CancellationException::class.java) { readCleanupBytes({ opened = true; stream }, { throw CancellationException() }) }
        assertFalse(opened)
    }

    @Test fun sourcesDeduplicateButConflictingReuseIsRejected() {
        val a = cleanupTestItem(1)
        assertEquals(listOf(a) to 1, cleanupUniqueSources(listOf(a, a)))
        assertThrows(CleanupSourceChangedException::class.java) { cleanupUniqueSources(listOf(a, a.copy(modifiedAt = 99))) }
        val otherVolume = a.copy(id = "content://media/sd/images/media/1", volumeName = "sd")
        assertEquals(2, cleanupUniqueSources(listOf(a, otherVolume)).first.size)
    }

    @Test fun prefilterCoverageDoesNotPretendReportedSizeIsActualEquality() {
        val a = cleanupTestItem(1)
        val b = cleanupTestItem(2)
        val video = cleanupTestItem(3).copy(isVideo = true)
        assertEquals(listOf(a, b), cleanupCandidates(listOf(a, b, video, cleanupTestItem(4).copy(bytes = 0))))
        assertNotEquals(readCleanupBytes({ ByteArrayInputStream("abc".toByteArray()) }, {}), readCleanupBytes({ ByteArrayInputStream("abcd".toByteArray()) }, {}))
    }

    @Test fun modernGenerationAdvanceAndMissingEvidenceFailContinuity() {
        assertFalse(cleanupNamespaceUnchanged("v", 5, "v", 6))
        assertFalse(cleanupNamespaceUnchanged("v", 5, "v", 4))
        assertFalse(cleanupNamespaceUnchanged("v", 5, "reset", 5))
        assertTrue(cleanupNamespaceUnchanged(null, null, null, null))
        val a = cleanupTestItem(1)
        assertFalse(cleanupSameSource(a.copy(generationAdded = null), a.copy(generationAdded = null), true))
        assertTrue(cleanupSameSource(a, a, true))
    }

    @Test fun unresolvedPossibleLegacyFavoriteIsUnknownAcrossCollidingVolumes() {
        val old = "content://media/external/images/media/1"
        val a = cleanupTestItem(1)
        assertTrue(cleanupFavoriteUnknown(a, setOf(old), emptyMap()))
        assertTrue(cleanupFavoriteUnknown(a.copy(volumeName = "sd"), setOf(old), emptyMap()))
        assertFalse(cleanupFavoriteUnknown(a, setOf(old), mapOf(old to a.id)))
        assertFalse(cleanupFavoriteUnknown(a, setOf("content://media/external/video/media/1"), emptyMap()))
    }
}

internal fun cleanupTestItem(id: Int) = MediaItem(
    id = "content://media/external_primary/images/media/$id", name = "synthetic$id.jpg", mimeType = "image/jpeg", isVideo = false,
    capturedAt = 100, width = 1, height = 1, bytes = 3, durationMs = 0, albumId = "a", albumName = "Synthetic",
    providerAuthority = "media", volumeName = "external_primary", providerVersion = "v", providerGeneration = 5,
    externalHandle = id.toString(), generationAdded = 1, generationModified = 2, dateAddedSeconds = 1,
)
