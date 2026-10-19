package app.still.gallery.similarity

import app.still.gallery.media.*
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO
import org.junit.Assert.*
import org.junit.Test

class SimilarityPolicyTest {
    @Test fun selectionRejectsCountVideoUriAndPhysicalSourceAliases() {
        val one = cleanupTestItem(1); val two = cleanupTestItem(2)
        for (rows in listOf(emptyList(), listOf(one), List(201) { cleanupTestItem(it) }, listOf(one, two.copy(isVideo = true)),
            listOf(one, two.copy(id = one.id)), listOf(one, one.copy(id = "another-uri")))) {
            failure(SimilarityFailure.INVALID_SELECTION) { SimilaritySession().begin(rows) {} }
        }
    }
    @Test fun currentAndForeignIdenticalLookingResultsCannotRetargetCallbacks() {
        val first = SimilaritySession(); val second = SimilaritySession()
        val rows = listOf(cleanupTestItem(1), cleanupTestItem(2))
        val a = first.begin(rows) {}; val b = second.begin(rows) {}
        val result = first.finish(a, analyzed(rows), coverage(rows))
        assertEquals(rows, first.resolve(result, result.pairs.single()))
        assertNull(second.resolve(result, result.pairs.single()))
        failure(SimilarityFailure.STALE) { second.finish(a, analyzed(rows), coverage(rows)) }
        first.begin(rows) {}
        assertNull(first.resolve(result, result.pairs.single()))
        assertTrue(second.isCurrent(b))
    }
    @Test fun sameUriWithNewSourceGenerationCannotConsumePreviousEvidence() {
        val session = SimilaritySession()
        val rows = listOf(cleanupTestItem(1), cleanupTestItem(2))
        val ticket = session.begin(rows) {}
        val result = session.finish(ticket, analyzed(rows), coverage(rows))
        val changed = rows.map { it.copy(generationModified = 99) }
        val replacement = session.begin(changed) {}
        assertNull(session.resolve(result, result.pairs.single()))
        assertTrue(runCatching { session.finish(replacement, analyzed(rows), coverage(rows)) }.isFailure)
    }
    @Test fun inputAndResultListsAreDefensivelyCopiedAndLiveWithdrawalIsPermanent() {
        val session = SimilaritySession(); var allowed = true
        val rows = mutableListOf(cleanupTestItem(1), cleanupTestItem(2))
        val ticket = session.begin(rows) { check(allowed) }
        val expected = rows.toList(); rows.clear()
        val result = session.finish(ticket, analyzed(expected), coverage(expected))
        assertEquals(expected, session.resolve(result, result.pairs.single()))
        assertTrue(runCatching { (result.pairs as MutableList).clear() }.isFailure)
        allowed = false; assertFalse(session.isCurrent(ticket))
        allowed = true; assertFalse(session.isCurrent(ticket)); assertNull(session.resolve(result, result.pairs.single()))
    }
    @Test fun directPairsNeverBecomeTransitiveGroupsAndDatesDoNotConstrainRetrieval() {
        val rows = listOf(cleanupTestItem(1).copy(capturedAt = 1), cleanupTestItem(2).copy(capturedAt = 1_900_000_000_000), cleanupTestItem(3))
        val hashes = listOf(0L, ((1L shl 10) - 1) shl 1, ((1L shl 20) - 1) shl 1)
        val session = SimilaritySession(); val ticket = session.begin(rows) {}
        val sources = rows.mapIndexed { index, row -> SimilarityAnalyzedSource(row, feature(hashes[index]), sha(index), 3) }
        val result = session.finish(ticket, sources, coverage(rows))
        assertEquals(setOf(rows[0] to rows[1], rows[1] to rows[2]), result.pairs.map { it.first to it.second }.toSet())
        assertTrue(result.reviewOnly && result.pairs.all { it.reviewOnly })
    }
    @Test fun fullReadableDigestAndLengthBothRequiredForExactPairOmission() {
        val rows = List(3) { cleanupTestItem(it + 1) }
        val sources = listOf(SimilarityAnalyzedSource(rows[0], feature(), sha(1), 3), SimilarityAnalyzedSource(rows[1], feature(), sha(1), 3), SimilarityAnalyzedSource(rows[2], feature(), sha(1), 4))
        val session = SimilaritySession(); val ticket = session.begin(rows) {}
        val result = session.finish(ticket, sources, coverage(rows).copy(admittedBytes = 10))
        assertEquals(1, result.coverage.exactPairsOmitted)
        assertEquals(2, result.pairs.size)
        assertEquals(3, result.coverage.pairChecks)
    }
    @Test fun twoHundredSourcesHaveBoundedPairChecksAndExplicitResultOverflow() {
        val rows = List(200) { cleanupTestItem(it + 1) }
        val session = SimilaritySession(); val ticket = session.begin(rows) {}
        val result = session.finish(ticket, analyzed(rows), coverage(rows))
        assertEquals(19_900, result.coverage.pairChecks)
        assertEquals(50, result.pairs.size)
        assertEquals(19_850, result.coverage.resultPairsOmitted)
    }
    @Test fun forgedCoverageAndSourceEvidenceAreRejected() {
        val rows = listOf(cleanupTestItem(1), cleanupTestItem(2))
        val bad = listOf(coverage(rows).copy(attempted = 0), coverage(rows).copy(admittedBytes = 0), coverage(rows).copy(featured = 1),
            coverage(rows).copy(unsupported = 1), coverage(rows).copy(admittedBytes = SimilarityBounds.MAX_RUN_BYTES, quotaProbeBytes = 1))
        for (value in bad) {
            val session = SimilaritySession(); val ticket = session.begin(rows) {}
            assertTrue(runCatching { session.finish(ticket, analyzed(rows), value) }.isFailure)
        }
        val session = SimilaritySession(); val ticket = session.begin(rows) {}
        assertTrue(runCatching { session.finish(ticket, analyzed(rows).map { SimilarityAnalyzedSource(it.item, it.feature, "caller-value", 3) }, coverage(rows)) }.isFailure)
    }
    @Test fun actualDimensionsAndAllocationMustFitWithoutUpscalingTinySources() {
        assertTrue(runCatching { similaritySampleSize(63, 256) }.isFailure)
        assertTrue(runCatching { similaritySampleSize(10_000, 64) }.isFailure)
        assertEquals(16, similaritySampleSize(4000, 3000))
        assertFalse(similarityBitmapFits(31, 128, 31L * 128 * 4))
        assertFalse(similarityBitmapFits(257, 128, 257L * 128 * 4))
        assertFalse(similarityBitmapFits(128, 128, SimilarityBounds.MAX_ALLOCATION_BYTES + 1))
        assertTrue(similarityBitmapFits(128, 128, 128L * 128 * 4))
        assertEquals(SimilarityPixelSkip.TINY, (similarityFeature(16, 128, IntArray(16 * 128) { -1 }) as SimilarityPixelResult.Rejected).reason)
        assertTrue(SimilarityBounds.MAX_INPUT_BYTES <= SimilarityBounds.MAX_ENCODED_BUFFERS)
    }
    @Test fun transparentFlatAndInvalidPlanesNeverBecomeConfidentDescriptors() {
        assertEquals(SimilarityPixelSkip.TRANSPARENT, (similarityFeature(64, 64, IntArray(64 * 64)) as SimilarityPixelResult.Rejected).reason)
        assertEquals(SimilarityPixelSkip.INVALID, (similarityFeature(64, 64, IntArray(2)) as SimilarityPixelResult.Rejected).reason)
        assertEquals(SimilarityPixelSkip.LOW_INFORMATION, (similarityFeature(64, 64, IntArray(64 * 64) { -1 }) as SimilarityPixelResult.Rejected).reason)
    }
    @Test fun validStaticJpegAndPngPassButTruncationMpfMotionAndAnimationAreOmitted() {
        val image = BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB)
        image.setRGB(20, 20, 0xff773344.toInt())
        val jpeg = ByteArrayOutputStream().also { assertTrue(ImageIO.write(image, "jpeg", it)) }.toByteArray()
        assertTrue(similarityContainerSupported(jpeg, jpeg.size))
        assertFalse(similarityContainerSupported(jpeg, jpeg.size - 2))
        val mpf = jpeg.take(2).toByteArray() + byteArrayOf(0xff.toByte(), 0xe2.toByte(), 0, 6, 77, 80, 70, 0) + jpeg.drop(2).toByteArray()
        assertNotNull(ImageIO.read(java.io.ByteArrayInputStream(mpf)))
        assertFalse(similarityContainerSupported(mpf, mpf.size))
        val text = "GCamera:MotionPhoto=1".toByteArray()
        val motion = jpeg.take(2).toByteArray() + byteArrayOf(0xff.toByte(), 0xe1.toByte(), 0, (text.size + 2).toByte()) + text + jpeg.drop(2).toByteArray()
        assertFalse(similarityContainerSupported(motion, motion.size))
        val trailing = jpeg + jpeg
        assertFalse(similarityContainerSupported(trailing, trailing.size))
        val png = ByteArrayOutputStream().also { assertTrue(ImageIO.write(image, "png", it)) }.toByteArray()
        assertTrue(similarityContainerSupported(png, png.size))
        assertFalse(similarityContainerSupported(png, png.size - 12))
        val ac = "acTL".toByteArray() + byteArrayOf(0, 0, 0, 2, 0, 0, 0, 0)
        val crc = java.util.zip.CRC32().apply { update(ac) }.value
        val chunk = byteArrayOf(0, 0, 0, 8) + ac + byteArrayOf((crc ushr 24).toByte(), (crc ushr 16).toByte(), (crc ushr 8).toByte(), crc.toByte())
        val animated = png.take(33).toByteArray() + chunk + png.drop(33).toByteArray()
        assertFalse(similarityContainerSupported(animated, animated.size))
        assertFalse(similarityContainerSupported(byteArrayOf(1, 2), 2))
    }
    @Test fun mainThreadWithdrawalDoesNotWaitForRunningPairFinalization() {
        val session = SimilaritySession()
        val rows = List(200) { cleanupTestItem(it + 1) }
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val invalidated = CountDownLatch(1)
        var workerChecks = 0
        val ticket = session.begin(rows) {
            if (Thread.currentThread().name == "similarity-finalizer" && ++workerChecks == 2) {
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
            }
        }
        val outcome = AtomicReference<Throwable?>()
        val worker = Thread({ try { session.finish(ticket, analyzed(rows), coverage(rows)) } catch (failure: Throwable) { outcome.set(failure) } }, "similarity-finalizer")
        worker.start()
        var invalidator: Thread? = null
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            invalidator = Thread { session.invalidate(); invalidated.countDown() }.also { it.start() }
            assertTrue("Withdrawal must not wait for the blocked finalizer's authority monitor", invalidated.await(2, TimeUnit.SECONDS))
            assertFalse(session.isCurrent(ticket))
        } finally { release.countDown(); worker.join(5000); invalidator?.join(5000) }
        assertFalse(worker.isAlive)
        assertTrue(outcome.get() is SimilarityException)
        assertEquals(SimilarityFailure.STALE, (outcome.get() as SimilarityException).reason)
    }
    companion object {
        internal fun feature(hash: Long = 0) = SimilarityFeature(hash, 1.0, DoubleArray(48) { 0.5 }, DoubleArray(12) { 0.25 })
        internal fun sha(index: Int) = "%064x".format(index + 1)
        internal fun analyzed(rows: List<MediaItem>) = rows.mapIndexed { index, row -> SimilarityAnalyzedSource(row, feature(), sha(index), 3) }
        internal fun coverage(rows: List<MediaItem>) = SimilarityCoverage(rows.size, attempted = rows.size, featured = rows.size, admittedBytes = rows.size * 3L)
    }
    private fun failure(reason: SimilarityFailure, block: () -> Any?) {
        val value = runCatching(block).exceptionOrNull(); assertTrue(value is SimilarityException); assertEquals(reason, (value as SimilarityException).reason)
    }
}
