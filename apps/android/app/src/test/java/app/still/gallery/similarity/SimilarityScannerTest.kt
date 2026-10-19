package app.still.gallery.similarity

import app.still.gallery.catalogue.CatalogueNamespace
import app.still.gallery.media.*
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class SimilarityScannerTest {
    @Test fun fullEofAndSuccessfulClosePrecedeDecodeAndCapturedBytesIgnoreObservedSize() = runBlocking {
        val fixture = Fixture()
        var buffer: ByteArray? = null
        fixture.reader.stream = { row -> object : ByteArrayInputStream((row.externalHandle + "readable").toByteArray()) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                assertTrue(length <= SimilarityBounds.BUFFER_BYTES)
                if (buffer == null) buffer = bytes else assertSame(buffer, bytes)
                return super.read(bytes, offset, length)
            }
        } }
        fixture.decode = { bytes, length, check ->
            assertSame(buffer, bytes)
            assertEquals(SimilarityBounds.MAX_INPUT_BYTES, bytes.size)
            assertTrue(bytes.size <= SimilarityBounds.MAX_ENCODED_BUFFERS)
            assertEquals(9, length)
            assertEquals(fixture.decoderCalls, fixture.reader.closed)
            check(); SimilarityPixelResult.Accepted(SimilarityPolicyTest.feature())
        }
        val result = fixture.scan()
        assertEquals(2, result.coverage.featured)
        assertEquals(18L, result.coverage.admittedBytes)
        assertEquals(1, result.pairs.size)
        assertEquals(0, result.coverage.exactPairsOmitted)
        assertEquals(3, fixture.reader.validations)
        assertTrue(fixture.reader.probes.values.all { it == 3 })
        fixture.assertFree()
    }
    @Test fun equalCompleteReadableBytesAreOmittedSeparatelyFromAppearanceCandidates() = runBlocking {
        val fixture = Fixture()
        fixture.reader.stream = { ByteArrayInputStream(byteArrayOf(3, 4, 5)) }
        val result = fixture.scan()
        assertTrue(result.pairs.isEmpty())
        assertEquals(1, result.coverage.exactPairsOmitted)
        assertEquals(2, result.coverage.featured)
    }
    @Test fun emptyUnreadableAndUnsupportedInputsHaveHonestPartialCoverage() = runBlocking {
        val fixture = Fixture(4)
        fixture.reader.rows[2] = fixture.reader.rows[2].copy(mimeType = "image/gif")
        fixture.ticket = fixture.scanner.begin(fixture.reader.rows) {}
        fixture.reader.stream = { row -> when (row.externalHandle) {
            "1" -> ByteArrayInputStream(byteArrayOf())
            "2" -> throw IOException()
            else -> ByteArrayInputStream(byteArrayOf(1))
        } }
        val result = fixture.scan()
        assertEquals(1, result.coverage.empty); assertEquals(1, result.coverage.unreadable)
        assertEquals(1, result.coverage.unsupported); assertEquals(1, result.coverage.featured)
        assertEquals(3, result.coverage.attempted)
        assertEquals(3, fixture.reader.probes.getValue("3"))
    }
    @Test fun scopeCapAndAmbiguousOrMissingSelectionStopBeforeProviderOpen() = runBlocking {
        for (mode in listOf("cap", "alias", "missing")) {
            val fixture = Fixture()
            when (mode) {
                "cap" -> fixture.reader.snapshotRows = List(CleanupBounds.MAX_SOURCES + 1) { fixture.reader.rows[0] }
                "alias" -> fixture.reader.snapshotRows = fixture.reader.rows + fixture.reader.rows[0].copy(modifiedAt = 999)
                else -> fixture.reader.snapshotRows = fixture.reader.rows.drop(1)
            }
            failure(if (mode == "cap") SimilarityFailure.SCOPE_LIMIT else SimilarityFailure.SOURCE_CHANGED) { fixture.scan() }
            assertEquals(0, fixture.reader.opened)
            fixture.assertFree()
        }
    }
    @Test fun earlyFeaturedSourceChangeDuringLastReadInvalidatesWholeResult() = runBlocking {
        val fixture = Fixture()
        fixture.reader.stream = { row ->
            if (row.externalHandle == "2") fixture.reader.rows[0] = fixture.reader.rows[0].copy(modifiedAt = 77)
            ByteArrayInputStream(row.externalHandle.toByteArray())
        }
        failure(SimilarityFailure.SOURCE_CHANGED) { fixture.scan() }
        assertFalse(fixture.scanner.isCurrent(fixture.ticket))
        fixture.assertFree()
    }
    @Test fun finalFenceAlsoCoversFailedAndNeverOpenedSelectedSources() = runBlocking {
        for (mode in listOf("failed", "unsupported")) {
            val fixture = Fixture()
            if (mode == "unsupported") fixture.reader.rows[0] = fixture.reader.rows[0].copy(mimeType = "image/gif")
            fixture.ticket = fixture.scanner.begin(fixture.reader.rows) {}
            fixture.reader.stream = { row ->
                if (row.externalHandle == "1") throw IOException()
                fixture.reader.rows[0] = fixture.reader.rows[0].copy(modifiedAt = 88)
                ByteArrayInputStream(byteArrayOf(2))
            }
            failure(SimilarityFailure.SOURCE_CHANGED) { fixture.scan() }
            fixture.assertFree()
        }
    }
    @Test fun finalNamespaceAdvanceRejectsEvenUnchangedSelectedRows() = runBlocking {
        val fixture = Fixture()
        fixture.reader.validation = { if (fixture.reader.validations == 3) throw CleanupSourceChangedException() }
        failure(SimilarityFailure.SOURCE_CHANGED) { fixture.scan() }
    }
    @Test fun quotaProbeIsReportedAndChargedBeforeAnotherInputCanUseBudget() = runBlocking {
        val fixture = Fixture()
        fixture.reader.stream = { row -> if (row.externalHandle == "1") Repeated(SimilarityBounds.MAX_INPUT_BYTES.toLong() + 1) else ByteArrayInputStream(byteArrayOf(2)) }
        val result = fixture.scan()
        assertEquals(1, result.coverage.oversized)
        assertEquals(1, result.coverage.featured)
        assertEquals(SimilarityBounds.MAX_INPUT_BYTES + 1L, result.coverage.admittedBytes)
        assertEquals(1, result.coverage.quotaProbeBytes)
        assertEquals(SimilarityBounds.MAX_INPUT_BYTES + 2L, result.coverage.admittedBytes + result.coverage.quotaProbeBytes)
        assertEquals(1, fixture.decoderCalls)
    }
    @Test fun runExhaustionCannotReadAnUnbudgetedEofByteOrClaimCompleteNoMatch() = runBlocking {
        val fixture = Fixture(17)
        var returned = 0L
        fixture.reader.stream = { object : Repeated(SimilarityBounds.MAX_INPUT_BYTES.toLong()) {
            override fun read(b: ByteArray, o: Int, l: Int): Int { val count = super.read(b, o, l); if (count > 0) returned += count; return count }
            override fun read(): Int { val value = super.read(); if (value >= 0) returned++; return value }
        } }
        val result = fixture.scan()
        assertEquals(SimilarityBounds.MAX_RUN_BYTES, returned)
        assertEquals(returned, result.coverage.admittedBytes)
        assertEquals(0, result.coverage.quotaProbeBytes)
        assertEquals(16, result.coverage.attempted)
        assertEquals(15, result.coverage.featured)
        assertEquals(2, result.coverage.budgetOmitted)
        assertTrue(fixture.reader.probes.values.all { it == 3 })
        assertTrue(result.coverage.budgetOmitted > 0)
    }
    @Test fun overLimitProbeAlsoConsumesTheLastRunBytes() = runBlocking {
        val fixture = Fixture(17)
        fixture.reader.stream = { Repeated(SimilarityBounds.MAX_INPUT_BYTES.toLong() + 1) }
        val result = fixture.scan()
        assertEquals(15, result.coverage.oversized)
        assertEquals(15, result.coverage.quotaProbeBytes)
        assertEquals(SimilarityBounds.MAX_RUN_BYTES - 15, result.coverage.admittedBytes)
        assertEquals(2, result.coverage.budgetOmitted)
        assertEquals(0, fixture.decoderCalls)
    }
    @Test fun stalledReadsAndDecoderRejectsDoNotBecomeAppearanceFeatures() = runBlocking {
        val fixture = Fixture()
        fixture.reader.stream = { row -> if (row.externalHandle == "1") object : InputStream() {
            override fun read(): Int = 0
            override fun read(b: ByteArray, o: Int, l: Int): Int = 0
        } else ByteArrayInputStream(byteArrayOf(2)) }
        fixture.decode = { _, _, _ -> SimilarityPixelResult.Rejected(SimilarityPixelSkip.LOW_INFORMATION) }
        val result = fixture.scan()
        assertEquals(1, result.coverage.unreadable); assertEquals(1, result.coverage.lowInformation)
        assertEquals(0, result.coverage.featured)
        assertTrue(result.pairs.isEmpty())
    }
    @Test fun failedCloseRetainsSharedLaneAcrossRecreatedScannerUntilVerifiedRetry() = runBlocking {
        val fixture = Fixture()
        var fail = true
        fixture.reader.close = { if (fail) throw IOException() }
        failure(SimilarityFailure.CLOSE_FAILED) { fixture.scan() }
        assertNull(fixture.lane.tryEnter())
        val replacement = fixture.replacement()
        assertEquals(SimilaritySettleResult.CLOSE_FAILED, replacement.settle())
        val next = replacement.begin(fixture.reader.rows) {}
        failure(SimilarityFailure.BUSY) { replacement.scan(next, emptySet()) }
        assertFalse(fixture.scanner.isCurrent(fixture.ticket))
        fail = false
        assertEquals(SimilaritySettleResult.CLEAN, replacement.settle())
        fixture.assertFree()
        assertEquals(0, fixture.decoderCalls)
    }
    @Test fun closeFailureSuppressedUnderWithdrawalStillRetainsActualHandleAndLane() = runBlocking {
        val fixture = Fixture(); var allowed = true; var fail = true
        fixture.ticket = fixture.scanner.begin(fixture.reader.rows) { check(allowed) }
        fixture.reader.close = { if (fail) throw IOException() }
        fixture.reader.stream = { object : ByteArrayInputStream(byteArrayOf(1)) {
            override fun read(b: ByteArray, o: Int, l: Int): Int { allowed = false; return super.read(b, o, l) }
        } }
        failure(SimilarityFailure.CLOSE_FAILED) { fixture.scan() }
        assertNull(fixture.lane.tryEnter())
        fail = false
        assertEquals(SimilaritySettleResult.CLEAN, fixture.replacement().settle())
        assertFalse(fixture.scanner.isCurrent(fixture.ticket))
        assertEquals(0, fixture.decoderCalls)
        fixture.assertFree()
    }
    @Test fun blockedProviderReadAndCloseCannotBeSweptOrReplacedAfterCancellation() = runBlocking {
        for (atClose in listOf(false, true)) {
            val fixture = Fixture()
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            val block = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            if (atClose) fixture.reader.close = block else fixture.reader.stream = { object : ByteArrayInputStream(byteArrayOf(1)) {
                override fun read(b: ByteArray, o: Int, l: Int): Int { block(); return super.read(b, o, l) }
            } }
            val running = async { fixture.scan() }
            try {
                assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
                fixture.scanner.invalidate(fixture.ticket); running.cancel()
                assertEquals(SimilaritySettleResult.BUSY, fixture.replacement().settle())
                assertNull(fixture.lane.tryEnter())
                assertFalse(running.isCompleted)
            } finally { release.countDown(); running.cancelAndJoin() }
            fixture.assertFree()
        }
    }
    @Test fun blockedDecoderRetainsLaneUntilItsBitmapWorkActuallyReturns() = runBlocking {
        val fixture = Fixture()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        fixture.decode = { _, _, check -> entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); check(); SimilarityPixelResult.Accepted(SimilarityPolicyTest.feature()) }
        val running = async { fixture.scan() }
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            fixture.scanner.invalidate(fixture.ticket); running.cancel()
            assertEquals(SimilaritySettleResult.BUSY, fixture.replacement().settle())
            assertNull(fixture.lane.tryEnter())
        } finally { release.countDown(); running.cancelAndJoin() }
        fixture.assertFree()
    }
    @Test fun cancellationBeforeIoBodyAndRetryCannotPublishAStalePair() = runBlocking {
        val fixture = Fixture()
        fixture.scanner.invalidate(fixture.ticket)
        failure(SimilarityFailure.STALE) { fixture.scan() }
        assertEquals(0, fixture.reader.opened)
        assertEquals(SimilaritySettleResult.CLEAN, fixture.scanner.settle())
        fixture.assertFree()
    }
    @Test fun productionDefaultAdmissionIsTheSameLaneAsCleanupAndCompare() = runBlocking {
        val fixture = Fixture()
        val scanner = SimilarityScanner(fixture.reader, SimilarityFeatureDecoder { _, _, _ -> SimilarityPixelResult.Accepted(SimilarityPolicyTest.feature()) }, SimilarityReadOwnership())
        val ticket = scanner.begin(fixture.reader.rows) {}
        val other = cleanupProcessReads.tryEnter()
        assertNotNull(other)
        try {
            failure(SimilarityFailure.BUSY) { scanner.scan(ticket, emptySet()) }
            assertEquals(0, fixture.reader.opened)
        } finally { other!!.close() }
        assertEquals(2, scanner.scan(ticket, emptySet()).coverage.featured)
    }
    private class Reader(count: Int) : CleanupSourceReader {
        val rows = MutableList(count) { cleanupTestItem(it + 1) }
        var snapshotRows: List<MediaItem>? = null
        var stream: (MediaItem) -> InputStream = { ByteArrayInputStream(it.externalHandle.toByteArray()) }
        var close: () -> Unit = {}
        var validation: () -> Unit = {}
        val probes = mutableMapOf<String, Int>()
        var opened = 0; var closed = 0; var validations = 0
        override fun checkAccess() {}
        override suspend fun snapshot(favorites: Set<String>) = CleanupSourceSnapshot(snapshotRows ?: rows.toList(), listOf(CatalogueNamespace("media", "external_primary", "v", 5)), emptyMap(), MediaAccess(30, legacy = true))
        override suspend fun probe(item: MediaItem): MediaItem? { probes[item.externalHandle] = probes.getOrDefault(item.externalHandle, 0) + 1; return rows.singleOrNull { it.id == item.id } }
        override suspend fun validateScope(snapshot: CleanupSourceSnapshot) { validations++; validation() }
        override fun open(item: MediaItem): InputStream {
            opened++
            val input = stream(item)
            return object : java.io.FilterInputStream(input) { override fun close() { close.invoke(); super.close(); closed++ } }
        }
    }
    private class Fixture(count: Int = 2) {
        val reader = Reader(count); val owner = SimilarityReadOwnership(); val lane = CleanupReadAdmission()
        var decoderCalls = 0
        var decode: (ByteArray, Int, () -> Unit) -> SimilarityPixelResult = { _, _, check -> check(); SimilarityPixelResult.Accepted(SimilarityPolicyTest.feature()) }
        val scanner = SimilarityScanner(reader, SimilarityFeatureDecoder { bytes, length, check -> decoderCalls++; decode(bytes, length, check) }, owner, lane)
        var ticket = scanner.begin(reader.rows) {}
        suspend fun scan() = scanner.scan(ticket, emptySet())
        fun replacement() = SimilarityScanner(reader, SimilarityFeatureDecoder { _, _, _ -> error("replacement must not decode") }, owner, lane)
        fun assertFree() { val lease = lane.tryEnter(); assertNotNull(lease); lease!!.close() }
    }
    private open class Repeated(private var remaining: Long) : InputStream() {
        override fun read(): Int = if (remaining-- > 0) 7 else -1
        override fun read(b: ByteArray, o: Int, l: Int): Int {
            if (remaining <= 0) return -1
            val count = minOf(remaining, l.toLong()).toInt()
            java.util.Arrays.fill(b, o, o + count, 7.toByte()); remaining -= count
            return count
        }
    }
    private suspend fun failure(reason: SimilarityFailure, block: suspend () -> Any?) {
        val value = runCatching { block() }.exceptionOrNull()
        assertTrue("Expected $reason got $value", value is SimilarityException)
        assertEquals(reason, (value as SimilarityException).reason)
    }
}
