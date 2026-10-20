package app.still.gallery.inputs

import app.still.gallery.media.MediaItem
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

interface SelectedInputSink : AutoCloseable {
    val stream: OutputStream
    fun sync()
}
data class SelectedInputFileInfo(val size: Long, val key: Any)

/** Trusted filesystem seam; names are generated children, never source paths. */
interface SelectedInputFileSystem : AutoCloseable {
    override fun close() {}
    fun settle() {}
    val hasUnsettledHandles: Boolean get() = false
    fun initialize()
    fun names(): List<String>
    fun create(name: String): SelectedInputSink
    fun open(name: String, expected: SelectedInputFileInfo? = null): InputStream
    fun info(name: String): SelectedInputFileInfo
    fun remove(name: String)
    fun syncDirectory()
}

/** No recursive traversal, following links, replacement, or original-file access. */
class NioSelectedInputFileSystem(root: Path) : SelectedInputFileSystem {
    private val root = root.toAbsolutePath().normalize()
    private var directory: SecureDirectoryStream<Path>? = null
    private var directoryKey: Any? = null
    private var pendingDirectory: java.nio.file.DirectoryStream<Path>? = null
    private val pendingHandles = mutableListOf<AutoCloseable>()
    private var closing = false
    private fun <T : AutoCloseable, R> useOwned(handle: T, body: (T) -> R): R {
        pendingHandles += handle
        try { return body(handle) } finally { handle.close(); pendingHandles.remove(handle) }
    }
    private fun directory(): SecureDirectoryStream<Path> = directory ?: throw IOException("Comparison cache is not open")
    @Synchronized override fun initialize() {
        settle()
        if (directory != null) return
        // Host seam requires a caller-created private directory; Android creates via a held parent FD.
        val before = Files.readAttributes(root, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        if (!before.isDirectory || before.isSymbolicLink || before.fileKey() == null) throw IOException("Unsupported comparison cache root")
        val opened = Files.newDirectoryStream(root)
        pendingDirectory = opened
        if (opened !is SecureDirectoryStream<Path>) throw IOException("Directory anchoring unavailable")
        val attributes = opened.getFileAttributeView(BasicFileAttributeView::class.java).readAttributes()
        if (attributes.fileKey() != before.fileKey() || (directoryKey != null && directoryKey != attributes.fileKey())) {
            throw IOException("Comparison cache root changed")
        }
        directoryKey = attributes.fileKey()
        directory = opened
        pendingDirectory = null
    }
    private fun child(name: String): Path {
        if (!ownedName(name)) throw IOException("Unexpected comparison cache entry")
        return root.fileSystem.getPath(name)
    }
    override fun names(): List<String> {
        val names = mutableListOf<String>()
        useOwned(directory().newDirectoryStream(root.fileSystem.getPath("."), NOFOLLOW_LINKS)) { entries ->
            for (entry in entries) {
                if (names.size >= 256) throw IOException("Unexpected comparison cache entries")
                names += entry.fileName.toString()
            }
        }
        names.forEach { info(it) }
        return names
    }
    override fun info(name: String): SelectedInputFileInfo {
        val attributes = directory().getFileAttributeView(child(name), BasicFileAttributeView::class.java, NOFOLLOW_LINKS).readAttributes()
        if (!attributes.isRegularFile || attributes.isSymbolicLink || attributes.fileKey() == null) throw IOException("Unsupported comparison cache entry")
        // Host-only Unix probe; mutations remain relative to the pinned directory.
        if (Files.readAttributes(root, BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey() != directoryKey ||
            Files.readAttributes(root.resolve(name), BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey() != attributes.fileKey() ||
            (Files.getAttribute(root.resolve(name), "unix:nlink", NOFOLLOW_LINKS) as? Number)?.toLong() != 1L
        ) throw IOException("Unsupported comparison cache entry")
        return SelectedInputFileInfo(attributes.size(), attributes.fileKey())
    }
    override fun create(name: String): SelectedInputSink {
        val opened = directory().newByteChannel(child(name), setOf(CREATE_NEW, WRITE, NOFOLLOW_LINKS))
        val channel = opened as? FileChannel ?: run {
            useOwned(opened) { throw IOException("File sync unavailable") }
        }
        return object : SelectedInputSink {
            override val stream: OutputStream = Channels.newOutputStream(channel)
            override fun sync() { channel.force(true) }
            override fun close() { stream.close() }
        }
    }
    override fun open(name: String, expected: SelectedInputFileInfo?): InputStream {
        val before = info(name)
        if (expected != null && expected != before) throw IOException("Comparison cache entry changed")
        val channel = directory().newByteChannel(child(name), setOf(READ, NOFOLLOW_LINKS))
        try {
            if (info(name) != before || channel.size() != before.size) throw IOException("Comparison cache entry changed")
            return Channels.newInputStream(channel)
        } catch (failure: Exception) { useOwned(channel) { throw failure } }
    }
    override fun remove(name: String) {
        try { info(name); directory().deleteFile(child(name)) } catch (_: NoSuchFileException) { /* Cache may be evicted. */ }
    }
    override fun syncDirectory() {
        // The pinned stream anchors mutations. The sync handle must match that pinned inode.
        val before = Files.readAttributes(root, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        if (before.fileKey() != directoryKey || before.isSymbolicLink) throw IOException("Comparison cache root changed")
        useOwned(FileChannel.open(root, READ, NOFOLLOW_LINKS)) { channel ->
            if (Files.readAttributes(root, BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey() != directoryKey) throw IOException("Comparison cache root changed")
            channel.force(true)
        }
    }
    override val hasUnsettledHandles: Boolean get() = pendingDirectory != null || pendingHandles.isNotEmpty() || closing
    override fun settle() {
        for (handle in pendingHandles.toList()) { handle.close(); pendingHandles.remove(handle) }
        pendingDirectory?.close(); pendingDirectory = null
        if (closing) { directory?.close(); directory = null; closing = false }
    }
    @Synchronized override fun close() { closing = true; settle() }
    companion object {
        private val namePattern = Regex("rep-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.bin")
        internal fun ownedName(name: String): Boolean = namePattern.matches(name)
    }
}

internal class SelectedInputWrite(val id: String, val name: String, val sink: SelectedInputSink)

/** Temporary bytes only. Retention ownership outlives cancellation and open decoders. */
class SelectedInputStore(
    root: Path,
    private val ownership: SelectedInputOwnership = selectedInputProcessOwnership,
    private val files: SelectedInputFileSystem = NioSelectedInputFileSystem(root),
) {
    private data class Entry(val name: String, val info: SelectedInputFileInfo)
    private val rootKey = root.toAbsolutePath().normalize().toString()
    private val entries = ConcurrentHashMap<PreparedSelectedInput, Entry>()

    suspend fun initialize(): SelectedInputCleanupResult = cleanup(null)
    fun acquire(selected: List<MediaItem>, checkCurrent: () -> Unit): SelectedInputTicket = ownership.acquire(rootKey, selected, checkCurrent)
    fun isCurrent(ticket: SelectedInputTicket): Boolean = ownership.current(ticket)
    fun invalidate(ticket: SelectedInputTicket) { ticket.invalidate() }
    internal fun beginWork(ticket: SelectedInputTicket) { ownership.beginWork(ticket) }
    internal fun finishWork(ticket: SelectedInputTicket) { ownership.finishWork(ticket) }
    internal fun attachLane(ticket: SelectedInputTicket, lane: AutoCloseable) { ownership.attachLane(ticket, lane) }
    internal fun track(ticket: SelectedInputTicket, close: () -> Unit): SelectedInputResource = ownership.track(ticket, close)
    private fun requireOwned(ticket: SelectedInputTicket) {
        if (ticket.rootKey != rootKey || !ownership.owns(ticket)) throw SelectedInputException(SelectedInputFailure.STALE)
        ticket.requireCurrent()
    }
    internal fun beginFiles(ticket: SelectedInputTicket) {
        requireOwned(ticket)
        ownership.fileSystem(ticket, files).initialize()
        if (files.names().isNotEmpty()) throw SelectedInputException(SelectedInputFailure.NEEDS_CLEANUP)
    }
    internal fun create(ticket: SelectedInputTicket): SelectedInputWrite {
        requireOwned(ticket)
        val id = UUID.randomUUID().toString()
        val name = "rep-$id.bin"
        return SelectedInputWrite(id, name, files.create(name))
    }
    internal fun verify(ticket: SelectedInputTicket, output: SelectedInputWrite, source: MediaItem, count: Long, digest: String, check: () -> Unit): PreparedSelectedInput {
        check()
        files.syncDirectory()
        val before = files.info(output.name)
        if (before.size != count) throw SelectedInputException(SelectedInputFailure.IO)
        val hash = MessageDigest.getInstance("SHA-256")
        var actual = 0L
        val input = files.open(output.name, before)
        track(ticket, input::close).use {
            val buffer = ByteArray(SelectedInputBounds.BUFFER_BYTES)
            var stalled = 0
            while (true) {
                check()
                val length = input.read(buffer)
                check()
                if (length == -1) break
                if (length < -1 || length > buffer.size) throw SelectedInputException(SelectedInputFailure.IO)
                if (length == 0) { if (++stalled >= 8) throw SelectedInputException(SelectedInputFailure.IO); continue }
                stalled = 0
                actual = selectedInputCount(actual, length, SelectedInputBounds.MAX_INPUT_BYTES)
                hash.update(buffer, 0, length)
            }
        }
        check()
        if (actual != count || selectedInputHex(hash.digest()) != digest || files.info(output.name) != before) throw SelectedInputException(SelectedInputFailure.IO)
        val member = PreparedSelectedInput(ticket, output.id, source.name, count, digest)
        entries[member] = Entry(output.name, before)
        ticket.requireCurrent()
        return member
    }
    internal fun publish(ticket: SelectedInputTicket, pair: PreparedInputPair) { ownership.publish(ticket, pair) }

    /** Decoder must use/close the returned stream. No file paths escape this API. */
    fun openRead(ticket: SelectedInputTicket, member: PreparedSelectedInput): InputStream {
        val entry = entries[member] ?: throw SelectedInputException(SelectedInputFailure.STALE)
        ownership.borrow(ticket, member)
        var resource: SelectedInputResource? = null
        try {
            val opened = files.open(entry.name, entry.info)
            resource = ownership.track(ticket, opened::close) { ownership.releaseRead(ticket) }
            ticket.requireCurrent()
            return object : FilterInputStream(opened) {
                private fun check() { ticket.requireCurrent() }
                @Synchronized override fun read(): Int { check(); val result = super.read(); check(); return result }
                @Synchronized override fun read(buffer: ByteArray, offset: Int, length: Int): Int { check(); val result = `in`.read(buffer, offset, length); check(); return result }
                @Synchronized override fun skip(count: Long): Long { check(); val result = super.skip(count); check(); return result }
                @Synchronized override fun available(): Int { check(); return super.available() }
                @Synchronized override fun close() {
                    try { resource.close() } catch (failure: Exception) { ticket.invalidate(); throw failure }
                }
            }
        } catch (_: Exception) {
            if (resource == null) ownership.releaseRead(ticket) else {
                ticket.invalidate()
                resource.close()
            }
            throw SelectedInputException(if (ticket.isActive) SelectedInputFailure.IO else SelectedInputFailure.STALE)
        }
    }

    suspend fun closeAndCleanup(ticket: SelectedInputTicket): SelectedInputCleanupResult {
        ticket.invalidate()
        return cleanup(ticket)
    }
    private suspend fun cleanup(requested: SelectedInputTicket?): SelectedInputCleanupResult = withContext(NonCancellable + Dispatchers.IO) {
        val (ticket, result) = ownership.maintenance(rootKey, requested)
        if (ticket == null) return@withContext result
        var clean = false
        val retainedFiles = ownership.fileSystem(ticket, files)
        try {
            for (resource in ownership.failedResources(ticket)) resource.close()
            retainedFiles.settle()
            retainedFiles.initialize()
            val names = retainedFiles.names()
            names.forEach(retainedFiles::remove)
            retainedFiles.syncDirectory()
            if (retainedFiles.names().isNotEmpty()) return@withContext SelectedInputCleanupResult.FAILED
            retainedFiles.close()
            entries.clear()
            clean = true
            SelectedInputCleanupResult.CLEAN
        } catch (_: Exception) { SelectedInputCleanupResult.FAILED }
        finally { ownership.finishMaintenance(ticket, clean) }
    }
}

internal fun selectedInputHex(value: ByteArray): String = value.joinToString("") { "%02x".format(java.util.Locale.ROOT, it) }
