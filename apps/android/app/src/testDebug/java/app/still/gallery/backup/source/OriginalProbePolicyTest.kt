package app.still.gallery.backup.source

import app.still.gallery.media.CleanupReadAdmission
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** Generated fake stream/row seam only; no Android original/completeness/device evidence. */
class OriginalProbePolicyTest {
    private class Source(val bytes: ByteArray = ByteArray(91) { (it * 7).toByte() }) : OriginalProbeSource {
        override var supported = true
        var permission = true
        var expected = ProbeExpected(bytes.size.toLong(), hash(bytes))
        var prepares = 0; var opens = 0; var cleanupCalls = 0; var aborts = 0; var closed = 0
        var row = false
        var cleanupFailures = 0
        var abortFailures = 0
        var cleanupAction: () -> Unit = {}
        var closeFailures = 0
        var prepareAction: ((ProbeResources) -> Unit)? = null
        var stream: () -> InputStream = { ByteArrayInputStream(bytes) }
        override fun locationGranted() = permission
        override fun prepare(resources: ProbeResources): ProbeExpected { prepares++; row = true; prepareAction?.invoke(resources); return expected }
        override fun openOriginal(resources: ProbeResources): ProbeInput {
            opens++; val value = stream()
            val resource = resources.own { if (closeFailures-- > 0) throw IOException(); value.close(); closed++ }
            return ProbeInput(value, resource)
        }
        override fun abort() { aborts++; if (abortFailures-- > 0) throw IOException() }
        override fun cleanup() { cleanupCalls++; if (cleanupFailures-- > 0) throw IOException(); cleanupAction(); row = false }
    }
    private class Harness {
        val owner = OriginalProbeOwnership()
        val lane = CleanupReadAdmission()
        fun engine(executor: Executor = Executor { it.run() }, limit: Long = OriginalProbeBounds.MAX_BYTES) = OriginalProbeEngine(owner, lane, executor, Executor { it.run() }, limit)
        fun assertReleased() { val value = lane.tryEnter(); assertNotNull(value); value!!.close() }
    }
    @Test fun generatedFixtureObservationsNeverGrantBackupStabilityOrInventoryAuthority() {
        val h = Harness(); val source = Source(); val result = h.engine().start(source).await()
        assertNull(result.failure); assertEquals(source.bytes.size.toLong(), result.actualBytes); assertEquals(hash(source.bytes), result.sha256)
        assertTrue(result.generatedFixtureOnly); assertTrue(result.locationPermissionGranted)
        assertFalse(result.productionCapability); assertFalse(result.backupEligible); assertFalse(result.sourceStabilityEstablished); assertFalse(result.componentCompletenessEstablished)
        assertEquals(1, source.opens); assertEquals(1, source.closed); assertFalse(source.row)
        assertEquals(OriginalProbeCleanup.CLEAN, h.engine().settle()); h.assertReleased()
    }
    @Test fun unsupportedApiDoesNotInsertOrOpenAnything() {
        val h = Harness(); val source = Source().apply { supported = false }
        assertEquals(OriginalProbeFailure.UNSUPPORTED_API, h.engine().start(source).await().failure)
        assertEquals(0, source.prepares); assertEquals(0, source.opens); h.assertReleased()
    }
    @Test fun missingLocationPermissionDeniesBeforeGeneratedRowCreation() {
        val h = Harness(); val source = Source().apply { permission = false }
        assertEquals(OriginalProbeFailure.LOCATION_PERMISSION_REQUIRED, h.engine().start(source).await().failure)
        assertEquals(0, source.prepares); assertEquals(0, source.opens); h.assertReleased()
    }
    @Test fun withdrawnCallerDoesNotCreateFixture() {
        val source = Source(); val result = Harness().engine().start(source) { false }.await()
        assertEquals(OriginalProbeFailure.CANCELLED, result.failure); assertEquals(0, source.prepares)
    }
    @Test fun permissionLostDuringPreparationBlocksOriginalOpenAndCleansRow() {
        val source = Source().apply { prepareAction = { permission = false } }
        val result = Harness().engine().start(source).await()
        assertEquals(OriginalProbeFailure.LOCATION_PERMISSION_REQUIRED, result.failure); assertEquals(0, source.opens); assertFalse(source.row)
    }
    @Test fun failedPreparationAfterInsertStillCleansExactFixture() {
        val source = Source().apply { prepareAction = { throw IOException() } }
        assertEquals(OriginalProbeFailure.IO, Harness().engine().start(source).await().failure)
        assertFalse(source.row); assertEquals(1, source.cleanupCalls); assertEquals(0, source.opens)
    }
    @Test fun prepareCloseUncertaintyRetainsWriterAndRowAcrossControllerRecreation() {
        val h = Harness(); var remaining = 1; var closed = false
        val source = Source().apply { prepareAction = { resources -> resources.own { if (remaining-- > 0) throw IOException(); closed = true }.close() } }
        assertEquals(OriginalProbeFailure.CLOSE_FAILED, h.engine().start(source).await().failure)
        assertFalse(closed); assertTrue(source.row); assertEquals(0, source.cleanupCalls); assertNull(h.lane.tryEnter())
        assertEquals(OriginalProbeFailure.BUSY, h.engine().start(Source()).await().failure)
        assertEquals(OriginalProbeCleanup.CLEAN, h.engine().settle()); assertTrue(closed); assertFalse(source.row); h.assertReleased()
    }
    @Test fun byteMismatchTruncationExtraAndMutationNeverProduceSuccessOrHash() {
        for (body in listOf(byteArrayOf(), ByteArray(90), ByteArray(92), ByteArray(91) { 4 })) {
            val source = Source().apply { stream = { ByteArrayInputStream(body) } }
            val result = Harness().engine().start(source).await()
            assertEquals(OriginalProbeFailure.BYTE_MISMATCH, result.failure); assertNull(result.sha256); assertFalse(source.row); assertEquals(1, source.closed)
        }
    }
    @Test fun actualEofBoundAcceptsExactLengthAndCountsOneOverLimitProbeByte() {
        val h = Harness(); val bytes = ByteArray(128) { it.toByte() }
        assertNull(h.engine(limit = 128).start(Source(bytes)).await().failure)
        val source = Source(bytes).apply { stream = { ByteArrayInputStream(bytes + byteArrayOf(2, 3, 4)) } }
        val result = h.engine(limit = 128).start(source).await()
        assertEquals(OriginalProbeFailure.LIMIT, result.failure); assertEquals(128L, result.actualBytes); assertEquals(1, result.probeBytes); assertNull(result.sha256)
        assertEquals(1, source.closed); assertFalse(source.row); h.assertReleased()
    }
    @Test fun invalidOrExcessExpectedFixtureLengthBlocksBeforeOpen() {
        for (length in listOf(0L, -1, 129, Long.MAX_VALUE)) {
            val source = Source().apply { expected = ProbeExpected(length, hash(bytes)) }
            assertEquals(OriginalProbeFailure.LIMIT, Harness().engine(limit = 128).start(source).await().failure)
            assertEquals(0, source.opens); assertFalse(source.row)
        }
    }
    @Test fun malformedReadAndEightZeroReadsAreRejectedWithCleanup() {
        for (value in listOf(-2, 65_537, 0)) {
            val source = Source().apply { stream = { object : InputStream() {
                override fun read() = value
                override fun read(b: ByteArray, off: Int, len: Int) = value
            } } }
            assertEquals(OriginalProbeFailure.IO, Harness().engine().start(source).await().failure)
            assertEquals(1, source.closed); assertFalse(source.row)
        }
    }
    @Test fun closeFailureDominatesPrimaryIoAndRuntimeReadFailures() {
        for (runtime in listOf(false, true)) {
            val h = Harness(); val source = Source().apply {
                closeFailures = 1
                stream = { object : InputStream() { override fun read(): Int = if (runtime) throw IllegalStateException() else throw IOException() } }
            }
            val attempt = h.engine().start(source)
            assertEquals(OriginalProbeFailure.CLOSE_FAILED, attempt.await().failure)
            assertEquals(1, source.opens); assertEquals(0, source.closed); assertEquals(0, source.cleanupCalls); assertTrue(source.row); assertNull(h.lane.tryEnter())
            assertEquals(OriginalProbeCleanup.CLEAN, h.engine().settle()); assertEquals(1, source.closed); assertFalse(source.row)
            assertEquals(OriginalProbeFailure.CLOSE_FAILED, attempt.await().failure); h.assertReleased()
        }
    }
    @Test fun aSuccessfulReadWithFailedCloseCannotPublishOrDeleteLiveFixture() {
        val h = Harness(); val source = Source().apply { closeFailures = 1 }
        val result = h.engine().start(source).await()
        assertEquals(OriginalProbeFailure.CLOSE_FAILED, result.failure); assertNull(result.sha256); assertTrue(source.row)
        assertEquals(OriginalProbeCleanup.CLEAN, h.engine().settle()); assertFalse(source.row)
    }
    @Test fun failedCleanupBlocksNewFixturesAndRecreatedControllerRetriesOriginalOwner() {
        val h = Harness(); val source = Source().apply { cleanupFailures = 2 }
        assertEquals(OriginalProbeFailure.CLEANUP_FAILED, h.engine().start(source).await().failure)
        assertTrue(source.row); assertNull(h.lane.tryEnter())
        val fresh = Source(); assertEquals(OriginalProbeFailure.BUSY, h.engine().start(fresh).await().failure); assertEquals(0, fresh.prepares)
        assertEquals(OriginalProbeCleanup.FAILED, h.engine().settle()); assertTrue(source.row)
        assertEquals(OriginalProbeCleanup.CLEAN, h.engine().settle()); assertFalse(source.row); h.assertReleased()
    }
    @Test fun busyExistingSharedLaneDoesNotCreateAnotherFixture() {
        val h = Harness(); val lane = h.lane.tryEnter()!!
        try { val source = Source(); assertEquals(OriginalProbeFailure.BUSY, h.engine().start(source).await().failure); assertEquals(0, source.prepares) }
        finally { lane.close() }
    }
    @Test fun executorRejectionReleasesOwnedSlotWithoutOpeningOrLeavingRow() {
        val h = Harness(); val source = Source()
        assertEquals(OriginalProbeFailure.IO, h.engine(Executor { throw java.util.concurrent.RejectedExecutionException() }).start(source).await().failure)
        assertEquals(0, source.prepares); assertFalse(source.row); h.assertReleased()
    }
    private fun stalled(close: Boolean, action: (Harness, Source, OriginalProbeAttempt, CountDownLatch, java.util.concurrent.ExecutorService) -> Unit) {
        val h = Harness(); val entered = CountDownLatch(1); val release = CountDownLatch(1); val pool = Executors.newSingleThreadExecutor()
        val source = Source().apply { stream = { object : ByteArrayInputStream(bytes) {
            override fun read(b: ByteArray, off: Int, len: Int): Int { if (!close) { entered.countDown(); assertTrue(release.await(3, TimeUnit.SECONDS)) }; return super.read(b, off, len) }
            override fun close() { if (close) { entered.countDown(); assertTrue(release.await(3, TimeUnit.SECONDS)) }; super.close() }
        } } }
        try {
            val attempt = h.engine(pool).start(source)
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            action(h, source, attempt, release, pool)
        } finally { release.countDown(); pool.shutdown(); assertTrue(pool.awaitTermination(3, TimeUnit.SECONDS)); h.engine().settle() }
    }
    @Test fun blockedReadTimesOutCallerButRetainsActualStreamRowAndLane() = stalled(false) { h, source, attempt, release, pool ->
        val before = System.nanoTime(); assertEquals(OriginalProbeFailure.TIMEOUT, attempt.await(25).failure)
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before) < 1000)
        assertTrue(source.row); assertEquals(0, source.closed); assertNull(h.lane.tryEnter()); assertEquals(OriginalProbeCleanup.BUSY, h.engine().settle())
        assertEquals(OriginalProbeFailure.BUSY, h.engine().start(Source()).await().failure)
        release.countDown(); pool.submit {}.get(2, TimeUnit.SECONDS)
        assertFalse(source.row); assertEquals(OriginalProbeFailure.TIMEOUT, attempt.await().failure); h.assertReleased()
    }
    @Test fun blockedCloseTimesOutWithoutDeletingOrReleasingItsOpenDescriptor() = stalled(true) { h, source, attempt, release, pool ->
        assertEquals(OriginalProbeFailure.TIMEOUT, attempt.await(25).failure); assertTrue(source.row); assertNull(h.lane.tryEnter()); assertEquals(0, source.closed)
        release.countDown(); pool.submit {}.get(2, TimeUnit.SECONDS)
        assertFalse(source.row); assertEquals(1, source.closed); assertEquals(OriginalProbeFailure.TIMEOUT, attempt.await().failure); h.assertReleased()
    }
    @Test fun cancellationReturnsPromptlyAndLateReadCannotReplaceCancelledResult() = stalled(false) { h, source, attempt, release, pool ->
        attempt.cancel(); assertEquals(OriginalProbeFailure.CANCELLED, attempt.await().failure); assertTrue(source.row); assertEquals(1, source.aborts)
        release.countDown(); pool.submit {}.get(2, TimeUnit.SECONDS)
        assertEquals(OriginalProbeFailure.CANCELLED, attempt.await().failure); assertFalse(source.row); h.assertReleased()
    }
    @Test fun currentAuthorityLossDuringReadPreventsSuccessAfterExactBytes() {
        var current = true
        val source = Source().apply { stream = { object : ByteArrayInputStream(bytes) {
            override fun read(b: ByteArray, off: Int, len: Int): Int { current = false; return super.read(b, off, len) }
        } } }
        val result = Harness().engine().start(source) { current }.await()
        assertEquals(OriginalProbeFailure.CANCELLED, result.failure); assertNull(result.sha256); assertFalse(source.row)
    }
    @Test fun abortFailureDoesNotReleaseBlockedPhysicalOwnership() = stalled(false) { h, source, attempt, release, pool ->
        source.abortFailures = 1
        attempt.cancel(); assertEquals(1, source.aborts); assertNull(h.lane.tryEnter()); assertEquals(OriginalProbeFailure.CANCELLED, attempt.await().failure)
        release.countDown(); pool.submit {}.get(2, TimeUnit.SECONDS); assertFalse(source.row); h.assertReleased()
    }
    @Test fun permissionLostDuringCleanupCannotPublishAnOriginalObservation() {
        val source = Source().apply { cleanupAction = { permission = false } }
        val result = Harness().engine().start(source).await()
        assertEquals(OriginalProbeFailure.LOCATION_PERMISSION_REQUIRED, result.failure)
        assertNull(result.sha256); assertFalse(result.locationPermissionGranted); assertFalse(source.row)
    }
    @Test fun malformedSingleByteEofProbeIsIoFailureRatherThanAcceptedContent() {
        val source = Source(ByteArray(128)).apply { stream = { object : ByteArrayInputStream(bytes) { override fun read() = 256 } } }
        val result = Harness().engine(limit = 128).start(source).await()
        assertEquals(OriginalProbeFailure.IO, result.failure); assertNull(result.sha256); assertEquals(0, result.probeBytes); assertFalse(source.row)
    }
    @Test fun resultRenderingContainsNoMediaBytesOrUriAndCopiedObservationHasNoAuthority() {
        val source = Source(); val result = Harness().engine().start(source).await()
        assertFalse(result.toString().contains("content://")); assertFalse(result.toString().contains(String(source.bytes)))
        assertFalse(result.copy().backupEligible); assertFalse(result.copy().productionCapability)
        assertEquals(OriginalProbeBounds.MAX_BYTES, 32L * 1024 * 1024)
    }
    companion object { private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) } }
}
