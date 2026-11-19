package app.still.gallery.ui

import app.still.gallery.media.MediaItem
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SimilarityPhase { IDLE, SCANNING, READY, STOPPING, STOPPED, FAILED, SETTLING, BUSY, CLOSE_FAILED }
enum class SimilarityFailure { UNAVAILABLE, INVALID_SELECTION, SOURCE_CHANGED, SCOPE_LIMIT, BUSY, CLOSE_FAILED }
enum class SimilaritySettlement { CLEAN, BUSY, CLOSE_FAILED }
class SimilarityWorkException(val reason: SimilarityFailure) : Exception()
class SimilarityRequest internal constructor()

data class SimilarityCoverageUi(
    val selected: Int = 0, val attempted: Int = 0, val featured: Int = 0,
    val empty: Int = 0, val unreadable: Int = 0, val oversized: Int = 0,
    val unsupported: Int = 0, val lowInformation: Int = 0, val budgetOmitted: Int = 0,
    val admittedBytes: Long = 0, val quotaProbeBytes: Long = 0,
    val exactPairsOmitted: Int = 0, val resultPairsOmitted: Int = 0,
    val pairChecks: Int = 0,
)

interface SimilarityWork<R : Any> {
    suspend fun scan(progress: suspend (SimilarityCoverageUi) -> Unit): R
    fun isCurrent(result: R): Boolean
    fun resolve(result: R, pair: Any): List<MediaItem>?
    fun invalidate()
    suspend fun settle(): SimilaritySettlement
}

data class SimilarityUiState<R : Any>(val request: SimilarityRequest?, val visible: Boolean,
    val phase: SimilarityPhase, val coverage: SimilarityCoverageUi, val result: R?, val failure: SimilarityFailure)

/** Analysis state is memory-only; settlement survives gallery disposal until actual reader exit. */
class SimilarityCoordinator<R : Any>(
    private val scope: CoroutineScope,
    private val allowed: () -> Boolean,
    private val recover: suspend () -> SimilaritySettlement,
    private val onWithdraw: (SimilarityRequest) -> Unit,
    private val publicationDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    settlementDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val settlementScope = CoroutineScope(SupervisorJob() + settlementDispatcher)
    private var opening by mutableStateOf<SimilarityRequest?>(null)
    private var phase by mutableStateOf(SimilarityPhase.SETTLING)
    private var result by mutableStateOf<R?>(null)
    private var coverage by mutableStateOf(SimilarityCoverageUi())
    private var failure by mutableStateOf(SimilarityFailure.UNAVAILABLE)
    private var sourceCurrent: (() -> Boolean)? = null
    private var work: SimilarityWork<R>? = null
    private var worker: Job? = null
    private var settling by mutableStateOf(false)
    private var recovered = false
    private var disposed = false
    private var maintenanceIdentity: Any? = null
    private var withdrawalRequest: SimilarityRequest? = null
    var actionIdentity: Any by mutableStateOf(Any())
        private set

    val canStart: Boolean get() = !disposed && allowed() && recovered && !settling && work == null && phase == SimilarityPhase.IDLE
    val state: SimilarityUiState<R> get() {
        val visible = current(opening)
        return SimilarityUiState(opening, visible, phase, coverage,
            result.takeIf { visible && phase == SimilarityPhase.READY }, failure)
    }

    private fun current(request: SimilarityRequest?): Boolean {
        if (disposed || request == null || opening !== request) return false
        val knownResult = result
        if (!allowed() || sourceCurrent?.invoke() != true ||
            phase == SimilarityPhase.READY && knownResult != null && work?.isCurrent(knownResult) != true) {
            // Hide immediately; enqueue state/worker settlement instead of IO in composition.
            sourceCurrent = null
            if (withdrawalRequest !== request) {
                withdrawalRequest = request
                settlementScope.launch { withContext(publicationDispatcher) {
                    if (opening === request) invalidate()
                    if (withdrawalRequest === request) withdrawalRequest = null
                } }
            }
            return false
        }
        return true
    }

    fun isCurrent(request: SimilarityRequest?, expected: R?): Boolean = expected != null && result === expected &&
        phase == SimilarityPhase.READY && current(request) && work?.isCurrent(expected) == true

    fun resolve(request: SimilarityRequest?, expected: R, pair: Any): List<MediaItem>? =
        if (isCurrent(request, expected)) work?.resolve(expected, pair) else null

    fun initialize() {
        if (disposed || !allowed() || settling || work != null) return
        settling = true; phase = SimilarityPhase.SETTLING
        val expected = Any().also { maintenanceIdentity = it; actionIdentity = Any() }
        settlementScope.launch {
            val settled = try { recover() } catch (_: Exception) { SimilaritySettlement.CLOSE_FAILED }
            withContext(publicationDispatcher) {
                settling = false
                if (!disposed && maintenanceIdentity === expected) {
                    recovered = settled == SimilaritySettlement.CLEAN
                    if (recovered) failure = SimilarityFailure.UNAVAILABLE
                    phase = when (settled) { SimilaritySettlement.CLEAN -> if (opening == null) SimilarityPhase.IDLE else SimilarityPhase.STOPPED; SimilaritySettlement.BUSY -> SimilarityPhase.BUSY; SimilaritySettlement.CLOSE_FAILED -> SimilarityPhase.CLOSE_FAILED }
                    actionIdentity = Any()
                }
                if (disposed) settlementScope.cancel()
            }
        }
    }

    fun start(expectedAction: Any, selectedCount: Int, checkSources: () -> Boolean, createWork: () -> SimilarityWork<R>) {
        if (expectedAction !== actionIdentity || !canStart || selectedCount !in 2..200 || !checkSources()) return
        opening = SimilarityRequest()
        val request = requireNotNull(opening)
        sourceCurrent = checkSources
        result = null; coverage = SimilarityCoverageUi(selected = selectedCount)
        failure = SimilarityFailure.UNAVAILABLE; phase = SimilarityPhase.SCANNING; actionIdentity = Any()
        val scanning = try { createWork() } catch (problem: Exception) {
            failure = (problem as? SimilarityWorkException)?.reason ?: SimilarityFailure.UNAVAILABLE
            phase = when (failure) { SimilarityFailure.BUSY -> SimilarityPhase.BUSY; SimilarityFailure.CLOSE_FAILED -> SimilarityPhase.CLOSE_FAILED; else -> SimilarityPhase.FAILED }
            return
        }
        work = scanning
        val launched = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val found = scanning.scan { update -> withContext(publicationDispatcher) {
                    if (current(request) && phase == SimilarityPhase.SCANNING) coverage = update
                } }
                currentCoroutineContext().ensureActive()
                if (!current(request) || phase != SimilarityPhase.SCANNING || !scanning.isCurrent(found)) throw CancellationException("Review ended")
                result = found; phase = SimilarityPhase.READY
            } catch (cancelled: CancellationException) {
                if (current(request) && phase == SimilarityPhase.SCANNING) phase = SimilarityPhase.STOPPED
                settle(scanning)
                throw cancelled
            } catch (problem: Exception) {
                if (current(request) && phase == SimilarityPhase.SCANNING) {
                    failure = (problem as? SimilarityWorkException)?.reason ?: SimilarityFailure.UNAVAILABLE
                    phase = SimilarityPhase.FAILED
                }
                settle(scanning)
            }
        }
        worker = launched
        launched.invokeOnCompletion { cause -> if (cause != null) settlementScope.launch(publicationDispatcher) {
            if (work === scanning) {
                if (current(request) && phase == SimilarityPhase.SCANNING) phase = SimilarityPhase.STOPPED
                scanning.invalidate(); settle(scanning)
            }
        } }
        launched.start()
    }

    fun stop(request: SimilarityRequest?) {
        if (!current(request) || phase != SimilarityPhase.SCANNING) return
        result = null; phase = SimilarityPhase.STOPPING
        work?.invalidate(); worker?.cancel(); work?.let(::settle)
    }

    fun close(request: SimilarityRequest?) { if (request != null && opening === request) invalidate() }

    fun invalidate() {
        opening?.let(onWithdraw)
        opening = null; sourceCurrent = null; result = null; actionIdentity = Any()
        val closing = work
        closing?.invalidate(); worker?.cancel()
        if (closing != null) { phase = SimilarityPhase.SETTLING; settle(closing) }
        else if (recovered && !settling && phase != SimilarityPhase.BUSY && phase != SimilarityPhase.CLOSE_FAILED) phase = SimilarityPhase.IDLE
    }

    fun retry(expectedAction: Any) {
        if (expectedAction !== actionIdentity || disposed || !allowed() || settling || phase !in listOf(SimilarityPhase.BUSY, SimilarityPhase.CLOSE_FAILED)) return
        work?.let { phase = SimilarityPhase.SETTLING; settle(it) } ?: initialize()
    }

    private fun settle(closing: SimilarityWork<R>) {
        if (settling) return
        settling = true
        val exited = worker
        settlementScope.launch {
            exited?.join()
            val settled = try { closing.settle() } catch (_: Exception) { SimilaritySettlement.CLOSE_FAILED }
            withContext(publicationDispatcher) {
                settling = false
                if (work === closing) {
                    result = null
                    recovered = settled == SimilaritySettlement.CLEAN
                    if (recovered) {
                        work = null
                        if (opening == null) phase = SimilarityPhase.IDLE
                        else if (phase in listOf(SimilarityPhase.STOPPING, SimilarityPhase.SETTLING, SimilarityPhase.BUSY, SimilarityPhase.CLOSE_FAILED)) phase = SimilarityPhase.STOPPED
                        if (failure == SimilarityFailure.CLOSE_FAILED) failure = SimilarityFailure.UNAVAILABLE
                    } else { phase = if (settled == SimilaritySettlement.BUSY) SimilarityPhase.BUSY else SimilarityPhase.CLOSE_FAILED }
                    actionIdentity = Any()
                }
                if (disposed) settlementScope.cancel()
            }
        }
    }

    fun dispose() { invalidate(); disposed = true; if (!settling) settlementScope.cancel() }
}
