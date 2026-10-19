package app.still.gallery.security

import android.content.Context
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes

/** Contains only the opt-in lock setting; no credentials, auth results or sessions are stored. */
class AppLockPreferences(context: Context) {
    private val store = LockSettingStore(AtomicLockSettingStorage(context.applicationContext.filesDir))
    fun load(): StoredLockSetting = store.load()
    fun persist(enabled: Boolean): Boolean = store.persist(enabled)
}

private class AtomicLockSettingStorage(private val directory: File) : LockSettingStorage {
    private val recordFile = File(directory, "app-lock.setting")
    private val backupFile = File(directory, "app-lock.setting.bak")
    private val markerFile = File(directory, "app-lock.pending")
    private val atomic = AtomicFile(recordFile)

    override fun hasPendingWrite(): Boolean = existsStrict(markerFile)

    override fun read(): ByteArray? {
        if (!existsStrict(recordFile) && !existsStrict(backupFile)) return null
        return atomic.openRead().use { input ->
            val bytes = ByteArray(64)
            val count = input.read(bytes)
            if (count < 0) ByteArray(0)
            else if (input.read() != -1) throw IOException("Oversized lock setting")
            else bytes.copyOf(count)
        }
    }

    override fun markPendingWrite() {
        FileOutputStream(markerFile).use { output ->
            output.write(1)
            output.fd.sync()
        }
    }

    override fun write(record: ByteArray) {
        var output: FileOutputStream? = null
        try {
            output = atomic.startWrite()
            output.write(record)
            // AtomicFile.finishWrite is void; require an explicit successful sync first.
            output.fd.sync()
            atomic.finishWrite(output)
            output = null
        } catch (failure: Exception) {
            if (output != null) atomic.failWrite(output)
            throw failure
        }
    }

    override fun clearPendingWrite() {
        if (!markerFile.delete()) throw IOException("Lock setting fence was not removed")
    }

    override fun syncDirectory() {
        // O_DIRECTORY is not exposed by the public Android SDK. Validate the
        // opened descriptor instead; never follow a substituted final symlink.
        val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW, 0)
        try {
            if (!OsConstants.S_ISDIR(Os.fstat(descriptor).st_mode)) throw IOException("Invalid lock setting directory")
            Os.fsync(descriptor)
        } finally { Os.close(descriptor) }
    }

    private fun existsStrict(file: File): Boolean = try {
        val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (!attributes.isRegularFile) throw IOException("Invalid lock setting file")
        true
    } catch (_: NoSuchFileException) {
        false
    }
}
