package app.still.gallery.similarity

import app.still.gallery.media.CleanupReadAdmission
import java.io.InputStream
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Process-wide only: a failed or blocked close cannot be replaced by a recreated UI. */
class SimilarityReadOwnership {
    internal class Work(val ticket: SimilarityTicket, val lane: CleanupReadAdmission.Lease) {
        var running = true
        var settling = false
        val resources = mutableListOf<Resource>()
    }
    internal class Resource(private val input: InputStream, private val release: (Resource) -> Unit) : AutoCloseable {
        @Volatile var failed = false
            private set
        private var closed = false
        @Synchronized override fun close() {
            if (closed) return
            try { input.close() } catch (_: Exception) { failed = true; throw SimilarityException(SimilarityFailure.CLOSE_FAILED) }
            closed = true
            release(this)
        }
    }
    private val holder = AtomicReference<Work?>(null)
    @Synchronized internal fun enter(ticket: SimilarityTicket, admission: CleanupReadAdmission): Work {
        ticket.requireCurrent()
        if (holder.get() != null) throw SimilarityException(SimilarityFailure.BUSY)
        val lane = admission.tryEnter() ?: throw SimilarityException(SimilarityFailure.BUSY)
        return Work(ticket, lane).also { holder.set(it) }
    }
    @Synchronized internal fun track(work: Work, input: InputStream): Resource {
        check(holder.get() === work && work.running)
        return Resource(input) { resource -> synchronized(this) { work.resources.remove(resource); releaseIfFinished(work) } }
            .also { work.resources += it }
    }
    @Synchronized internal fun finish(work: Work): Boolean {
        check(holder.get() === work && work.running)
        work.running = false
        releaseIfFinished(work)
        return work.resources.isEmpty()
    }
    private fun releaseIfFinished(work: Work) {
        if (!work.running && !work.settling && work.resources.isEmpty() && holder.compareAndSet(work, null)) work.lane.close()
    }
    suspend fun settle(): SimilaritySettleResult = withContext(NonCancellable + Dispatchers.IO) {
        val work = synchronized(this@SimilarityReadOwnership) {
            val current = holder.get() ?: return@withContext SimilaritySettleResult.CLEAN
            if (current.running || current.settling || current.resources.any { !it.failed }) return@withContext SimilaritySettleResult.BUSY
            current.settling = true
            current
        }
        try {
            val pending = synchronized(this@SimilarityReadOwnership) { work.resources.toList() }
            for (resource in pending) resource.close()
            SimilaritySettleResult.CLEAN
        } catch (_: Exception) { SimilaritySettleResult.CLOSE_FAILED }
        finally { synchronized(this@SimilarityReadOwnership) { work.settling = false; releaseIfFinished(work) } }
    }
}

internal val similarityProcessReads = SimilarityReadOwnership()
