package app.still.gallery.inputs

import android.content.Context
import app.still.gallery.media.CleanupSourceReader
import app.still.gallery.media.MediaItem
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Lazy filesystem initialization stays on IO. No URI/path is exposed to the decoder. */
class AndroidSelectedInputs(context: Context, private val reader: CleanupSourceReader) {
    private val application = context.applicationContext
    private val storeDelegate = lazy { application.cacheDir.toPath().resolve("selected-inputs-v1").let { root -> SelectedInputStore(root, files = AndroidSelectedInputFileSystem(root)) } }
    private val store: SelectedInputStore get() = storeDelegate.value

    suspend fun initialize(): SelectedInputCleanupResult = withContext(Dispatchers.IO) { store.initialize() }
    fun acquire(selected: List<MediaItem>, checkCurrent: () -> Unit): SelectedInputTicket {
        if (!storeDelegate.isInitialized()) throw SelectedInputException(SelectedInputFailure.NEEDS_CLEANUP)
        return store.acquire(selected, checkCurrent)
    }
    suspend fun prepare(ticket: SelectedInputTicket, favorites: Set<String>, onProgress: (SelectedInputProgress) -> Unit = {}): PreparedInputPair =
        SelectedInputPreparation(reader, store).prepare(ticket, favorites, onProgress)

    /** Memory/live-authority check; provider continuity is checked by preparation. */
    fun isCurrent(ticket: SelectedInputTicket): Boolean = storeDelegate.isInitialized() && store.isCurrent(ticket)
    fun invalidate(ticket: SelectedInputTicket) { ticket.invalidate() }
    fun openRead(ticket: SelectedInputTicket, member: PreparedSelectedInput): InputStream {
        if (!storeDelegate.isInitialized()) throw SelectedInputException(SelectedInputFailure.STALE)
        return store.openRead(ticket, member)
    }
    suspend fun closeAndCleanup(ticket: SelectedInputTicket): SelectedInputCleanupResult {
        ticket.invalidate()
        return if (storeDelegate.isInitialized()) store.closeAndCleanup(ticket) else SelectedInputCleanupResult.CLEAN
    }
}

/** Linux/Android descriptor anchoring; no child lookup traverses the mutable cache pathname. */
internal class AndroidSelectedInputFileSystem(private val root: java.nio.file.Path) : SelectedInputFileSystem {
    private data class Inode(val device: Long, val inode: Long)
    private var directory: java.io.FileDescriptor? = null
    private var anchor: android.os.ParcelFileDescriptor? = null
    private var directoryInode: Inode? = null
    private var parentDirectory: java.io.FileDescriptor? = null
    private var parentAnchor: android.os.ParcelFileDescriptor? = null
    private val unsettled = mutableListOf<java.io.FileDescriptor>()
    private var closing = false
    private fun closeDescriptor(descriptor: java.io.FileDescriptor) { if (descriptor.valid()) android.system.Os.close(descriptor) }
    private fun identity(stat: android.system.StructStat) = Inode(stat.st_dev, stat.st_ino)
    private fun flags(value: Int): Int = value or android.system.OsConstants.O_NOFOLLOW or
        (if (android.os.Build.VERSION.SDK_INT >= 27) android.system.OsConstants.O_CLOEXEC else 0)
    override fun initialize() {
        settle()
        if (anchor != null) return
        if (directory != null || parentDirectory != null || parentAnchor != null) { closing = true; settle() }
        val parent = root.parent ?: throw java.io.IOException("Invalid comparison cache root")
        val parentBefore = android.system.Os.lstat(parent.toString())
        if (!android.system.OsConstants.S_ISDIR(parentBefore.st_mode)) throw java.io.IOException("Unsupported comparison cache parent")
        val parentOpened = android.system.Os.open(parent.toString(), flags(android.system.OsConstants.O_RDONLY), 0)
        parentDirectory = parentOpened
        val parentActual = android.system.Os.fstat(parentOpened)
        if (!android.system.OsConstants.S_ISDIR(parentActual.st_mode) || identity(parentActual) != identity(parentBefore)) throw java.io.IOException("Comparison cache parent changed")
        parentAnchor = android.os.ParcelFileDescriptor.dup(parentOpened)
        val rootPath = "/proc/self/fd/${parentAnchor!!.fd}/${root.fileName}"
        try { android.system.Os.mkdir(rootPath, 448) } catch (failure: android.system.ErrnoException) {
            if (failure.errno != android.system.OsConstants.EEXIST) throw failure
        }
        val before = android.system.Os.lstat(rootPath)
        if (!android.system.OsConstants.S_ISDIR(before.st_mode)) throw java.io.IOException("Unsupported comparison cache root")
        val opened = android.system.Os.open(rootPath, flags(android.system.OsConstants.O_RDONLY), 0)
        directory = opened // retain even if a subsequent check/dup fails
        val actual = android.system.Os.fstat(opened)
        if (!android.system.OsConstants.S_ISDIR(actual.st_mode) || identity(actual) != identity(before) ||
            (directoryInode != null && directoryInode != identity(actual))) throw java.io.IOException("Comparison cache root changed")
        directoryInode = identity(actual)
        anchor = android.os.ParcelFileDescriptor.dup(opened)
    }
    private fun anchored(name: String? = null): String {
        val held = anchor ?: throw java.io.IOException("Comparison cache is not open")
        if (name != null && !NioSelectedInputFileSystem.ownedName(name)) throw java.io.IOException("Unexpected comparison cache entry")
        return "/proc/self/fd/${held.fd}" + if (name == null) "" else "/$name"
    }
    private fun checked(stat: android.system.StructStat): SelectedInputFileInfo {
        if (!android.system.OsConstants.S_ISREG(stat.st_mode) || stat.st_nlink != 1L || stat.st_size < 0) throw java.io.IOException("Unsupported comparison cache entry")
        return SelectedInputFileInfo(stat.st_size, identity(stat))
    }
    override fun names(): List<String> {
        val names = java.io.File(anchored()).list()?.toList() ?: throw java.io.IOException("Comparison cache enumeration failed")
        if (names.size > 256) throw java.io.IOException("Unexpected comparison cache entries")
        names.forEach { info(it) }
        return names
    }
    override fun info(name: String): SelectedInputFileInfo = checked(android.system.Os.lstat(anchored(name)))
    override fun create(name: String): SelectedInputSink {
        val descriptor = android.system.Os.open(anchored(name), flags(android.system.OsConstants.O_WRONLY or android.system.OsConstants.O_CREAT or android.system.OsConstants.O_EXCL), 384)
        try {
            checked(android.system.Os.fstat(descriptor))
            val output = java.io.FileOutputStream(descriptor)
            return object : SelectedInputSink {
                override val stream: java.io.OutputStream = output
                override fun sync() { android.system.Os.fsync(descriptor) }
                override fun close() { closeDescriptor(descriptor) }
            }
        } catch (failure: Exception) { unsettled += descriptor; throw failure }
    }
    override fun open(name: String, expected: SelectedInputFileInfo?): InputStream {
        val descriptor = android.system.Os.open(anchored(name), flags(android.system.OsConstants.O_RDONLY), 0)
        try {
            val actual = checked(android.system.Os.fstat(descriptor))
            if (expected != null && actual != expected) throw java.io.IOException("Comparison cache entry changed")
            return object : java.io.FilterInputStream(java.io.FileInputStream(descriptor)) {
                override fun close() { closeDescriptor(descriptor) }
            }
        } catch (failure: Exception) { unsettled += descriptor; throw failure }
    }
    override fun remove(name: String) {
        try { info(name); android.system.Os.remove(anchored(name)) } catch (failure: android.system.ErrnoException) {
            if (failure.errno != android.system.OsConstants.ENOENT) throw failure
        }
    }
    override fun syncDirectory() { android.system.Os.fsync(directory ?: throw java.io.IOException("Comparison cache is not open")) }
    override val hasUnsettledHandles: Boolean get() = unsettled.isNotEmpty() || ((directory != null || parentDirectory != null) && anchor == null) || closing
    override fun settle() {
        val pending = unsettled.toList()
        for (descriptor in pending) { closeDescriptor(descriptor); unsettled.remove(descriptor) }
        if (closing) {
            anchor?.let { if (it.fileDescriptor.valid()) it.close() }; anchor = null
            directory?.let(::closeDescriptor); directory = null
            parentAnchor?.let { if (it.fileDescriptor.valid()) it.close() }; parentAnchor = null
            parentDirectory?.let(::closeDescriptor); parentDirectory = null
            closing = false
        }
    }
    override fun close() {
        closing = true
        settle()
    }
}
