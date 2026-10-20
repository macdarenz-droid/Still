package app.still.gallery.cleanup

import java.util.Collections

enum class ProtectionStatus { READY, UNKNOWN }
enum class ProtectionWriteStatus { SAVED, CONFLICT, UNKNOWN }

/** Keys in UNKNOWN snapshots are not an authoritative empty protection set. */
data class ProtectionSnapshot(val status: ProtectionStatus, val revision: Long, val keys: Set<ReadableContentKey>) {
    companion object {
        fun unknown() = ProtectionSnapshot(ProtectionStatus.UNKNOWN, 0, emptySet())
    }
}
data class ProtectionWriteResult(val status: ProtectionWriteStatus, val snapshot: ProtectionSnapshot)

internal interface ProtectionStorage {
    fun hasPendingWrite(): Boolean
    fun read(): ByteArray?
    fun markPendingWrite()
    fun write(record: ByteArray)
    fun syncDirectory()
    fun clearPendingWrite()
}

/** Strict bounded ASCII format. Invalid/unknown records are preserved, never rewritten as empty. */
internal object ProtectionRecordCodec {
    const val MAX_KEYS = 4096
    const val MAX_BYTES = 512 * 1024
    private const val HEADER = "still-readable-protection-v1"

    fun decode(bytes: ByteArray): ProtectionSnapshot {
        require(bytes.size <= MAX_BYTES) { "Protection record is too large" }
        require(bytes.all { it.toInt() in 10..126 && (it.toInt() == 10 || it.toInt() >= 32) }) { "Protection record is not canonical ASCII" }
        val lines = bytes.toString(Charsets.US_ASCII).split('\n')
        require(lines.size >= 3 && lines.last().isEmpty() && lines[0] == HEADER) { "Unsupported protection record" }
        val revisionText = lines[1].removePrefix("revision:")
        require(lines[1].startsWith("revision:") && revisionText.matches(Regex("[1-9][0-9]{0,18}")))
        val revision = requireNotNull(revisionText.toLongOrNull())
        val entries = lines.drop(2).dropLast(1)
        require(entries.size <= MAX_KEYS)
        val keys = entries.map { line ->
            val fields = line.split(':')
            require(fields.size == 3 && fields[2].matches(Regex("[1-9][0-9]{0,18}"))) { "Invalid protection entry" }
            ReadableContentKey(fields[1], requireNotNull(fields[2].toLongOrNull()), fields[0])
        }
        require(keys.toSet().size == keys.size) { "Repeated protection entry" }
        require(entries == entries.sorted()) { "Protection entries are not canonical" }
        return ProtectionSnapshot(ProtectionStatus.READY, revision, Collections.unmodifiableSet(keys.toSet()))
    }

    fun encode(revision: Long, keys: Set<ReadableContentKey>): ByteArray {
        require(revision > 0 && keys.size <= MAX_KEYS)
        val entries = keys.map { "${it.algorithm}:${it.sha256}:${it.byteCount}" }.sorted()
        val text = "$HEADER\nrevision:$revision\n" + entries.joinToString(separator = "\n", postfix = if (entries.isEmpty()) "" else "\n")
        return text.toByteArray(Charsets.US_ASCII).also { require(it.size <= MAX_BYTES) }
    }
}

/**
 * One coordinator covers the WHOLE read/CAS/write across all instances pointing
 * at this store. Android supplies a process-wide lock (single-process app).
 * No pruning, corruption repair, rollback overwrite or automatic fence removal.
 */
internal class ProtectionStore(private val storage: ProtectionStorage, private val coordinator: Any) {
    fun load(): ProtectionSnapshot = synchronized(coordinator) { loadUncoordinated() }

    fun setProtected(
        key: ReadableContentKey,
        protected: Boolean,
        expectedRevision: Long,
        checkAdmitted: () -> Unit = {},
    ): ProtectionWriteResult = synchronized(coordinator) {
        require(expectedRevision >= 0)
        checkAdmitted()
        val prior = loadUncoordinated()
        if (prior.status != ProtectionStatus.READY) return@synchronized ProtectionWriteResult(ProtectionWriteStatus.UNKNOWN, prior)
        if (expectedRevision != prior.revision) return@synchronized ProtectionWriteResult(ProtectionWriteStatus.CONFLICT, prior)
        val keys = prior.keys.toMutableSet()
        if (protected) keys.add(key) else keys.remove(key)
        if (keys == prior.keys) return@synchronized ProtectionWriteResult(ProtectionWriteStatus.SAVED, prior)
        if (prior.revision == Long.MAX_VALUE) return@synchronized ProtectionWriteResult(ProtectionWriteStatus.UNKNOWN, ProtectionSnapshot.unknown())
        val record = try { ProtectionRecordCodec.encode(prior.revision + 1, keys) }
        catch (_: Exception) { return@synchronized ProtectionWriteResult(ProtectionWriteStatus.UNKNOWN, ProtectionSnapshot.unknown()) }
        // Recheck after queueing/loading, at the admission point immediately
        // before the tiny synchronous replacement. Do not interrupt after this.
        checkAdmitted()
        try {
            storage.markPendingWrite()
            storage.syncDirectory()
            storage.write(record)
            storage.syncDirectory()
            val actual = storage.read()
            check(actual != null && actual.contentEquals(record)) { "Protection readback failed" }
            val next = ProtectionRecordCodec.decode(actual)
            // No fallible disk operation follows removal of the uncertainty fence.
            storage.clearPendingWrite()
            ProtectionWriteResult(ProtectionWriteStatus.SAVED, next)
        } catch (_: Exception) {
            ProtectionWriteResult(ProtectionWriteStatus.UNKNOWN, ProtectionSnapshot.unknown())
        }
    }

    private fun loadUncoordinated(): ProtectionSnapshot = try {
        if (storage.hasPendingWrite()) ProtectionSnapshot.unknown()
        else storage.read()?.let(ProtectionRecordCodec::decode)
            ?: ProtectionSnapshot(ProtectionStatus.READY, 0, emptySet())
    } catch (_: Exception) { ProtectionSnapshot.unknown() }
}
