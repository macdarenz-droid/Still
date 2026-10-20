package app.still.gallery.backup.drive

import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class DriveResumableProtocolTest {
    private val chunk = DriveProtocolLimits.CHUNK_BYTES
    private val uri = "https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&upload_id=fixture_secret_session"
    private fun denied(reason: DriveProtocolError, run: () -> Unit) {
        try { run(); fail("expected $reason") } catch (failure: DriveProtocolException) { assertEquals(reason, failure.reason) }
    }
    private class Fixture(val total: Int, val seed: Int = 101) {
        val originalEpoch = DriveFixtureEpoch.generated()
        var epoch = originalEpoch
        val protocol = DriveResumableProtocol.generated(seed, total, originalEpoch) { epoch }
    }
    private fun completion(protocol: DriveResumableProtocol, id: String = "fixture_object", status: Int = 200,
        length: Int = protocol.state.totalBytes, md5: String = protocol.expectedMd5) = DriveTransferResponse(status,
        listOf("Content-Type" to "application/json"), "{\"id\":\"$id\",\"size\":\"$length\",\"md5Checksum\":\"$md5\"}".toByteArray())
    private fun begin(protocol: DriveResumableProtocol, world: FakeDrive) { assertEquals(DriveProtocolPhase.READY, protocol.exchange(protocol.initiate(), world).phase) }
    private fun upload(protocol: DriveResumableProtocol, world: FakeDrive) {
        if (protocol.state.phase == DriveProtocolPhase.NEW) begin(protocol, world)
        var calls = 0
        while (protocol.state.phase == DriveProtocolPhase.READY) { protocol.exchange(protocol.nextChunk(), world); assertTrue(++calls <= 10) }
        assertEquals(DriveProtocolPhase.UPLOADED_UNVERIFIED, protocol.state.phase)
    }

    @Test fun generatedBinaryUsesOfficialInitiationAlignedChunksFinalChunkAndExactReadback() {
        for (total in listOf(1, chunk - 1, chunk, chunk + 1, chunk * 2 + 113, DriveProtocolLimits.OBJECT_BYTES)) {
            val fixture = Fixture(total); val protocol = fixture.protocol; val world = FakeDrive(total)
            val initiation = protocol.initiate()
            assertEquals("POST", initiation.method); assertEquals(DriveProtocolTarget.CREATE_RESUMABLE, initiation.target)
            assertEquals(mapOf("uploadType" to "resumable", "fields" to "id,size,md5Checksum"), initiation.queryParameters)
            assertEquals(total.toString(), initiation.headers["X-Upload-Content-Length"])
            assertEquals("application/octet-stream", initiation.headers["X-Upload-Content-Type"])
            protocol.exchange(initiation, world); upload(protocol, world)
            val requests = world.requests.filter { it.kind == DriveProtocolRequestKind.CHUNK }
            assertEquals((total + chunk - 1) / chunk, requests.size)
            for ((index, request) in requests.withIndex()) {
                val start = index * chunk; val count = minOf(chunk, total - start)
                assertEquals("PUT", request.method); assertEquals(count, request.contentLength)
                assertEquals("bytes $start-${start + count - 1}/$total", request.headers["Content-Range"])
                if (start + count < total) assertEquals(0, count % chunk)
            }
            assertEquals(total, protocol.state.acknowledgedBytes); assertEquals(1, world.sessions); assertEquals(1, world.objects)
            assertFalse(protocol.productionCapability); assertFalse(protocol.state.productionCapability); assertTrue(protocol.state.testOnly)
            val readback = protocol.readback()
            assertEquals("GET", readback.method); assertEquals(DriveProtocolTarget.OBJECT_CONTENT, readback.target)
            assertEquals(mapOf("alt" to "media"), readback.queryParameters); assertEquals("fixture_object", readback.objectId)
            assertEquals(DriveProtocolPhase.VERIFIED, protocol.exchange(readback, world).phase)
            assertArrayEquals(generated(fixture.seed, total), world.bytes)
            assertEquals(protocol.expectedSha256, hash("SHA-256", world.bytes))
        }
    }

    @Test fun partialAcknowledgementResumesAtExactByteWithoutRoundingOrSkippingAndKeepsChunkLengthAligned() {
        val protocol = Fixture(chunk * 2 + 113).protocol; val world = FakeDrive(protocol.state.totalBytes)
        begin(protocol, world); world.acceptPrefix = 12_345
        protocol.exchange(protocol.nextChunk(), world)
        assertEquals(12_345, protocol.state.acknowledgedBytes)
        val resumed = protocol.nextChunk()
        assertEquals(chunk, resumed.contentLength); assertEquals("bytes 12345-${12_345 + chunk - 1}/${protocol.state.totalBytes}", resumed.headers["Content-Range"])
        assertArrayEquals(generated(101, protocol.state.totalBytes).copyOfRange(12_345, 12_345 + chunk), resumed.payloadForFakeTransport())
        protocol.exchange(resumed, world); upload(protocol, world)
        assertEquals(DriveProtocolPhase.VERIFIED, protocol.exchange(protocol.readback(), world).phase)
    }

    @Test fun missingInitialRangeMeansNoConfirmedBytesAndSameSessionCanResendExactFirstChunk() {
        val protocol = Fixture(chunk + 17).protocol; val world = FakeDrive(protocol.state.totalBytes)
        begin(protocol, world); world.acceptPrefix = 0
        val first = protocol.nextChunk(); protocol.exchange(first, world)
        assertEquals(0, protocol.state.acknowledgedBytes)
        val retry = protocol.nextChunk(); assertTrue(first.sameSessionAs(retry))
        assertEquals(first.headers, retry.headers); assertArrayEquals(first.payloadForFakeTransport(), retry.payloadForFakeTransport())
        protocol.exchange(retry, world); upload(protocol, world); assertEquals(1, world.sessions)
    }

    @Test fun malformedOrOverclaimingRangeDoesNotAdvanceAndRequiresStatusQueryOnOriginalSession() {
        for (range in listOf("bytes=0-${chunk + 1}", "bytes=0-${chunk * 2 + 112}", "bytes=1-10", "bytes=0--1", "bytes=0-01", "bytes=0-9,10-20")) {
            val protocol = Fixture(chunk * 2 + 113).protocol; val world = FakeDrive(protocol.state.totalBytes)
            begin(protocol, world); val first = protocol.nextChunk()
            assertEquals(DriveProtocolPhase.UNCERTAIN, protocol.accept(first, DriveTransferResponse(308, listOf("Range" to range))).phase)
            assertEquals(0, protocol.state.acknowledgedBytes); denied(DriveProtocolError.RECONCILIATION_REQUIRED) { protocol.nextChunk() }
            val query = protocol.queryStatus(); assertTrue(first.sameSessionAs(query))
            assertEquals("PUT", query.method); assertEquals(0, query.contentLength); assertEquals("bytes */${protocol.state.totalBytes}", query.headers["Content-Range"])
            assertEquals(DriveProtocolPhase.READY, protocol.exchange(query, world).phase); assertEquals(1, world.sessions)
        }
    }

    @Test fun regressingOrMissingRangeAfterKnownProgressNeverRewindsAcknowledgement() {
        val protocol = Fixture(chunk * 2 + 113).protocol; val world = FakeDrive(protocol.state.totalBytes)
        begin(protocol, world); protocol.exchange(protocol.nextChunk(), world)
        val lost = protocol.nextChunk(); protocol.responseLost(lost)
        for (headers in listOf(emptyList(), listOf("Range" to "bytes=0-99"))) {
            val query = protocol.queryStatus(); assertEquals(DriveProtocolPhase.UNCERTAIN, protocol.accept(query, DriveTransferResponse(308, headers)).phase)
            assertEquals(chunk, protocol.state.acknowledgedBytes)
        }
        assertEquals(DriveProtocolPhase.READY, protocol.exchange(protocol.queryStatus(), world).phase)
        assertEquals(chunk, protocol.state.acknowledgedBytes)
    }

    @Test fun lostChunkResponsesReconcileAppliedOrUnappliedEffectBeforeAnotherChunkWithoutNewSession() {
        for (applied in listOf(false, true)) {
            val protocol = Fixture(chunk * 2 + 113).protocol; val world = FakeDrive(protocol.state.totalBytes)
            begin(protocol, world); val request = protocol.nextChunk()
            if (applied) world.exchange(request)
            protocol.responseLost(request)
            denied(DriveProtocolError.RECONCILIATION_REQUIRED) { protocol.nextChunk() }
            denied(DriveProtocolError.RECONCILIATION_REQUIRED) { protocol.initiate() }
            val query = protocol.queryStatus(); assertTrue(query.sameSessionAs(request)); protocol.exchange(query, world)
            assertEquals(if (applied) chunk else 0, protocol.state.acknowledgedBytes)
            denied(DriveProtocolError.INVALID_REQUEST) { protocol.accept(request, DriveTransferResponse(308)) }
            upload(protocol, world); assertEquals(1, world.sessions); assertEquals(1, world.objects)
        }
    }

    @Test fun lostFinalResponseReturnsExistingCompletionMetadataThenReadbackVerifiesOneObject() {
        val protocol = Fixture(chunk + 7).protocol; val world = FakeDrive(protocol.state.totalBytes)
        begin(protocol, world); protocol.exchange(protocol.nextChunk(), world)
        val last = protocol.nextChunk(); world.exchange(last); protocol.responseLost(last)
        assertEquals(1, world.objects); assertEquals(chunk, protocol.state.acknowledgedBytes)
        assertEquals(DriveProtocolPhase.UPLOADED_UNVERIFIED, protocol.exchange(protocol.queryStatus(), world).phase)
        assertEquals(DriveProtocolPhase.VERIFIED, protocol.exchange(protocol.readback(), world).phase)
        assertEquals(1, world.sessions); assertEquals(1, world.objects)
    }

    @Test fun initiationLossOrNonSuccessCannotCreateReplacementAndHasNoSessionToQuery() {
        for (status in listOf(302, 401, 403, 404, 410, 429, 500, 503)) {
            val protocol = Fixture(17).protocol
            assertEquals(DriveProtocolPhase.BLOCKED, protocol.accept(protocol.initiate(), DriveTransferResponse(status)).phase)
            assertEquals(DriveProtocolError.INITIATION_UNKNOWN, protocol.state.error)
            denied(DriveProtocolError.RECONCILIATION_REQUIRED) { protocol.initiate() }
            denied(DriveProtocolError.RECONCILIATION_REQUIRED) { protocol.queryStatus() }
        }
        val protocol = Fixture(17).protocol; val world = FakeDrive(17)
        val request = protocol.initiate()
        val state = protocol.exchange(request, DriveFakeTransport { world.exchange(it); throw IOException("lost generated response") })
        assertEquals(1, world.sessions); assertEquals(DriveProtocolError.INITIATION_UNKNOWN, state.error)
        denied(DriveProtocolError.RECONCILIATION_REQUIRED) { protocol.initiate() }
    }

    @Test fun sessionErrorsAndRedirectsRequireReconciliationWhileExpiredSessionStaysBlocked() {
        for (status in listOf(301, 302, 307, 400, 401, 403, 408, 429, 500, 503)) {
            val protocol = Fixture(chunk + 1).protocol; val world = FakeDrive(protocol.state.totalBytes); begin(protocol, world)
            val request = protocol.nextChunk()
            assertEquals(DriveProtocolPhase.UNCERTAIN, protocol.accept(request, DriveTransferResponse(status, listOf("Location" to "https://attacker.invalid/session"))).phase)
            denied(DriveProtocolError.RECONCILIATION_REQUIRED) { protocol.nextChunk() }
            assertEquals(DriveProtocolPhase.READY, protocol.exchange(protocol.queryStatus(), world).phase)
            assertEquals(1, world.sessions); assertEquals(0, world.objects)
        }
        for (status in listOf(404, 410)) {
            val protocol = Fixture(17).protocol; val world = FakeDrive(17); begin(protocol, world)
            assertEquals(DriveProtocolPhase.BLOCKED, protocol.accept(protocol.nextChunk(), DriveTransferResponse(status)).phase)
            assertEquals(DriveProtocolError.SESSION_EXPIRED, protocol.state.error)
            denied(DriveProtocolError.RECONCILIATION_REQUIRED) { protocol.initiate() }; denied(DriveProtocolError.RECONCILIATION_REQUIRED) { protocol.queryStatus() }
        }
    }

    @Test fun prematureCompletionFullRangeOn308AndCompletionRangeContradictionCannotPublishUploaded() {
        val protocol = Fixture(chunk + 1).protocol; val world = FakeDrive(protocol.state.totalBytes); begin(protocol, world)
        protocol.accept(protocol.nextChunk(), completion(protocol))
        assertEquals(DriveProtocolPhase.UNCERTAIN, protocol.state.phase); assertNull(protocol.state.objectId)
        protocol.exchange(protocol.queryStatus(), world); protocol.exchange(protocol.nextChunk(), world)
        val final = protocol.nextChunk(); world.exchange(final)
        protocol.accept(final, DriveTransferResponse(308, listOf("Range" to "bytes=0-${protocol.state.totalBytes - 1}")))
        assertEquals(DriveProtocolPhase.UNCERTAIN, protocol.state.phase)
        val query = protocol.queryStatus(); val ordinary = completion(protocol)
        protocol.accept(query, DriveTransferResponse(200, listOf("Content-Type" to "application/json", "Range" to "bytes=0-1"), ordinary.body()))
        assertEquals(DriveProtocolPhase.UNCERTAIN, protocol.state.phase)
        assertEquals(DriveProtocolPhase.UPLOADED_UNVERIFIED, protocol.exchange(protocol.queryStatus(), world).phase)
    }

    @Test fun wrongCompletionMetadataCanOnlyReconcileSameFinishedObjectAnd201IsAccepted() {
        for (wrong in listOf("length", "checksum", "identity", "malformed")) {
            val protocol = Fixture(17).protocol; val world = FakeDrive(17); begin(protocol, world)
            val last = protocol.nextChunk(); world.exchange(last)
            val response = when (wrong) {
                "length" -> completion(protocol, length = 18)
                "checksum" -> completion(protocol, md5 = "0".repeat(32))
                "identity" -> completion(protocol, id = "bad/id")
                else -> DriveTransferResponse(200, listOf("Content-Type" to "application/json"), "{broken".toByteArray())
            }
            assertEquals(DriveProtocolPhase.UNCERTAIN, protocol.accept(last, response).phase)
            assertNull(protocol.state.objectId); assertEquals(0, protocol.state.acknowledgedBytes)
            val query = protocol.queryStatus(); assertTrue(query.sameSessionAs(last))
            assertEquals(DriveProtocolPhase.UPLOADED_UNVERIFIED, protocol.accept(query, completion(protocol, status = 201)).phase)
            assertEquals(1, world.objects); assertEquals(1, world.sessions)
        }
    }

    @Test fun onlyFullExactShaReadbackCanVerifyAndReadback404IsNotSessionExpiry() {
        for (fault in listOf("short", "extra", "wrong", "length_header", "partial", "missing", "redirect")) {
            val protocol = Fixture(17).protocol; val world = FakeDrive(17); upload(protocol, world)
            val bytes = when (fault) { "short" -> world.bytes.copyOf(16); "extra" -> world.bytes + byteArrayOf(1)
                "wrong" -> world.bytes.copyOf().also { it[0] = (it[0].toInt() xor 255).toByte() }; else -> world.bytes.copyOf() }
            val status = when (fault) { "partial" -> 206; "missing" -> 404; "redirect" -> 302; else -> 200 }
            val response = DriveTransferResponse(status, listOf("Content-Length" to if (fault == "length_header") "1" else bytes.size.toString()), bytes)
            assertEquals(DriveProtocolPhase.BLOCKED, protocol.accept(protocol.readback(), response).phase)
            assertEquals(DriveProtocolError.READBACK_MISMATCH, protocol.state.error)
            denied(DriveProtocolError.RECONCILIATION_REQUIRED) { protocol.initiate() }
        }
    }

    @Test fun lostReadbackAllowsOnlyAnotherReadOfSameCompletedObject() {
        val protocol = Fixture(17).protocol; val world = FakeDrive(17); upload(protocol, world)
        val first = protocol.readback(); protocol.responseLost(first)
        assertEquals(DriveProtocolPhase.UPLOADED_UNVERIFIED, protocol.state.phase)
        denied(DriveProtocolError.RECONCILIATION_REQUIRED) { protocol.nextChunk() }
        denied(DriveProtocolError.RECONCILIATION_REQUIRED) { protocol.initiate() }
        val retry = protocol.readback(); assertEquals(first.objectId, retry.objectId)
        assertEquals(DriveProtocolPhase.VERIFIED, protocol.exchange(retry, world).phase); assertEquals(1, world.objects)
    }

    @Test fun cancellationDoesNotWaitForFakeExchangeAndLateCompletionCannotPublish() {
        val protocol = Fixture(17).protocol; val world = FakeDrive(17); begin(protocol, world)
        val request = protocol.nextChunk(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val result = executor.submit<DriveProtocolError?> {
            try { protocol.exchange(request, DriveFakeTransport { entered.countDown(); assertTrue(release.await(3, TimeUnit.SECONDS)); world.exchange(it) }); null }
            catch (failure: DriveProtocolException) { failure.reason }
        }
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS)); protocol.cancel()
            assertEquals(DriveProtocolPhase.CANCELLED, protocol.state.phase)
            denied(DriveProtocolError.CANCELLED) { protocol.nextChunk() }
            release.countDown(); assertEquals(DriveProtocolError.CANCELLED, result.get(3, TimeUnit.SECONDS))
            assertEquals(1, world.objects); assertEquals(0, protocol.state.acknowledgedBytes); assertNull(protocol.state.objectId)
        } finally { release.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS)) }
    }

    @Test fun concurrentRedispatchAndExternalSettlementCannotSupersedeAnUnsettledExchange() {
        val protocol = Fixture(17).protocol; val request = protocol.initiate()
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val calls = AtomicInteger()
        val executor = Executors.newSingleThreadExecutor()
        val transport = DriveFakeTransport {
            if (calls.incrementAndGet() == 1) { entered.countDown(); assertTrue(release.await(3, TimeUnit.SECONDS)) }
            DriveTransferResponse(200, listOf("Location" to uri))
        }
        val result = executor.submit<DriveProtocolState> { protocol.exchange(request, transport) }
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val duplicateError = try { protocol.exchange(request, transport); null } catch (failure: DriveProtocolException) { failure.reason }
            assertEquals("one original session initiation is dispatched", 1, calls.get())
            assertEquals(DriveProtocolError.BUSY, duplicateError)
            denied(DriveProtocolError.BUSY) { protocol.accept(request, DriveTransferResponse(200, listOf("Location" to uri))) }
            denied(DriveProtocolError.BUSY) { protocol.responseLost(request) }
            assertEquals(1, calls.get()); assertEquals(DriveProtocolPhase.INITIATING, protocol.state.phase)
            release.countDown(); assertEquals(DriveProtocolPhase.READY, result.get(3, TimeUnit.SECONDS).phase)
            assertEquals(1, calls.get())
            denied(DriveProtocolError.INVALID_REQUEST) { protocol.exchange(request, transport) }
        } finally { release.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS)) }
    }

    @Test fun staleEpochIsPermanentAndLateUploadOrReadbackCannotClaimSuccess() {
        for (stage in listOf("upload", "readback")) {
            val fixture = Fixture(17); val protocol = fixture.protocol; val world = FakeDrive(17)
            if (stage == "upload") begin(protocol, world) else upload(protocol, world)
            val request = if (stage == "upload") protocol.nextChunk() else protocol.readback()
            denied(DriveProtocolError.STALE_EPOCH) { protocol.exchange(request, DriveFakeTransport { fixture.epoch = DriveFixtureEpoch.generated(); world.exchange(it) }) }
            assertEquals(DriveProtocolPhase.FENCED, protocol.state.phase)
            assertNotEquals(DriveProtocolPhase.VERIFIED, protocol.state.phase)
            fixture.epoch = fixture.originalEpoch
            assertEquals(DriveProtocolPhase.FENCED, protocol.state.phase)
            denied(DriveProtocolError.STALE_EPOCH) { protocol.queryStatus() }
        }
    }

    @Test fun foreignManufacturedReplayedAndBusyRequestsCannotAdvanceCurrentAttempt() {
        val first = Fixture(17).protocol; val second = Fixture(17).protocol
        val own = first.initiate(); val foreign = second.initiate()
        val response = DriveTransferResponse(200, listOf("Location" to uri))
        denied(DriveProtocolError.INVALID_REQUEST) { first.accept(foreign, response) }
        val manufactured = DriveProtocolRequest(own.kind, own.target, own.method, own.headers, own.payloadForFakeTransport(), null)
        denied(DriveProtocolError.INVALID_REQUEST) { first.accept(manufactured, response) }
        assertEquals(DriveProtocolPhase.INITIATING, first.state.phase)
        first.accept(own, response); denied(DriveProtocolError.INVALID_REQUEST) { first.accept(own, response) }
        val request = first.nextChunk(); denied(DriveProtocolError.BUSY) { first.nextChunk() }; denied(DriveProtocolError.BUSY) { first.queryStatus() }
        first.responseLost(request); val query = first.queryStatus(); assertTrue(query.sameSessionAs(request))
        denied(DriveProtocolError.INVALID_REQUEST) { first.accept(request, DriveTransferResponse(308)) }
        assertEquals(DriveProtocolPhase.AWAITING_RESPONSE, first.state.phase)
    }

    @Test fun fixtureBoundsPayloadCopiesAndDisplayRecordsCannotMintSourceOrVerificationAuthority() {
        val epoch = DriveFixtureEpoch.generated()
        for (length in listOf(-1, 0, DriveProtocolLimits.OBJECT_BYTES + 1)) denied(DriveProtocolError.INVALID_FIXTURE) { DriveResumableProtocol.generated(1, length, epoch) { epoch } }
        denied(DriveProtocolError.INVALID_FIXTURE) { DriveResumableProtocol.generated(0, 17, epoch) { epoch } }
        val protocol = Fixture(17).protocol; val forged = protocol.state.copy(phase = DriveProtocolPhase.VERIFIED, objectId = "forged")
        assertFalse(forged.productionCapability); assertEquals(DriveProtocolPhase.NEW, protocol.state.phase)
        denied(DriveProtocolError.RECONCILIATION_REQUIRED) { protocol.readback() }
        val world = FakeDrive(17); begin(protocol, world); val request = protocol.nextChunk()
        val exported = request.payloadForFakeTransport(); exported.fill(0)
        assertFalse(request.payloadForFakeTransport().contentEquals(exported))
        try { (request.headers as MutableMap)["Content-Range"] = "forged"; fail("immutable headers") } catch (_: UnsupportedOperationException) { }
        protocol.exchange(request, world); assertEquals(DriveProtocolPhase.VERIFIED, protocol.exchange(protocol.readback(), world).phase)
    }

    @Test fun sessionCapabilityCannotAppearInRequestsStateOrErrorsRendering() {
        val protocol = Fixture(chunk + 1).protocol; val world = FakeDrive(protocol.state.totalBytes)
        val initiation = protocol.initiate(); val response = world.exchange(initiation); protocol.accept(initiation, response)
        val first = protocol.nextChunk(); protocol.responseLost(first); val query = protocol.queryStatus()
        for (value in listOf(protocol.toString(), protocol.state.toString(), initiation.toString(), response.toString(), first.toString(), query.toString(), first.headers.toString(), query.headers.toString())) {
            assertFalse(value.contains(uri)); assertFalse(value.contains("fixture_secret_session")); assertFalse(value.contains("upload_id="))
        }
    }

    private inner class FakeDrive(private val total: Int) : DriveFakeTransport {
        val requests = mutableListOf<DriveProtocolRequest>(); val bytes = ByteArray(total)
        var sessions = 0; var objects = 0; var offset = 0; var acceptPrefix: Int? = null
        private var sessionRequest: DriveProtocolRequest? = null
        override fun exchange(request: DriveProtocolRequest): DriveTransferResponse {
            requests += request
            if (request.target == DriveProtocolTarget.SESSION) {
                if (sessionRequest == null) sessionRequest = request else assertTrue(sessionRequest!!.sameSessionAs(request))
            }
            return when (request.kind) {
                DriveProtocolRequestKind.INITIATE -> { sessions++; DriveTransferResponse(200, listOf("Location" to uri)) }
                DriveProtocolRequestKind.CHUNK -> {
                    assertEquals(offset, request.from); val payload = request.payloadForFakeTransport()
                    assertEquals(request.through - request.from, payload.size)
                    assertEquals("bytes ${request.from}-${request.through - 1}/$total", request.headers["Content-Range"])
                    if (request.through != total) assertEquals(0, payload.size % chunk)
                    val count = acceptPrefix?.also { acceptPrefix = null } ?: payload.size
                    payload.copyInto(bytes, offset, 0, count); offset += count
                    if (offset == total && objects == 0) objects++
                    status()
                }
                DriveProtocolRequestKind.QUERY -> { assertEquals("bytes */$total", request.headers["Content-Range"]); assertEquals(0, request.contentLength); status() }
                DriveProtocolRequestKind.READBACK -> {
                    assertEquals("fixture_object", request.objectId); assertEquals(1, objects)
                    DriveTransferResponse(200, listOf("Content-Length" to bytes.size.toString()), bytes)
                }
            }
        }
        private fun status(): DriveTransferResponse = if (offset == total) DriveTransferResponse(200, listOf("Content-Type" to "application/json"),
            "{\"id\":\"fixture_object\",\"size\":\"$total\",\"md5Checksum\":\"${hash("MD5", bytes)}\"}".toByteArray())
        else DriveTransferResponse(308, if (offset == 0) emptyList() else listOf("Range" to "bytes=0-${offset - 1}"))
    }
}

private fun hash(algorithm: String, bytes: ByteArray): String = MessageDigest.getInstance(algorithm).digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
private fun generated(seed: Int, length: Int): ByteArray {
    var state = seed
    return ByteArray(length) { state = state xor (state shl 13); state = state xor (state ushr 17); state = state xor (state shl 5); state.toByte() }
}
