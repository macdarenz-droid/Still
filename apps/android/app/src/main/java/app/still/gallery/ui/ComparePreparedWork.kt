package app.still.gallery.ui

import android.content.Context
import app.still.gallery.inputs.AndroidSelectedInputs
import app.still.gallery.inputs.SelectedInputCleanupResult
import app.still.gallery.inputs.SelectedInputException
import app.still.gallery.inputs.SelectedInputFailure
import app.still.gallery.inputs.SelectedInputTicket
import app.still.gallery.media.AndroidCleanupSourceReader
import app.still.gallery.media.DeviceMedia
import app.still.gallery.media.MediaAccess
import app.still.gallery.media.MediaItem
import app.still.gallery.media.mediaAccess
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** This adapter only reads ticket-owned staged members; it never decodes MediaStore directly. */
class ComparePreparedWork(
    context: Context,
    device: DeviceMedia,
    private val selected: List<MediaItem>,
    access: MediaAccess,
    private val favorites: Set<String>,
    private val checkSources: () -> Boolean,
) : CompareWork<ComparePreview> {
    private val inputs = AndroidSelectedInputs(context, AndroidCleanupSourceReader(context, device, access))
    private val active = AtomicBoolean(true)
    @Volatile private var ticket: SelectedInputTicket? = null
    private val decoded = mutableListOf<ComparePreview>()
    private var initialized = false

    override suspend fun prepare(progress: (CompareProgress) -> Unit): List<ComparePreview> = withContext(Dispatchers.IO) {
        val job = currentCoroutineContext()
        val check = {
            job.ensureActive()
            if (!active.get() || !checkSources()) throw CancellationException("Comparison access ended")
        }
        try {
            check()
            initialized = true
            when (inputs.initialize()) {
                SelectedInputCleanupResult.CLEAN -> Unit
                SelectedInputCleanupResult.BUSY -> throw CompareWorkException(CompareFailure.BUSY)
                SelectedInputCleanupResult.FAILED -> throw CompareWorkException(CompareFailure.CLEANUP)
            }
            check()
            val acquired = inputs.acquire(selected, check).also { ticket = it }
            check()
            var lastCompleted = -1
            val pair = inputs.prepare(acquired, favorites) { update ->
                if (update.completed != lastCompleted) {
                    lastCompleted = update.completed
                    progress(CompareProgress(CompareStage.COPYING, update.completed))
                }
            }
            check()
            progress(CompareProgress(CompareStage.PREVIEWS, 0))
            pair.members.forEachIndexed { index, member ->
                check()
                val preview = try {
                    decodeComparePreview({ inputs.openRead(acquired, member) }) {
                        check()
                        if (!inputs.isCurrent(acquired)) throw CancellationException("Comparison ended")
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: SelectedInputException) { throw failure }
                catch (_: Exception) { throw CompareWorkException(CompareFailure.PREVIEW) }
                decoded += preview
                check()
                progress(CompareProgress(CompareStage.PREVIEWS, index + 1))
            }
            check()
            decoded.toList()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (problem: SelectedInputException) {
            throw CompareWorkException(when (problem.reason) {
                SelectedInputFailure.SIZE_LIMIT -> CompareFailure.SIZE_LIMIT
                SelectedInputFailure.SCOPE_LIMIT -> CompareFailure.SCOPE_LIMIT
                SelectedInputFailure.SOURCE_CHANGED, SelectedInputFailure.STALE -> CompareFailure.SOURCE_CHANGED
                SelectedInputFailure.INVALID_SELECTION -> CompareFailure.INVALID_SELECTION
                SelectedInputFailure.BUSY -> CompareFailure.BUSY
                SelectedInputFailure.NEEDS_CLEANUP, SelectedInputFailure.CLEANUP_FAILED -> CompareFailure.CLEANUP
                SelectedInputFailure.EMPTY, SelectedInputFailure.IO -> CompareFailure.UNAVAILABLE
            })
        }
    }

    override fun invalidate() { active.set(false); ticket?.let(inputs::invalidate) }

    override suspend fun cleanup(): CompareCleanup = withContext(Dispatchers.IO) {
        decoded.forEach { it.close() }
        decoded.clear()
        val acquired = ticket
        if (acquired != null) inputs.closeAndCleanup(acquired).toCompareCleanup()
        else if (initialized) inputs.initialize().toCompareCleanup()
        else CompareCleanup.CLEAN
    }
}

suspend fun recoverCompareStorage(context: Context, device: DeviceMedia): CompareCleanup =
    AndroidSelectedInputs(context, AndroidCleanupSourceReader(context, device, context.mediaAccess())).initialize().toCompareCleanup()

private fun SelectedInputCleanupResult.toCompareCleanup(): CompareCleanup = when (this) {
    SelectedInputCleanupResult.CLEAN -> CompareCleanup.CLEAN
    SelectedInputCleanupResult.BUSY -> CompareCleanup.BUSY
    SelectedInputCleanupResult.FAILED -> CompareCleanup.FAILED
}
