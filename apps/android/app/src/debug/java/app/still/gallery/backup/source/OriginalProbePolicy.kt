package app.still.gallery.backup.source

import app.still.gallery.media.CleanupReadAdmission
import app.still.gallery.media.cleanupProcessReads
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** Debug fixture observations only. None of these facts grant source/backup authority. */
object OriginalProbeBounds {
    const val READ_BYTES = 64 * 1024
    const val MAX_BYTES = 32L * 1024 * 1024
    const val DEADLINE_MS = 30_000L
}
enum class OriginalProbeFailure { UNSUPPORTED_API, LOCATION_PERMISSION_REQUIRED, BUSY, CANCELLED, TIMEOUT, IO, LIMIT, BYTE_MISMATCH, CLOSE_FAILED, CLEANUP_FAILED }
enum class OriginalProbeCleanup { CLEAN, BUSY, FAILED }
data class OriginalProbeObservation(val failure: OriginalProbeFailure?, val actualBytes: Long = 0,
    val sha256: String? = null, val probeBytes: Int = 0, val locationPermissionGranted: Boolean = false) {
    val generatedFixtureOnly = true
    val productionCapability = false
    val backupEligible = false
    val sourceStabilityEstablished = false
    val componentCompletenessEstablished = false
}
internal class ProbeFault(val reason: OriginalProbeFailure) : Exception()
internal data class ProbeExpected(val length: Long, val sha256: String)
internal class ProbeInput(val stream: InputStream, val resource: ProbeResource)
internal interface OriginalProbeSource {
    val supported: Boolean
    fun locationGranted(): Boolean
    fun prepare(resources: ProbeResources): ProbeExpected
    fun openOriginal(resources: ProbeResources): ProbeInput
    fun abort()
    fun cleanup()
}

/** A returned close exception retains its exact handle for an explicit settlement retry. */
internal class ProbeResource(private val action: () -> Unit, private val remove: (ProbeResource) -> Unit) : AutoCloseable {
    @Volatile var failed = false; private set
    private var closed = false
    @Synchronized override fun close() {
        if (closed) return
        try { action() } catch (_: Exception) { failed = true; throw ProbeFault(OriginalProbeFailure.CLOSE_FAILED) }
        closed = true; remove(this)
    }
}
internal class ProbeResources {
    private val held = mutableListOf<ProbeResource>()
    @Synchronized fun own(close: () -> Unit): ProbeResource = ProbeResource(close) { synchronized(this) { held.remove(it) } }.also { held += it }
    @Synchronized fun owns(resource: ProbeResource) = held.any { it === resource }
    @Synchronized fun snapshot() = held.toList()
}
internal class ProbeHolder(val source: OriginalProbeSource, val lane: AutoCloseable) {
    val resources = ProbeResources()
    val withdrawn = AtomicBoolean(false)
    val result = CompletableFuture<OriginalProbeObservation>()
    var working = true
    var settling = false
}

/** Process ownership spans controller recreation and includes generated rows and failed closes. */
internal class OriginalProbeOwnership {
    private var holder: ProbeHolder? = null
    @Synchronized fun acquire(source: OriginalProbeSource, admission: CleanupReadAdmission): ProbeHolder? {
        if (holder != null) return null
        val lane = admission.tryEnter() ?: return null
        return ProbeHolder(source, lane).also { holder = it }
    }
    @Synchronized fun finished(value: ProbeHolder) { check(holder === value); value.working = false }
    fun settle(): OriginalProbeCleanup {
        val current = synchronized(this) {
            val value = holder ?: return OriginalProbeCleanup.CLEAN
            if (value.working || value.settling) return OriginalProbeCleanup.BUSY
            value.settling = true; value
        }
        var clean = false
        try {
            for (resource in current.resources.snapshot().asReversed()) resource.close()
            current.source.cleanup()
            clean = true
            return OriginalProbeCleanup.CLEAN
        } catch (_: Exception) { return OriginalProbeCleanup.FAILED }
        finally {
            synchronized(this) {
                current.settling = false
                if (clean) { check(current.resources.snapshot().isEmpty()); current.lane.close(); holder = null }
            }
        }
    }
    /** Ordinary completion never silently retries a close that already failed. */
    fun finish(value: ProbeHolder): OriginalProbeCleanup {
        finished(value)
        if (value.resources.snapshot().any { it.failed }) return OriginalProbeCleanup.FAILED
        return settle()
    }
}
private val originalProbeProcessOwnership = OriginalProbeOwnership()
private val originalProbeWorkers = Executors.newSingleThreadExecutor { task -> Thread(task, "still-generated-original-probe").apply { isDaemon = true } }
private val originalProbeAborts = Executors.newSingleThreadExecutor { task -> Thread(task, "still-generated-probe-abort").apply { isDaemon = true } }

/** No snapshot, URI, stream, source or bytes escape. Await is an off-main-thread test operation. */
class OriginalProbeAttempt internal constructor(private val holder: ProbeHolder?, private val completed: CompletableFuture<OriginalProbeObservation>,
    private val abortExecutor: Executor) {
    fun cancel() { withdraw(OriginalProbeFailure.CANCELLED) }
    private fun withdraw(reason: OriginalProbeFailure) {
        val current = holder ?: return
        current.withdrawn.set(true)
        completed.complete(OriginalProbeObservation(reason))
        try { abortExecutor.execute { try { current.source.abort() } catch (_: Exception) { } } } catch (_: RuntimeException) { }
    }
    fun await(timeoutMs: Long = OriginalProbeBounds.DEADLINE_MS): OriginalProbeObservation {
        require(timeoutMs in 1..OriginalProbeBounds.DEADLINE_MS)
        return try { completed.get(timeoutMs, TimeUnit.MILLISECONDS) }
        catch (_: TimeoutException) { withdraw(OriginalProbeFailure.TIMEOUT); completed.getNow(OriginalProbeObservation(OriginalProbeFailure.TIMEOUT)) }
        catch (_: InterruptedException) { withdraw(OriginalProbeFailure.CANCELLED); Thread.currentThread().interrupt(); OriginalProbeObservation(OriginalProbeFailure.CANCELLED) }
    }
    override fun toString() = "OriginalProbeAttempt(generatedFixtureOnly, redacted)"
}

internal class OriginalProbeEngine(private val ownership: OriginalProbeOwnership = originalProbeProcessOwnership,
    private val admission: CleanupReadAdmission = cleanupProcessReads, private val worker: Executor = originalProbeWorkers,
    private val abortExecutor: Executor = originalProbeAborts, private val limit: Long = OriginalProbeBounds.MAX_BYTES) {
    init { require(limit in 1..OriginalProbeBounds.MAX_BYTES) }
    fun start(source: OriginalProbeSource, current: () -> Boolean = { true }): OriginalProbeAttempt {
        fun blocked(reason: OriginalProbeFailure) = OriginalProbeAttempt(null, CompletableFuture.completedFuture(OriginalProbeObservation(reason)), abortExecutor)
        if (!source.supported) return blocked(OriginalProbeFailure.UNSUPPORTED_API)
        if (!source.locationGranted()) return blocked(OriginalProbeFailure.LOCATION_PERMISSION_REQUIRED)
        if (!current()) return blocked(OriginalProbeFailure.CANCELLED)
        val holder = ownership.acquire(source, admission) ?: return blocked(OriginalProbeFailure.BUSY)
        val attempt = OriginalProbeAttempt(holder, holder.result, abortExecutor)
        try { worker.execute { run(holder, current) } }
        catch (_: RuntimeException) {
            ownership.finish(holder)
            holder.result.complete(OriginalProbeObservation(OriginalProbeFailure.IO))
        }
        return attempt
    }
    fun settle() = ownership.settle()
    private fun run(holder: ProbeHolder, current: () -> Boolean) {
        val source = holder.source
        fun checkCurrent() {
            if (holder.withdrawn.get() || !current()) throw ProbeFault(OriginalProbeFailure.CANCELLED)
            if (!source.locationGranted()) throw ProbeFault(OriginalProbeFailure.LOCATION_PERMISSION_REQUIRED)
        }
        var count = 0L; var probes = 0; var hash: String? = null; var failure: OriginalProbeFailure? = null; var verified = false
        try {
            checkCurrent()
            val expected = source.prepare(holder.resources)
            checkCurrent()
            if (expected.length !in 1..limit || !expected.sha256.matches(Regex("[a-f0-9]{64}"))) throw ProbeFault(OriginalProbeFailure.LIMIT)
            val opened = source.openOriginal(holder.resources)
            if (!holder.resources.owns(opened.resource)) throw ProbeFault(OriginalProbeFailure.IO)
            try {
                val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(OriginalProbeBounds.READ_BYTES)
                var zeros = 0
                while (true) {
                    checkCurrent()
                    if (count == limit) {
                        val extra = opened.stream.read()
                        checkCurrent()
                        if (extra !in -1..255) throw ProbeFault(OriginalProbeFailure.IO)
                        if (extra != -1) { probes++; throw ProbeFault(OriginalProbeFailure.LIMIT) }
                        break
                    }
                    val requested = minOf(buffer.size.toLong(), limit - count).toInt()
                    val length = opened.stream.read(buffer, 0, requested)
                    checkCurrent()
                    if (length == -1) break
                    if (length < -1 || length > requested) throw ProbeFault(OriginalProbeFailure.IO)
                    if (length == 0) { if (++zeros >= 8) throw ProbeFault(OriginalProbeFailure.IO); continue }
                    zeros = 0; count += length; digest.update(buffer, 0, length)
                }
                hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                if (count != expected.length || hash != expected.sha256) throw ProbeFault(OriginalProbeFailure.BYTE_MISMATCH)
            } finally { opened.resource.close() }
            checkCurrent(); verified = true
        } catch (problem: ProbeFault) { failure = problem.reason }
        catch (_: Exception) { failure = OriginalProbeFailure.IO }
        finally {
            // Do not lose a close failure suppressed by a primary read/prepare failure.
            for (resource in holder.resources.snapshot().asReversed().filterNot { it.failed }) {
                try { resource.close() } catch (_: Exception) { }
            }
            if (holder.resources.snapshot().any { it.failed }) failure = OriginalProbeFailure.CLOSE_FAILED
            val cleanup = ownership.finish(holder)
            if (cleanup != OriginalProbeCleanup.CLEAN && failure != OriginalProbeFailure.CLOSE_FAILED) failure = OriginalProbeFailure.CLEANUP_FAILED
            if (!verified && failure == null) failure = OriginalProbeFailure.IO
            if (holder.withdrawn.get() || !runCatching(current).getOrDefault(false)) failure = failure ?: OriginalProbeFailure.CANCELLED
            val finalPermission = runCatching { source.locationGranted() }.getOrDefault(false)
            if (!finalPermission) failure = failure ?: OriginalProbeFailure.LOCATION_PERMISSION_REQUIRED
            holder.result.complete(OriginalProbeObservation(failure, count, if (failure == null) hash else null, probes, finalPermission))
        }
    }
}
