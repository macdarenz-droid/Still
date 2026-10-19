package app.still.gallery.inputs

import app.still.gallery.media.MediaItem
import app.still.gallery.media.cleanupSourceReference
import app.still.gallery.media.sourceUiToken
import java.io.IOException
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

object SelectedInputBounds {
    const val BUFFER_BYTES = 64 * 1024
    const val MAX_INPUT_BYTES = 32L * 1024 * 1024
    const val MAX_RETAINED_BYTES = 64L * 1024 * 1024
}

enum class SelectedInputFailure { BUSY, NEEDS_CLEANUP, INVALID_SELECTION, STALE, SOURCE_CHANGED, SIZE_LIMIT, SCOPE_LIMIT, EMPTY, IO, CLEANUP_FAILED }
class SelectedInputException(val reason: SelectedInputFailure) : IOException("Selected photo preparation: ${reason.name}")
enum class SelectedInputCleanupResult { CLEAN, BUSY, FAILED }
enum class SelectedInputFidelity { READABLE_REPRESENTATION }
data class SelectedInputProgress(val completed: Int, val total: Int = 2, val bytes: Long)

/** Memory-only authority identity; never save, reconstruct, log, or reuse after invalidation. */
class SelectedInputTicket internal constructor(
    internal val rootKey: String,
    selected: List<MediaItem>,
    private val checkCurrent: () -> Unit,
) {
    internal val selected: List<MediaItem> = Collections.unmodifiableList(selected.toList())
    private val active = AtomicBoolean(selected.isNotEmpty())
    internal fun invalidate() { active.set(false) }
    internal val isActive: Boolean get() = active.get()
    internal fun requireCurrent() {
        if (!active.get()) throw SelectedInputException(SelectedInputFailure.STALE)
        try { checkCurrent() } catch (_: Exception) { active.set(false); throw SelectedInputException(SelectedInputFailure.STALE) }
        if (!active.get()) throw SelectedInputException(SelectedInputFailure.STALE)
    }
}

/** Identity of captured task bytes only, never an original or canonical AssetVersion. */
class PreparedSelectedInput internal constructor(
    internal val ticket: SelectedInputTicket,
    val representationId: String,
    val name: String,
    val byteCount: Long,
    internal val sha256: String,
) {
    val fidelity = SelectedInputFidelity.READABLE_REPRESENTATION
}

class PreparedInputPair internal constructor(internal val ticket: SelectedInputTicket, members: List<PreparedSelectedInput>) {
    val members: List<PreparedSelectedInput> = Collections.unmodifiableList(members.toList())
}

internal fun selectedPhotos(selected: List<MediaItem>): List<MediaItem> {
    if (selected.size != 2 || selected.any { it.isVideo } || selected.map { it.id }.toSet().size != 2 ||
        selected.map(::sourceUiToken).toSet().size != 2 || selected.map(::cleanupSourceReference).toSet().size != 2
    ) throw SelectedInputException(SelectedInputFailure.INVALID_SELECTION)
    return selected.toList()
}

internal fun selectedInputCount(current: Long, count: Int, limit: Long): Long {
    if (current < 0 || count < 0 || current > limit || count.toLong() > limit - current) throw SelectedInputException(SelectedInputFailure.SIZE_LIMIT)
    return current + count
}

/** Shared across all staging/store instances, including recreated gallery controllers. */
class SelectedInputOwnership {
    private class Holder(val ticket: SelectedInputTicket, var workers: Int = 0, var readers: Int = 0, var cleaning: Boolean = false, var failedReadClose: Boolean = false, var pair: PreparedInputPair? = null) {
        val resources = mutableListOf<SelectedInputResource>()
        var lane: AutoCloseable? = null
        var files: SelectedInputFileSystem? = null
    }
    private var holder: Holder? = null
    private var initializedRoot: String? = null

    @Synchronized internal fun acquire(root: String, selected: List<MediaItem>, check: () -> Unit): SelectedInputTicket {
        if (holder != null) throw SelectedInputException(SelectedInputFailure.BUSY)
        if (initializedRoot != root) throw SelectedInputException(SelectedInputFailure.NEEDS_CLEANUP)
        val ticket = SelectedInputTicket(root, selectedPhotos(selected), check)
        ticket.requireCurrent()
        holder = Holder(ticket)
        initializedRoot = null
        return ticket
    }
    @Synchronized internal fun owns(ticket: SelectedInputTicket): Boolean = holder?.ticket === ticket
    @Synchronized internal fun current(ticket: SelectedInputTicket): Boolean = try {
        if (holder?.ticket !== ticket) false else { ticket.requireCurrent(); true }
    } catch (_: SelectedInputException) { false }
    @Synchronized internal fun beginWork(ticket: SelectedInputTicket) {
        val current = holder?.takeIf { it.ticket === ticket } ?: throw SelectedInputException(SelectedInputFailure.STALE)
        ticket.requireCurrent()
        if (current.workers != 0 || current.pair != null || current.cleaning) throw SelectedInputException(SelectedInputFailure.BUSY)
        current.workers++
    }
    @Synchronized internal fun finishWork(ticket: SelectedInputTicket) {
        holder?.takeIf { it.ticket === ticket }?.let { check(it.workers > 0); it.workers--; releaseLane(it) }
    }
    @Synchronized internal fun publish(ticket: SelectedInputTicket, pair: PreparedInputPair) {
        val current = holder?.takeIf { it.ticket === ticket } ?: throw SelectedInputException(SelectedInputFailure.STALE)
        ticket.requireCurrent()
        if (pair.ticket !== ticket || pair.members.size != 2 || pair.members.any { it.ticket !== ticket } ||
            pair.members[0] === pair.members[1] || pair.members.map { it.representationId }.toSet().size != 2
        ) throw SelectedInputException(SelectedInputFailure.STALE)
        check(current.workers == 1 && current.pair == null)
        current.pair = pair
    }
    @Synchronized internal fun borrow(ticket: SelectedInputTicket, member: PreparedSelectedInput) {
        val current = holder?.takeIf { it.ticket === ticket } ?: throw SelectedInputException(SelectedInputFailure.STALE)
        ticket.requireCurrent()
        if (current.cleaning || current.pair?.members?.none { it === member } != false) throw SelectedInputException(SelectedInputFailure.STALE)
        if (current.readers >= 2) throw SelectedInputException(SelectedInputFailure.BUSY)
        current.readers++
    }
    @Synchronized internal fun releaseRead(ticket: SelectedInputTicket) {
        holder?.takeIf { it.ticket === ticket }?.let { check(it.readers > 0); it.readers--; if (it.readers == 0) it.failedReadClose = false }
    }
    @Synchronized internal fun failedReadClose(ticket: SelectedInputTicket) { holder?.takeIf { it.ticket === ticket }?.failedReadClose = true }
    @Synchronized internal fun fileSystem(ticket: SelectedInputTicket, candidate: SelectedInputFileSystem): SelectedInputFileSystem {
        val current = holder?.takeIf { it.ticket === ticket } ?: throw SelectedInputException(SelectedInputFailure.STALE)
        return current.files ?: candidate.also { current.files = it }
    }
    @Synchronized internal fun attachLane(ticket: SelectedInputTicket, lane: AutoCloseable) {
        val current = holder?.takeIf { it.ticket === ticket } ?: throw SelectedInputException(SelectedInputFailure.STALE)
        check(current.workers == 1 && current.lane == null)
        current.lane = lane
    }
    @Synchronized internal fun track(ticket: SelectedInputTicket, close: () -> Unit, afterClose: () -> Unit = {}): SelectedInputResource {
        val current = holder?.takeIf { it.ticket === ticket } ?: throw SelectedInputException(SelectedInputFailure.STALE)
        return SelectedInputResource(close) { resource ->
            synchronized(this) {
                current.resources.remove(resource)
                afterClose()
                releaseLane(current)
            }
        }.also { current.resources += it }
    }
    @Synchronized internal fun failedResources(ticket: SelectedInputTicket): List<SelectedInputResource> =
        holder?.takeIf { it.ticket === ticket }?.resources?.toList().orEmpty()
    private fun releaseLane(current: Holder) {
        if (current.workers == 0 && current.resources.isEmpty()) {
            current.lane?.close()
            current.lane = null
        }
    }
    @Synchronized internal fun maintenance(root: String, requested: SelectedInputTicket?): Pair<SelectedInputTicket?, SelectedInputCleanupResult> {
        val old = holder
        if (requested != null && old?.ticket !== requested) return null to SelectedInputCleanupResult.CLEAN
        if (old != null && (old.ticket.rootKey != root || old.ticket.isActive || old.workers > 0 || (old.readers > 0 && old.resources.none { it.failed }) || old.resources.any { !it.failed } || old.cleaning)) {
            return null to if (old.failedReadClose) SelectedInputCleanupResult.FAILED else SelectedInputCleanupResult.BUSY
        }
        val next = old ?: Holder(SelectedInputTicket(root, emptyList()) {})
        holder = next
        next.cleaning = true; next.workers = 1
        initializedRoot = null
        return next.ticket to SelectedInputCleanupResult.BUSY
    }
    @Synchronized internal fun finishMaintenance(ticket: SelectedInputTicket, clean: Boolean) {
        val current = holder?.takeIf { it.ticket === ticket } ?: return
        check(current.cleaning && current.workers == 1)
        if (clean) check(current.readers == 0 && current.resources.isEmpty())
        current.cleaning = false; current.workers = 0
        releaseLane(current)
        if (clean) { holder = null; initializedRoot = ticket.rootKey }
    }
}

internal val selectedInputProcessOwnership = SelectedInputOwnership()

/** A close exception is uncertainty, including when use() suppresses it under another failure. */
internal class SelectedInputResource(private val closeOwned: () -> Unit, private val released: (SelectedInputResource) -> Unit) : AutoCloseable {
    @Volatile var failed: Boolean = false
        private set
    private var closed = false
    @Synchronized override fun close() {
        if (closed) return
        try { closeOwned() } catch (_: Exception) { failed = true; throw SelectedInputException(SelectedInputFailure.IO) }
        closed = true
        released(this)
    }
}
