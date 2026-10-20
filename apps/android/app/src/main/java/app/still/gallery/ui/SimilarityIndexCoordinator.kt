package app.still.gallery.ui

import android.content.Context
import app.still.gallery.media.CleanupSourceReader
import app.still.gallery.media.CleanupSourceSnapshot
import app.still.gallery.similarity.SimilarityException
import app.still.gallery.similarity.SimilarityFailure as AnalysisFailure
import app.still.gallery.similarity.SimilarityIndexProgress
import app.still.gallery.similarity.SimilarityIndexStore
import app.still.gallery.similarity.SimilarityIndexStoreSession
import app.still.gallery.similarity.SimilarityIndexClearSession
import app.still.gallery.similarity.SimilarityIndexClearStore
import app.still.gallery.similarity.SimilarityIndexRunControl
import app.still.gallery.similarity.SimilarityLibraryIndexWork
import app.still.gallery.similarity.SimilaritySettleResult
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

enum class SimilarityIndexPhase { LOADING, IDLE, RUNNING, STOPPING, STOPPED, COMPLETE, PARTIAL, CLEARING, FAILED, BUSY, CLOSE_FAILED }
enum class SimilarityIndexUiFailure { UNAVAILABLE, SOURCE_CHANGED, SCOPE_LIMIT, BUSY, CLOSE_FAILED }
class SimilarityIndexAction internal constructor()

data class SimilarityIndexUiState(
    val phase: SimilarityIndexPhase,
    val progress: SimilarityIndexProgress,
    val stored: Int,
    val failure: SimilarityIndexUiFailure?,
    val action: SimilarityIndexAction,
)

/** Foreground-only user control. Cleanup scope survives UI cancellation until provider reads settle. */
class SimilarityIndexCoordinator(
    private val scope: CoroutineScope,
    private val allowed: () -> Boolean,
    private val openStore: () -> SimilarityIndexStoreSession,
    private val openClearStore: () -> SimilarityIndexClearSession,
    private val createWork: (CleanupSourceReader, SimilarityIndexStoreSession, Set<String>, () -> Unit) -> SimilarityIndexRunControl,
    private val publicationDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    settlementDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val settlementScope = CoroutineScope(SupervisorJob() + settlementDispatcher)

    constructor(scope: CoroutineScope, context: Context, allowed: () -> Boolean,
        publicationDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate) : this(
        scope = scope,
        allowed = allowed,
        openStore = { SimilarityIndexStore(context.applicationContext) },
        openClearStore = { SimilarityIndexClearStore(context.applicationContext) },
        createWork = { reader, store, favorites, checkCurrent -> SimilarityLibraryIndexWork(reader, store, favorites, checkCurrent) },
        publicationDispatcher = publicationDispatcher,
    )

    private var phase by mutableStateOf(SimilarityIndexPhase.LOADING)
    private var progress by mutableStateOf(SimilarityIndexProgress())
    private var stored by mutableStateOf(0)
    private var failure by mutableStateOf<SimilarityIndexUiFailure?>(null)
    private var action by mutableStateOf(SimilarityIndexAction())
    private var initialized = false
    private var disposed = false
    private var work: SimilarityIndexRunControl? = null
    private var store: SimilarityIndexStoreSession? = null
    private var worker: Job? = null
    private var runIdentity: Any? = null
    private var stopRequested = false
    private var invalidateActiveWork: (() -> Unit)? = null

    val state: SimilarityIndexUiState get() = SimilarityIndexUiState(phase, progress, stored, failure, action)
    val canStart: Boolean get() = !disposed && initialized && allowed() && work == null &&
        phase in setOf(SimilarityIndexPhase.IDLE, SimilarityIndexPhase.STOPPED, SimilarityIndexPhase.COMPLETE, SimilarityIndexPhase.PARTIAL, SimilarityIndexPhase.FAILED)
    val canClear: Boolean get() = !disposed && initialized && allowed() && work == null && phase != SimilarityIndexPhase.CLEARING

    fun initialize() {
        if (disposed || initialized) return
        phase = SimilarityIndexPhase.LOADING
        val expected = Any().also { runIdentity = it; action = SimilarityIndexAction() }
        scope.launch {
            val count = try { withContext(ioDispatcher) { openStore().use { it.count() } } }
                catch (_: Exception) { null }
            withContext(publicationDispatcher) {
                if (!disposed && runIdentity === expected) {
                    initialized = true
                    if (count == null) { failure = SimilarityIndexUiFailure.UNAVAILABLE; phase = SimilarityIndexPhase.FAILED }
                    else { stored = count; failure = null; phase = SimilarityIndexPhase.IDLE }
                    action = SimilarityIndexAction()
                }
            }
        }
    }

    fun start(expectedAction: SimilarityIndexAction, reader: CleanupSourceReader, favorites: Set<String>,
        checkCurrent: () -> Unit, onSnapshot: suspend (CleanupSourceSnapshot) -> Unit) {
        if (expectedAction !== action || !canStart || runCatching(checkCurrent).isFailure) return
        val openedStore = try { openStore() } catch (_: Exception) {
            failure = SimilarityIndexUiFailure.UNAVAILABLE; phase = SimilarityIndexPhase.FAILED; action = SimilarityIndexAction(); return
        }
        val indexing = try { createWork(reader, openedStore, favorites.toSet(), checkCurrent) } catch (_: Exception) {
            try { openedStore.close() } catch (_: Exception) { }
            failure = SimilarityIndexUiFailure.UNAVAILABLE; phase = SimilarityIndexPhase.FAILED; action = SimilarityIndexAction(); return
        }
        val identity = Any()
        val invalidated = AtomicBoolean(false)
        val invalidateOnce = { if (invalidated.compareAndSet(false, true)) indexing.invalidate() }
        store = openedStore; work = indexing; runIdentity = identity; stopRequested = false
        invalidateActiveWork = invalidateOnce
        progress = SimilarityIndexProgress(indexed = stored)
        failure = null; phase = SimilarityIndexPhase.RUNNING; action = SimilarityIndexAction()
        var result: SimilarityIndexProgress? = null
        var failed: SimilarityIndexUiFailure? = null
        val finalized = AtomicBoolean(false)
        suspend fun finishRun() {
            if (!finalized.compareAndSet(false, true)) return
            val settled = withContext(NonCancellable + ioDispatcher) {
                val state = try { indexing.settle() } catch (_: Exception) { SimilaritySettleResult.CLOSE_FAILED }
                val actualCount = try { openedStore.count() } catch (_: Exception) { null }
                try { openedStore.close() } catch (_: Exception) { if (failed == null) failed = SimilarityIndexUiFailure.UNAVAILABLE }
                state to actualCount
            }
            withContext(NonCancellable + publicationDispatcher) {
                if (!disposed && runIdentity === identity) {
                    invalidateActiveWork = null
                    settled.second?.let { stored = it }
                    when {
                        settled.first == SimilaritySettleResult.CLOSE_FAILED -> { failure = SimilarityIndexUiFailure.CLOSE_FAILED; phase = SimilarityIndexPhase.CLOSE_FAILED }
                        settled.first == SimilaritySettleResult.BUSY -> { failure = SimilarityIndexUiFailure.BUSY; phase = SimilarityIndexPhase.BUSY }
                        stopRequested -> { work = null; store = null; failure = null; phase = SimilarityIndexPhase.STOPPED }
                        failed != null -> { work = null; store = null; failure = failed; phase = if (failed == SimilarityIndexUiFailure.BUSY) SimilarityIndexPhase.BUSY else SimilarityIndexPhase.FAILED }
                        result != null -> { work = null; store = null; progress = result!!; stored = result!!.indexed; phase = if (result!!.complete) SimilarityIndexPhase.COMPLETE else SimilarityIndexPhase.PARTIAL }
                        else -> { work = null; store = null; phase = SimilarityIndexPhase.STOPPED }
                    }
                    action = SimilarityIndexAction()
                }
                if (disposed) settlementScope.cancel()
            }
        }
        val launched = scope.launchLazyWithEntryFallback(
            onUnstartedCompletion = {
                invalidateOnce()
                settlementScope.launch { finishRun() }
            },
        ) {
            try {
                result = indexing.run(
                    onSnapshot = { snapshot ->
                        currentCoroutineContext().ensureActive()
                        withContext(publicationDispatcher) {
                            if (runIdentity !== identity || !allowed()) throw CancellationException("Similarity index was withdrawn")
                            onSnapshot(snapshot)
                        }
                    },
                    onProgress = { update ->
                        withContext(publicationDispatcher) {
                            if (runIdentity === identity && phase == SimilarityIndexPhase.RUNNING && allowed()) {
                                progress = update; stored = update.indexed
                            }
                        }
                    },
                )
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (problem: SimilarityException) { failed = problem.toUiFailure() }
            catch (_: Exception) { failed = SimilarityIndexUiFailure.UNAVAILABLE }
            finally {
                finishRun()
            }
        }
        worker = launched
        launched.start()
    }

    fun stop() {
        if (disposed || phase != SimilarityIndexPhase.RUNNING) return
        stopRequested = true; phase = SimilarityIndexPhase.STOPPING; action = SimilarityIndexAction()
        invalidateActiveWork?.invoke(); worker?.cancel()
    }

    fun retryClose(expectedAction: SimilarityIndexAction) {
        if (expectedAction !== action || disposed || phase !in setOf(SimilarityIndexPhase.BUSY, SimilarityIndexPhase.CLOSE_FAILED)) return
        val retained = work
        if (retained == null) {
            initialized = false; failure = null; phase = SimilarityIndexPhase.LOADING
            initialize()
            return
        }
        phase = SimilarityIndexPhase.STOPPING; action = SimilarityIndexAction()
        settlementScope.launch {
            val settled = try { retained.settle() } catch (_: Exception) { SimilaritySettleResult.CLOSE_FAILED }
            withContext(publicationDispatcher) {
                if (!disposed && work === retained) {
                    if (settled == SimilaritySettleResult.CLEAN) {
                        work = null; store = null; failure = null; phase = SimilarityIndexPhase.STOPPED
                    } else {
                        failure = if (settled == SimilaritySettleResult.BUSY) SimilarityIndexUiFailure.BUSY else SimilarityIndexUiFailure.CLOSE_FAILED
                        phase = if (settled == SimilaritySettleResult.BUSY) SimilarityIndexPhase.BUSY else SimilarityIndexPhase.CLOSE_FAILED
                    }
                    action = SimilarityIndexAction()
                }
            }
        }
    }

    fun clear(expectedAction: SimilarityIndexAction) {
        if (expectedAction !== action || !canClear) return
        phase = SimilarityIndexPhase.CLEARING; failure = null; action = SimilarityIndexAction()
        val identity = Any().also { runIdentity = it }
        scope.launch {
            val cleared = try { withContext(ioDispatcher) { openClearStore().use { it.clear() } }; true }
                catch (_: Exception) { false }
            withContext(publicationDispatcher) {
                if (!disposed && runIdentity === identity) {
                    if (cleared) { stored = 0; progress = SimilarityIndexProgress(); phase = SimilarityIndexPhase.IDLE; failure = null }
                    else { phase = SimilarityIndexPhase.FAILED; failure = SimilarityIndexUiFailure.UNAVAILABLE }
                    action = SimilarityIndexAction()
                }
            }
        }
    }

    fun dispose() {
        if (disposed) return
        stop(); disposed = true
        worker?.cancel()
        if (work == null) settlementScope.cancel()
    }

    private fun SimilarityException.toUiFailure() = when (reason) {
        AnalysisFailure.BUSY -> SimilarityIndexUiFailure.BUSY
        AnalysisFailure.CLOSE_FAILED -> SimilarityIndexUiFailure.CLOSE_FAILED
        AnalysisFailure.SCOPE_LIMIT -> SimilarityIndexUiFailure.SCOPE_LIMIT
        AnalysisFailure.SOURCE_CHANGED, AnalysisFailure.STALE, AnalysisFailure.INVALID_SELECTION -> SimilarityIndexUiFailure.SOURCE_CHANGED
        AnalysisFailure.IO -> SimilarityIndexUiFailure.UNAVAILABLE
    }
}
