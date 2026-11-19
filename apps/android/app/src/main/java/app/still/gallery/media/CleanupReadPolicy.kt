package app.still.gallery.media

import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

object CleanupBounds {
    const val BUFFER_BYTES = 64 * 1024
    const val MAX_SOURCES = 50_000
    const val MAX_STREAM_BYTES = 16L * 1024 * 1024 * 1024
}

class CleanupScopeLimitException : IOException("Accessible media exceeds the scan bound")
class CleanupReadLimitException : IOException("Readable stream exceeds the scan bound")
class CleanupEmptyReadException : IOException("Readable stream is empty")
class CleanupSourceChangedException : IOException("The observed media changed")

data class CleanupDigest(val sha256: String, val byteCount: Long)

/** Checked independently of the configured stream limit, including Long overflow. */
fun cleanupCountBytes(current: Long, count: Int, limit: Long): Long {
    if (current < 0 || count < 0 || limit <= 0 || current > limit || count.toLong() > limit - current) throw CleanupReadLimitException()
    return current + count
}

/** No digest escapes before complete EOF AND successful close. No media bytes persist. */
fun readCleanupBytes(open: () -> InputStream, checkActive: () -> Unit, limit: Long = CleanupBounds.MAX_STREAM_BYTES): CleanupDigest {
    val digest = MessageDigest.getInstance("SHA-256")
    var count = 0L
    checkActive()
    open().use { stream ->
        val buffer = ByteArray(CleanupBounds.BUFFER_BYTES)
        var stalledReads = 0
        while (true) {
            checkActive()
            val length = stream.read(buffer)
            checkActive()
            if (length == -1) break
            if (length < -1 || length > buffer.size) throw IOException("Invalid readable stream length")
            if (length == 0) {
                if (++stalledReads >= 8) throw IOException("Readable stream made no progress")
                continue
            }
            stalledReads = 0
            count = cleanupCountBytes(count, length, limit)
            digest.update(buffer, 0, length)
        }
    }
    checkActive()
    if (count == 0L) throw CleanupEmptyReadException()
    return CleanupDigest(digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }, count)
}

/** One nonqueueing process-wide lane; a canceled blocked reader keeps its lease. */
class CleanupReadAdmission {
    private val holder = AtomicReference<Any?>(null)
    fun tryEnter(): Lease? {
        val identity = Any()
        return if (holder.compareAndSet(null, identity)) Lease { holder.compareAndSet(identity, null) } else null
    }
    class Lease internal constructor(private val release: () -> Unit) : AutoCloseable {
        override fun close() = release()
    }
}

val cleanupProcessReads = CleanupReadAdmission()

/** Provider aliases never become a second member. Conflicting aliases invalidate the scan. */
fun cleanupUniqueSources(items: List<MediaItem>): Pair<List<MediaItem>, Int> {
    val unique = linkedMapOf<String, MediaItem>()
    var aliases = 0
    for (item in items) {
        val key = cleanupSourceReference(item)
        val old = unique[key]
        if (old == null) unique[key] = item
        else {
            if (sourceUiToken(old) != sourceUiToken(item)) throw CleanupSourceChangedException()
            aliases++
        }
    }
    return unique.values.toList() to aliases
}

fun cleanupSourceReference(item: MediaItem): String = listOf(item.providerAuthority, item.volumeName, item.isVideo, item.externalHandle)
    .joinToString("|") { "${it.toString().length}:$it" }

/** This inexpensive prefilter deliberately omits singleton/unknown reported sizes. */
fun cleanupCandidates(items: List<MediaItem>): List<MediaItem> {
    val buckets = items.filter { it.bytes > 0 }.groupingBy { it.isVideo to it.bytes }.eachCount()
    return items.filter { it.bytes > 0 && buckets.getValue(it.isVideo to it.bytes) >= 2 }
}

fun cleanupSameSource(expected: MediaItem, observed: MediaItem?, modern: Boolean): Boolean =
    observed != null && sourceUiToken(expected) == sourceUiToken(observed) &&
        (!modern || (!expected.providerVersion.isNullOrBlank() && expected.providerGeneration != null &&
            expected.generationAdded != null && expected.generationModified != null))

/** Any modern generation advance invalidates an entire readable-byte scan. */
fun cleanupNamespaceUnchanged(beforeVersion: String?, beforeGeneration: Long?, afterVersion: String?, afterGeneration: Long?): Boolean =
    beforeVersion == afterVersion && beforeGeneration == afterGeneration

/** Unresolved old aggregate references are uncertain, never evidence of NotFavorite. */
fun cleanupFavoriteUnknown(item: MediaItem, saved: Set<String>, provenAliases: Map<String, String>): Boolean =
    saved.any { key ->
        val legacy = legacyFavoriteReference(key)
        legacy != null && legacy.key !in provenAliases && legacy.isVideo == item.isVideo && legacy.externalHandle == item.externalHandle && key != item.id
    }
