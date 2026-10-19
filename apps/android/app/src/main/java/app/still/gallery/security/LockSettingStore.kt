package app.still.gallery.security

data class StoredLockSetting(val enabled: Boolean, val needsRecovery: Boolean = false)

/** Storage for one local setting. The marker records uncertain writes, never authentication. */
internal interface LockSettingStorage {
    fun hasPendingWrite(): Boolean
    fun read(): ByteArray?
    fun markPendingWrite()
    fun write(record: ByteArray)
    fun clearPendingWrite()
}

/** Strict records and a fail-closed transaction fence protect interrupted/failed disables. */
internal class LockSettingStore(private val storage: LockSettingStorage) {
    fun load(): StoredLockSetting = try {
        if (storage.hasPendingWrite()) StoredLockSetting(enabled = true, needsRecovery = true)
        else when (val record = storage.read()) {
            null -> StoredLockSetting(enabled = false)
            else -> when {
                record.contentEquals(ON) -> StoredLockSetting(enabled = true)
                record.contentEquals(OFF) -> StoredLockSetting(enabled = false)
                else -> StoredLockSetting(enabled = true, needsRecovery = true)
            }
        }
    } catch (_: Exception) {
        StoredLockSetting(enabled = true, needsRecovery = true)
    }

    fun persist(enabled: Boolean): Boolean {
        val previous = load()
        try {
            // Publish a durable locked-on fence before touching the enabled record.
            storage.markPendingWrite()
        } catch (_: Exception) {
            return false
        }
        return try {
            val record = if (enabled) ON else OFF
            storage.write(record)
            if (storage.read()?.contentEquals(record) != true) throw IllegalStateException("Lock setting verification failed")
            // No fallible operation may follow removal of this transaction fence.
            storage.clearPendingWrite()
            true
        } catch (_: Exception) {
            try { storage.write(if (previous.enabled) ON else OFF) } catch (_: Exception) { /* Fence remains authoritative. */ }
            false
        }
    }

    companion object {
        private val ON = "still-app-lock-v1:on\n".toByteArray(Charsets.UTF_8)
        private val OFF = "still-app-lock-v1:off\n".toByteArray(Charsets.UTF_8)
    }
}
