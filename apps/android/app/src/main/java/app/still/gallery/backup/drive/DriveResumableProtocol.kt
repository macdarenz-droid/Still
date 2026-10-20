package app.still.gallery.backup.drive

import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/** Fixture identity only, never an OAuth account, grant, or production epoch. */
class DriveFixtureEpoch private constructor() {
    companion object { fun generated() = DriveFixtureEpoch() }
    override fun toString() = "DriveFixtureEpoch(testOnly)"
}

enum class DriveProtocolPhase { NEW, INITIATING, READY, AWAITING_RESPONSE, UNCERTAIN, UPLOADED_UNVERIFIED, VERIFYING, VERIFIED, BLOCKED, CANCELLED, FENCED }
enum class DriveProtocolRequestKind { INITIATE, CHUNK, QUERY, READBACK }
enum class DriveProtocolTarget { CREATE_RESUMABLE, SESSION, OBJECT_CONTENT }
data class DriveProtocolState(val phase: DriveProtocolPhase, val acknowledgedBytes: Int, val totalBytes: Int,
    val objectId: String? = null, val error: DriveProtocolError? = null) {
    val testOnly = true
    val productionCapability = false
}

/** No URL getter: fake transports compare the opaque session identity instead. */
class DriveProtocolRequest internal constructor(val kind: DriveProtocolRequestKind, val target: DriveProtocolTarget,
    val method: String, headers: Map<String, String>, payload: ByteArray, private val session: DriveSession?,
    val objectId: String? = null, internal val from: Int = 0, internal val through: Int = 0) {
    val headers: Map<String, String> = Collections.unmodifiableMap(headers.toMap())
    val queryParameters: Map<String, String> = Collections.unmodifiableMap(when (kind) {
        DriveProtocolRequestKind.INITIATE -> mapOf("uploadType" to "resumable", "fields" to "id,size,md5Checksum")
        DriveProtocolRequestKind.READBACK -> mapOf("alt" to "media")
        else -> emptyMap()
    })
    private val bytes = payload.copyOf()
    val contentLength: Int get() = bytes.size
    fun payloadForFakeTransport(): ByteArray = bytes.copyOf()
    fun sameSessionAs(other: DriveProtocolRequest): Boolean = session != null && session === other.session
    override fun toString() = "DriveProtocolRequest(kind=$kind, contentLength=$contentLength, redacted)"
}

/** Deliberately no real HTTP implementation or authorization input. */
fun interface DriveFakeTransport { fun exchange(request: DriveProtocolRequest): DriveTransferResponse }

/** One memory-only generated binary object. No media, URI or caller bytes can become a source. */
class DriveResumableProtocol private constructor(private val source: ByteArray, private val epoch: DriveFixtureEpoch,
    private val currentEpoch: () -> DriveFixtureEpoch) {
    companion object {
        fun generated(seed: Int, byteLength: Int, epoch: DriveFixtureEpoch, currentEpoch: () -> DriveFixtureEpoch): DriveResumableProtocol {
            protocolRequire(seed != 0 && byteLength in 1..DriveProtocolLimits.OBJECT_BYTES, DriveProtocolError.INVALID_FIXTURE)
            var state = seed
            val bytes = ByteArray(byteLength) { state = state xor (state shl 13); state = state xor (state ushr 17); state = state xor (state shl 5); state.toByte() }
            return DriveResumableProtocol(bytes, epoch, currentEpoch)
        }
    }
    val testOnly = true
    val productionCapability = false
    val expectedSha256: String = digest("SHA-256", source)
    val expectedMd5: String = digest("MD5", source)
    private val lock = Any()
    private val cancelled = AtomicBoolean(false)
    private val fenced = AtomicBoolean(false)
    @Volatile private var published = DriveProtocolState(DriveProtocolPhase.NEW, 0, source.size)
    private var session: DriveSession? = null
    private var pending: DriveProtocolRequest? = null
    private var inFlight: Any? = null
    private var maxSubmitted = 0
    val state: DriveProtocolState get() {
        if (cancelled.get()) return published.copy(phase = DriveProtocolPhase.CANCELLED, error = DriveProtocolError.CANCELLED)
        if (!sameEpoch()) return published.copy(phase = DriveProtocolPhase.FENCED, error = DriveProtocolError.STALE_EPOCH)
        return published
    }

    fun initiate(): DriveProtocolRequest = synchronized(lock) {
        checkAdmission(); protocolRequire(published.phase == DriveProtocolPhase.NEW && pending == null, DriveProtocolError.RECONCILIATION_REQUIRED)
        val body = "{\"name\":\"Still generated protocol fixture.bin\",\"mimeType\":\"application/octet-stream\"}".toByteArray()
        val request = DriveProtocolRequest(DriveProtocolRequestKind.INITIATE, DriveProtocolTarget.CREATE_RESUMABLE, "POST",
            mapOf("Content-Type" to "application/json; charset=UTF-8", "Content-Length" to body.size.toString(),
                "X-Upload-Content-Type" to "application/octet-stream", "X-Upload-Content-Length" to source.size.toString()), body, null)
        pending = request; publish(published.copy(phase = DriveProtocolPhase.INITIATING, error = null)); request
    }

    fun nextChunk(): DriveProtocolRequest = synchronized(lock) {
        checkAdmission(); protocolRequire(pending == null, DriveProtocolError.BUSY)
        protocolRequire(published.phase == DriveProtocolPhase.READY && session != null, DriveProtocolError.RECONCILIATION_REQUIRED)
        val from = published.acknowledgedBytes
        protocolRequire(from < source.size, DriveProtocolError.RECONCILIATION_REQUIRED)
        val through = from + minOf(DriveProtocolLimits.CHUNK_BYTES, source.size - from)
        val bytes = source.copyOfRange(from, through)
        val request = DriveProtocolRequest(DriveProtocolRequestKind.CHUNK, DriveProtocolTarget.SESSION, "PUT",
            mapOf("Content-Type" to "application/octet-stream", "Content-Length" to bytes.size.toString(),
                "Content-Range" to "bytes $from-${through - 1}/${source.size}"), bytes, session, from = from, through = through)
        maxSubmitted = maxOf(maxSubmitted, through); pending = request
        publish(published.copy(phase = DriveProtocolPhase.AWAITING_RESPONSE, error = null)); request
    }

    fun queryStatus(): DriveProtocolRequest = synchronized(lock) {
        checkAdmission(); protocolRequire(pending == null, DriveProtocolError.BUSY)
        protocolRequire(session != null && published.phase in setOf(DriveProtocolPhase.UNCERTAIN, DriveProtocolPhase.READY), DriveProtocolError.RECONCILIATION_REQUIRED)
        val request = DriveProtocolRequest(DriveProtocolRequestKind.QUERY, DriveProtocolTarget.SESSION, "PUT",
            mapOf("Content-Length" to "0", "Content-Range" to "bytes */${source.size}"), byteArrayOf(), session)
        pending = request; publish(published.copy(phase = DriveProtocolPhase.AWAITING_RESPONSE, error = null)); request
    }

    fun readback(): DriveProtocolRequest = synchronized(lock) {
        checkAdmission(); protocolRequire(pending == null, DriveProtocolError.BUSY)
        protocolRequire(published.phase == DriveProtocolPhase.UPLOADED_UNVERIFIED && published.objectId != null, DriveProtocolError.RECONCILIATION_REQUIRED)
        val request = DriveProtocolRequest(DriveProtocolRequestKind.READBACK, DriveProtocolTarget.OBJECT_CONTENT, "GET",
            mapOf("Accept" to "application/octet-stream"), byteArrayOf(), null, published.objectId)
        pending = request; publish(published.copy(phase = DriveProtocolPhase.VERIFYING, error = null)); request
    }

    /** Fake exchange runs outside the policy lock; cancel/epoch withdrawal never waits for it. */
    fun exchange(request: DriveProtocolRequest, transport: DriveFakeTransport): DriveProtocolState {
        val flight = Any()
        synchronized(lock) { checkAdmission(); checkRequest(request); inFlight = flight }
        try {
            val response = try { transport.exchange(request) }
            catch (failure: Throwable) {
                val outcome = lost(request, flight)
                if (failure is Error) throw failure
                return outcome
            }
            return acceptResponse(request, response, flight)
        } finally { synchronized(lock) { if (inFlight === flight) inFlight = null } }
    }

    fun responseLost(request: DriveProtocolRequest): DriveProtocolState = lost(request, null)
    private fun lost(request: DriveProtocolRequest, flight: Any?): DriveProtocolState = synchronized(lock) {
        checkSettlement(request, flight); pending = null
        val next = when (request.kind) {
            DriveProtocolRequestKind.INITIATE -> published.copy(phase = DriveProtocolPhase.BLOCKED, error = DriveProtocolError.INITIATION_UNKNOWN)
            DriveProtocolRequestKind.READBACK -> published.copy(phase = DriveProtocolPhase.UPLOADED_UNVERIFIED, error = DriveProtocolError.RECONCILIATION_REQUIRED)
            else -> published.copy(phase = DriveProtocolPhase.UNCERTAIN, error = DriveProtocolError.RECONCILIATION_REQUIRED)
        }
        publish(next); state
    }

    fun accept(request: DriveProtocolRequest, response: DriveTransferResponse): DriveProtocolState = acceptResponse(request, response, null)
    private fun acceptResponse(request: DriveProtocolRequest, response: DriveTransferResponse, flight: Any?): DriveProtocolState = synchronized(lock) {
        checkSettlement(request, flight)
        val next = try { responseState(request, response) }
        catch (failure: DriveProtocolException) {
            published.copy(phase = if (session == null || request.kind == DriveProtocolRequestKind.READBACK) DriveProtocolPhase.BLOCKED else DriveProtocolPhase.UNCERTAIN, error = failure.reason)
        }
        checkCurrent(); pending = null; publish(next); state
    }

    fun cancel() { cancelled.set(true) }

    private fun responseState(request: DriveProtocolRequest, response: DriveTransferResponse): DriveProtocolState {
        if (request.kind == DriveProtocolRequestKind.INITIATE) {
            if (response.status != 200) return published.copy(phase = DriveProtocolPhase.BLOCKED, error = DriveProtocolError.INITIATION_UNKNOWN)
            protocolRequire(response.bodyLength == 0 && response.header("range") == null)
            session = DriveSession.parse(response.header("location") ?: throw DriveProtocolException(DriveProtocolError.INVALID_SESSION))
            return published.copy(phase = DriveProtocolPhase.READY, error = null)
        }
        if (request.kind == DriveProtocolRequestKind.READBACK) {
            protocolRequire(response.status == 200 && response.bodyLength == source.size, DriveProtocolError.READBACK_MISMATCH)
            protocolRequire(response.header("content-length")?.let { it == source.size.toString() } != false, DriveProtocolError.READBACK_MISMATCH)
            protocolRequire(digest("SHA-256", response.body()) == expectedSha256, DriveProtocolError.READBACK_MISMATCH)
            return published.copy(phase = DriveProtocolPhase.VERIFIED, error = null)
        }
        if (response.status == 404 || response.status == 410)
            return published.copy(phase = DriveProtocolPhase.BLOCKED, error = DriveProtocolError.SESSION_EXPIRED)
        if (response.status == 308) {
            protocolRequire(response.bodyLength == 0)
            val acknowledged = driveAcknowledgedBytes(response.header("range"))
            val ceiling = if (request.kind == DriveProtocolRequestKind.CHUNK) request.through else maxSubmitted
            protocolRequire(acknowledged >= published.acknowledgedBytes && acknowledged <= ceiling && acknowledged < source.size)
            return published.copy(phase = DriveProtocolPhase.READY, acknowledgedBytes = acknowledged.toInt(), error = null)
        }
        if (response.status == 200 || response.status == 201) {
            protocolRequire(maxSubmitted == source.size && response.header("range") == null)
            val completed = driveCompletion(response, source.size, expectedMd5)
            protocolRequire(published.objectId == null || published.objectId == completed.objectId)
            return published.copy(phase = DriveProtocolPhase.UPLOADED_UNVERIFIED, acknowledgedBytes = source.size, objectId = completed.objectId, error = null)
        }
        return published.copy(phase = DriveProtocolPhase.UNCERTAIN, error = DriveProtocolError.RECONCILIATION_REQUIRED)
    }

    private fun sameEpoch(): Boolean {
        if (fenced.get()) return false
        val current = try { currentEpoch() === epoch } catch (_: RuntimeException) { false }
        if (!current) fenced.set(true)
        return current
    }
    private fun checkCurrent() {
        protocolRequire(!cancelled.get(), DriveProtocolError.CANCELLED)
        protocolRequire(sameEpoch(), DriveProtocolError.STALE_EPOCH)
    }
    private fun checkAdmission() { checkCurrent(); protocolRequire(inFlight == null, DriveProtocolError.BUSY) }
    private fun checkSettlement(request: DriveProtocolRequest, flight: Any?) {
        checkCurrent(); protocolRequire(inFlight == null || inFlight === flight, DriveProtocolError.BUSY); checkRequest(request)
    }
    private fun checkRequest(request: DriveProtocolRequest) { checkCurrent(); protocolRequire(pending === request, DriveProtocolError.INVALID_REQUEST) }
    private fun publish(value: DriveProtocolState) { checkCurrent(); published = value }
    override fun toString() = "DriveResumableProtocol(testOnly, productionCapability=false, redacted)"
}

private fun digest(algorithm: String, bytes: ByteArray): String = MessageDigest.getInstance(algorithm).digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
