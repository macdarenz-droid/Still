package app.still.gallery.backup

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.net.URL
import java.security.cert.Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class AndroidDriveReadinessTest {
    private val json = "{\"storageQuota\":{\"limit\":\"1000\",\"usage\":\"100\"}}".toByteArray()
    private fun owner() = DriveConnectionSession(true, null) { true }.also {
        val account = ChosenDriveAccount(DriveAccountDisplay("fixture@example.com", "Fixture"), "a".repeat(64))
        it.complete(it.begin(it.state.action)!!, account, DriveGrant("fixture-access-token", setOf(DRIVE_FILE_SCOPE)))
    }
    private suspend fun fails(reason: DriveReadinessError, action: suspend () -> Unit) {
        try { action(); fail("expected $reason") } catch (failure: DriveReadinessFailure) { assertEquals(reason, failure.reason) }
    }
    private suspend fun check(connection: Connection, request: DriveReadinessRequest = DriveReadinessRequest { true }): DriveStorageQuota {
        val owner = owner()
        return AndroidDriveReadiness({ target -> assertEquals(AndroidDriveReadiness.ENDPOINT, target.toExternalForm()); connection })
            .check(owner.accessLease(owner.state.action)!!, request)
    }

    @Test fun fixedHttpsGetUsesOnlyHeaderBearerAndProjectedQuotaThenClosesBeforeReturning() = runBlocking {
        val connection = Connection(json)
        assertEquals(DriveStorageQuota(1_000, 100), check(connection))
        assertEquals("GET", connection.requestMethod); assertFalse(connection.instanceFollowRedirects)
        assertFalse(connection.useCaches); assertFalse(connection.allowUserInteraction)
        assertEquals(DriveReadinessLimits.CONNECT_TIMEOUT_MS, connection.connectTimeout)
        assertEquals(DriveReadinessLimits.READ_TIMEOUT_MS, connection.readTimeout)
        assertEquals("Bearer fixture-access-token", connection.getRequestProperty("Authorization"))
        assertEquals("identity", connection.getRequestProperty("Accept-Encoding"))
        assertEquals("application/json", connection.getRequestProperty("Accept"))
        assertFalse(connection.url.toExternalForm().contains("fixture-access-token"))
        assertEquals(1, connection.body.closes); assertTrue(connection.disconnections.get() > 0)
    }

    @Test fun substitutedHostPathSchemeOrQueryIsDeniedBeforeSendingAuthorization() = runBlocking {
        for (url in listOf("https://attacker.invalid/drive/v3/about?fields=storageQuota(limit,usage)",
            "https://www.googleapis.com/drive/v3/files", "https://www.googleapis.com/drive/v3/about?fields=*",
            "http://www.googleapis.com/drive/v3/about", "https://user@www.googleapis.com/drive/v3/about")) {
            val connection = Connection(json, URL(url))
            fails(DriveReadinessError.INVALID_RESPONSE) { check(connection) }
            assertNull(connection.getRequestProperty("Authorization")); assertEquals(0, connection.responses); assertEquals(0, connection.inputOpens)
        }
    }

    @Test fun redirectsAndHttpErrorsNeverReadSuccessBodyAndCloseErrorBodyWithoutParsing() = runBlocking {
        for ((code, reason) in listOf(301 to DriveReadinessError.NETWORK, 302 to DriveReadinessError.NETWORK,
            307 to DriveReadinessError.NETWORK, 401 to DriveReadinessError.NEEDS_SIGN_IN,
            403 to DriveReadinessError.PERMISSION_DENIED, 429 to DriveReadinessError.RATE_LIMITED, 500 to DriveReadinessError.NETWORK)) {
            val connection = Connection(json).apply { status = code }
            fails(reason) { check(connection) }
            assertFalse(connection.instanceFollowRedirects); assertEquals(0, connection.inputOpens)
            assertEquals(0, connection.error.reads); assertEquals(1, connection.error.closes)
            assertTrue(connection.disconnections.get() > 0)
        }
    }

    @Test fun actualChunkedResponseLimitIsEnforcedIndependentlyOfClaimedLength() = runBlocking {
        val atLimit = json + ByteArray(DriveReadinessLimits.RESPONSE_BYTES - json.size) { ' '.code.toByte() }
        assertEquals(DriveStorageQuota(1_000, 100), check(Connection(atLimit).apply { declaredLength = -1 }))
        for (declared in listOf(-1L, 1L)) {
            val tooLarge = Connection(atLimit + byteArrayOf(' '.code.toByte())).apply { declaredLength = declared }
            fails(DriveReadinessError.INVALID_RESPONSE) { check(tooLarge) }
            assertEquals(DriveReadinessLimits.RESPONSE_BYTES + 1, tooLarge.body.returned)
            assertEquals(1, tooLarge.body.closes)
        }
        val oversizedHeader = Connection(json).apply { declaredLength = DriveReadinessLimits.RESPONSE_BYTES + 1L }
        fails(DriveReadinessError.INVALID_RESPONSE) { check(oversizedHeader) }; assertEquals(0, oversizedHeader.inputOpens)
        val farOversize = Connection(atLimit + ByteArray(DriveReadinessLimits.RESPONSE_BYTES) { ' '.code.toByte() })
        fails(DriveReadinessError.INVALID_RESPONSE) { check(farOversize) }
        assertEquals("stop after the one-byte overflow probe", DriveReadinessLimits.RESPONSE_BYTES + 1, farOversize.body.returned)
    }

    @Test fun unknownFieldsEncodingContentTypeAndMalformedBodiesNeverProduceQuota() = runBlocking {
        for (body in listOf("", "{", "{\"storageQuota\":{\"usage\":-1}}", "{\"files\":[]}"))
            fails(DriveReadinessError.INVALID_RESPONSE) { check(Connection(body.toByteArray())) }
        for (type in listOf(null, "text/html", "application/json; charset=iso-8859-1")) {
            val connection = Connection(json).apply { typeValue = type }
            fails(DriveReadinessError.INVALID_RESPONSE) { check(connection) }; assertEquals(0, connection.inputOpens)
        }
        val compressed = Connection(json).apply { encodingValue = "gzip" }
        fails(DriveReadinessError.INVALID_RESPONSE) { check(compressed) }; assertEquals(0, compressed.inputOpens)
        assertEquals(DriveStorageQuota(1_000, 100), check(Connection(json).apply { typeValue = "application/json; charset=UTF-8" }))
    }

    @Test fun readTimeoutZeroReadsAndFailedSuccessfulBodyCloseCannotPublishSuccess() = runBlocking {
        val timed = Connection(json).apply { body.failure = SocketTimeoutException("fixture timeout") }
        fails(DriveReadinessError.NETWORK) { check(timed) }; assertEquals(1, timed.body.closes)
        val zero = Connection(json).apply { body.zero = true }
        fails(DriveReadinessError.INVALID_RESPONSE) { check(zero) }; assertEquals(1, zero.body.closes)
        val failedClose = Connection(json).apply { body.failClose = true }
        fails(DriveReadinessError.NETWORK) { check(failedClose) }; assertTrue(failedClose.body.closes > 0)
    }

    @Test fun withdrawnLeaseAndRequestNeverOpenHttpAndMidRequestWithdrawalDeniesResponse() = runBlocking {
        val owner = owner(); val lease = owner.accessLease(owner.state.action)!!; var opens = 0
        val transport = AndroidDriveReadiness({ opens++; Connection(json) })
        owner.disconnect(owner.state.action)
        fails(DriveReadinessError.NEEDS_SIGN_IN) { transport.check(lease, DriveReadinessRequest { true }) }; assertEquals(0, opens)
        val cancelled = DriveReadinessRequest { true }; cancelled.cancel()
        val active = owner(); fails(DriveReadinessError.CANCELLED) { transport.check(active.accessLease(active.state.action)!!, cancelled) }
        assertEquals(0, opens)
        val changed = owner(); val held = changed.accessLease(changed.state.action)!!
        val connection = Connection(json).apply { onResponse = { changed.disconnect(changed.state.action) } }
        fails(DriveReadinessError.NEEDS_SIGN_IN) { AndroidDriveReadiness({ connection }).check(held, DriveReadinessRequest { true }) }
        assertEquals(0, connection.inputOpens)
    }

    @Test fun overallDeadlineIsCheckedAfterHeadersAndDuringBodyReads() = runBlocking {
        var now = 0L; val owner = owner()
        val connection = Connection(json).apply { onResponse = { now = TimeUnit.MILLISECONDS.toNanos(DriveReadinessLimits.DEADLINE_MS) } }
        fails(DriveReadinessError.NETWORK) { AndroidDriveReadiness({ connection }, { now }).check(owner.accessLease(owner.state.action)!!, DriveReadinessRequest { true }) }
        assertEquals(0, connection.inputOpens)
        now = 0
        val slow = Connection(json).apply { body.afterRead = { now = TimeUnit.MILLISECONDS.toNanos(DriveReadinessLimits.DEADLINE_MS + 1) } }
        fails(DriveReadinessError.NETWORK) { AndroidDriveReadiness({ slow }, { now }).check(owner.accessLease(owner.state.action)!!, DriveReadinessRequest { true }) }
        assertEquals(1, slow.body.closes)
    }

    @Test fun cancellationSchedulesDisconnectOffCallerAndStopsBlockedReadWithoutPublishing() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CountDownLatch(1); val owner = owner()
        val connection = Connection(json).apply {
            body.beforeRead = { entered.complete(Unit); if (!release.await(3, TimeUnit.SECONDS)) throw IOException("fixture read did not unblock") }
            onDisconnect = { release.countDown() }
        }
        val request = DriveReadinessRequest { true }
        val result = async(Dispatchers.Default) {
            try { AndroidDriveReadiness({ connection }).check(owner.accessLease(owner.state.action)!!, request); null }
            catch (failure: DriveReadinessFailure) { failure.reason }
        }
        try {
            withTimeout(3_000) { entered.await() }; request.cancel()
            assertEquals(DriveReadinessError.CANCELLED, withTimeout(3_000) { result.await() })
            assertTrue(connection.disconnections.get() > 0); assertEquals(1, connection.body.closes)
        } finally { release.countDown(); request.cancel(); result.cancel() }
    }

    private class Body(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closes = 0; var reads = 0; var returned = 0; var failure: IOException? = null
        var zero = false; var failClose = false; var beforeRead: (() -> Unit)? = null; var afterRead: (() -> Unit)? = null
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            reads++; beforeRead?.invoke(); failure?.let { throw it }; if (zero) return 0
            val count = super.read(bytes, offset, length); if (count > 0) returned += count; afterRead?.invoke(); return count
        }
        override fun close() { closes++; if (failClose) throw IOException("fixture close failure"); super.close() }
    }
    private class Connection(bytes: ByteArray, url: URL = URL(AndroidDriveReadiness.ENDPOINT)) : HttpsURLConnection(url) {
        val body = Body(bytes); val error = Body("unparsed error".toByteArray()); val disconnections = AtomicInteger()
        var inputOpens = 0; var responses = 0; var status = 200; var declaredLength = -1L
        var typeValue: String? = "application/json"; var encodingValue: String? = null
        var onResponse: (() -> Unit)? = null; var onDisconnect: (() -> Unit)? = null
        override fun getResponseCode(): Int { responses++; onResponse?.invoke(); return status }
        override fun getInputStream(): InputStream { inputOpens++; return body }
        override fun getErrorStream(): InputStream? = if (status == 200) null else error
        override fun getContentType(): String? = typeValue
        override fun getContentEncoding(): String? = encodingValue
        override fun getContentLengthLong(): Long = declaredLength
        override fun connect() = Unit
        override fun disconnect() { disconnections.incrementAndGet(); onDisconnect?.invoke() }
        override fun usingProxy() = false
        override fun getCipherSuite(): String = "fixture-only"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
    }
}
