package app.still.gallery.ui

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

enum class ComparePhase { IDLE, PREPARING, READY, STOPPING, CANCELLED, FAILED, CLEANING, CLEANUP_FAILED, BUSY }
enum class CompareFailure { UNAVAILABLE, PREVIEW, SIZE_LIMIT, SCOPE_LIMIT, SOURCE_CHANGED, INVALID_SELECTION, BUSY, CLEANUP }
enum class CompareCleanup { CLEAN, BUSY, FAILED }
enum class CompareStage { COPYING, PREVIEWS }
data class CompareProgress(val stage: CompareStage = CompareStage.COPYING, val completed: Int = 0)
class CompareWorkException(val reason: CompareFailure) : Exception()

/** The work owner retains resources until cleanup, even if coroutine delivery is canceled. */
interface CompareWork<P : AutoCloseable> {
    suspend fun prepare(progress: (CompareProgress) -> Unit): List<P>
    fun invalidate()
    suspend fun cleanup(): CompareCleanup
}

data class CompareUiState<P : AutoCloseable>(
    val request: CompareRequest?,
    val visible: Boolean,
    val phase: ComparePhase,
    val names: List<String>,
    val previews: List<P>,
    val progress: CompareProgress,
    val failure: CompareFailure,
    val transform: CompareTransform,
)

/** Session-only owner. The independent cleanup scope survives disposal until real worker exit. */
class CompareCoordinator<P : AutoCloseable>(
    private val scope: CoroutineScope,
    private val allowed: () -> Boolean,
    private val recover: suspend () -> CompareCleanup,
    private val publicationDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    cleanupDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val fence = CompareRequestFence(allowed)
    private val cleanupScope = CoroutineScope(SupervisorJob() + cleanupDispatcher)
    private var opening by mutableStateOf<CompareRequest?>(null)
    private var phase by mutableStateOf(ComparePhase.CLEANING)
    private var names by mutableStateOf(emptyList<String>())
    private var previews by mutableStateOf(emptyList<P>())
    private var progress by mutableStateOf(CompareProgress())
    private var failure by mutableStateOf(CompareFailure.UNAVAILABLE)
    private var transform by mutableStateOf(CompareTransform())
    private var sourceCurrent: (() -> Boolean)? = null
    private var work: CompareWork<P>? = null
    private var worker: Job? = null
    private var cleaner: Job? = null
    private var cleaning by mutableStateOf(false)
    private var maintenanceIdentity: Any? = null
    private var disposed = false
    private var recovered = false
    var actionIdentity: Any by mutableStateOf(Any())
        private set

    private fun current(request: CompareRequest?): Boolean {
        if (disposed || !fence.isCurrent(request) || opening !== request) return false
        if (sourceCurrent?.invoke() != true) { fence.invalidate(); return false }
        return true
    }

    val canStart: Boolean get() = !disposed && allowed() && recovered && work == null && !cleaning && phase == ComparePhase.IDLE
    val state: CompareUiState<P> get() {
        val visible = current(opening)
        return CompareUiState(opening, visible, phase, if (visible) names else emptyList(),
            if (visible && phase == ComparePhase.READY) previews else emptyList(), progress, failure, transform)
    }

    fun initialize() {
        if (disposed || !allowed() || cleaning || work != null) return
        cleaning = true
        phase = ComparePhase.CLEANING
        val expected = Any().also { maintenanceIdentity = it; actionIdentity = Any() }
        cleaner = cleanupScope.launch {
            val result = try { recover() } catch (_: Exception) { CompareCleanup.FAILED }
            withContext(publicationDispatcher) {
                cleaning = false
                if (!disposed && maintenanceIdentity === expected) {
                    recovered = result == CompareCleanup.CLEAN
                    phase = when (result) { CompareCleanup.CLEAN -> ComparePhase.IDLE; CompareCleanup.BUSY -> ComparePhase.BUSY; CompareCleanup.FAILED -> ComparePhase.CLEANUP_FAILED }
                    actionIdentity = Any()
                }
                if (disposed) cleanupScope.cancel()
            }
        }
    }

    fun start(expectedAction: Any, selectedNames: List<String>, checkSources: () -> Boolean, createWork: () -> CompareWork<P>) {
        if (expectedAction !== actionIdentity || !canStart || selectedNames.size != 2 || !checkSources()) return
        val request = fence.open() ?: return
        opening = request
        sourceCurrent = checkSources
        names = selectedNames.toList()
        previews = emptyList()
        progress = CompareProgress()
        transform = CompareTransform()
        phase = ComparePhase.PREPARING
        actionIdentity = Any()
        val preparing = try { createWork() } catch (_: Exception) { phase = ComparePhase.FAILED; return }
        work = preparing
        val launched = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = preparing.prepare { next ->
                    scope.launch(publicationDispatcher) {
                        if (current(request) && phase == ComparePhase.PREPARING) progress = next
                    }
                }
                currentCoroutineContext().ensureActive()
                if (!current(request) || phase != ComparePhase.PREPARING || result.size != 2) throw CancellationException("Comparison ended")
                previews = result.toList()
                phase = ComparePhase.READY
            } catch (cancelled: CancellationException) {
                if (current(request) && phase == ComparePhase.PREPARING) phase = ComparePhase.CANCELLED
                scheduleCleanup(preparing)
                throw cancelled
            } catch (problem: Exception) {
                if (current(request) && phase == ComparePhase.PREPARING) {
                    failure = (problem as? CompareWorkException)?.reason ?: CompareFailure.UNAVAILABLE
                    phase = ComparePhase.FAILED
                }
                scheduleCleanup(preparing)
            }
        }
        worker = launched // Assign before even synchronous preparation/failure may run.
        launched.invokeOnCompletion { cause ->
            if (cause != null) cleanupScope.launch(publicationDispatcher) {
                // Also runs when a canceled scope never entered the worker body.
                if (work === preparing) {
                    if (current(request) && phase == ComparePhase.PREPARING) phase = ComparePhase.CANCELLED
                    preparing.invalidate()
                    scheduleCleanup(preparing)
                }
            }
        }
        launched.start()
    }

    fun change(request: CompareRequest?, zoomFactor: Float = 1f, dx: Float = 0f, dy: Float = 0f) {
        if (current(request) && phase == ComparePhase.READY) transform = transform.change(zoomFactor, dx, dy)
    }

    fun reset(request: CompareRequest?) { if (current(request) && phase == ComparePhase.READY) transform = CompareTransform() }

    fun stop(request: CompareRequest?) {
        if (!current(request) || phase != ComparePhase.PREPARING) return
        phase = ComparePhase.STOPPING
        work?.invalidate()
        worker?.cancel()
        work?.let(::scheduleCleanup)
    }

    fun close(request: CompareRequest?) {
        if (request == null || opening !== request) return
        invalidate()
    }

    fun invalidate() {
        fence.invalidate()
        opening = null
        previews = emptyList()
        names = emptyList()
        sourceCurrent = null
        transform = CompareTransform()
        actionIdentity = Any()
        val closing = work
        closing?.invalidate()
        worker?.cancel()
        if (closing != null) { phase = ComparePhase.CLEANING; scheduleCleanup(closing) }
        else if (recovered && phase != ComparePhase.CLEANUP_FAILED && phase != ComparePhase.BUSY && !cleaning) phase = ComparePhase.IDLE
    }

    fun retryCleanup(expectedAction: Any) {
        if (expectedAction !== actionIdentity || disposed || !allowed() || cleaning ||
            phase != ComparePhase.CLEANUP_FAILED && phase != ComparePhase.BUSY) return
        val closing = work
        if (closing != null) { phase = ComparePhase.CLEANING; scheduleCleanup(closing) } else initialize()
    }

    private fun scheduleCleanup(closing: CompareWork<P>) {
        if (cleaning) return
        cleaning = true
        val exited = worker
        cleaner = cleanupScope.launch {
            exited?.join()
            val result = try { closing.cleanup() } catch (_: Exception) { CompareCleanup.FAILED }
            withContext(publicationDispatcher) {
                cleaning = false
                if (work === closing) {
                    previews = emptyList()
                    if (result == CompareCleanup.CLEAN) {
                        recovered = true
                        work = null
                        if (opening == null) phase = ComparePhase.IDLE
                        else if (phase == ComparePhase.STOPPING) phase = ComparePhase.CANCELLED
                    } else {
                        recovered = false
                        phase = if (result == CompareCleanup.BUSY) ComparePhase.BUSY else ComparePhase.CLEANUP_FAILED
                        failure = CompareFailure.CLEANUP
                    }
                    actionIdentity = Any()
                }
                if (disposed) cleanupScope.cancel()
            }
        }
    }

    fun dispose() {
        invalidate()
        disposed = true
        if (!cleaning) cleanupScope.cancel()
    }
}
