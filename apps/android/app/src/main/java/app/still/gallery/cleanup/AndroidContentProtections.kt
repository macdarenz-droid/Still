package app.still.gallery.cleanup

import android.content.Context
import android.os.Build
import android.os.Looper
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes

/**
 * IO-only on-phone content protection. Applies to ALL freshly hashed matching
 * readable copies, including copies not currently present. No source URI, media
 * bytes, original/version guarantee or cloud/deletion capability is stored.
 */
class AndroidContentProtections(context: Context) {
    private val store = ProtectionStore(AtomicProtectionStorage(context.applicationContext.filesDir), COORDINATOR)

    fun load(): ProtectionSnapshot {
        requireWorkerThread()
        return store.load()
    }

    fun setProtected(key: ReadableContentKey, protected: Boolean, expectedRevision: Long, checkAdmitted: () -> Unit): ProtectionWriteResult {
        requireWorkerThread()
        return store.setProtected(key, protected, expectedRevision, checkAdmitted)
    }

    private fun requireWorkerThread() {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Read/write content protections on an IO dispatcher" }
    }

    companion object { private val COORDINATOR = Any() }
}

private class AtomicProtectionStorage(private val directory: File) : ProtectionStorage {
    private val recordFile = File(directory, "readable-content.protections")
    private val backupFile = File(directory, "readable-content.protections.bak")
    private val markerFile = File(directory, "readable-content.protections.pending")
    private val replacementFile = File(directory, "readable-content.protections.new")
    private val atomic = AtomicFile(recordFile)

    override fun hasPendingWrite(): Boolean = existsStrict(markerFile)

    override fun read(): ByteArray? {
        // Uncertain sidecars are not silently restored, discarded or reset.
        if (existsStrict(backupFile) || existsStrict(replacementFile)) throw IOException("Uncertain protection replacement")
        if (!existsStrict(recordFile)) return null
        return recordFile.inputStream().use { input ->
            val result = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) throw IOException("Protection record read made no progress")
                if (result.size() > ProtectionRecordCodec.MAX_BYTES - count) throw IOException("Protection record is too large")
                result.write(buffer, 0, count)
            }
            result.toByteArray()
        }
    }

    override fun markPendingWrite() {
        FileOutputStream(markerFile).use { output -> output.write(1); output.fd.sync() }
    }

    override fun write(record: ByteArray) {
        var output: FileOutputStream? = null
        try {
            output = atomic.startWrite()
            output.write(record)
            output.fd.sync()
            atomic.finishWrite(output)
            output = null
        } catch (failure: Exception) {
            if (output != null) atomic.failWrite(output)
            throw failure
        }
    }

    override fun syncDirectory() {
        val closeOnExec = if (Build.VERSION.SDK_INT >= 27) OsConstants.O_CLOEXEC else 0
        val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or closeOnExec, 0)
        try {
            if (!OsConstants.S_ISDIR(Os.fstat(descriptor).st_mode)) throw IOException("Invalid protection directory")
            Os.fsync(descriptor)
        } finally { Os.close(descriptor) }
    }

    override fun clearPendingWrite() {
        if (!markerFile.delete()) throw IOException("Protection fence was not removed")
    }

    private fun existsStrict(file: File): Boolean = try {
        val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (!attributes.isRegularFile) throw IOException("Invalid protection file")
        true
    } catch (_: NoSuchFileException) { false }
}
