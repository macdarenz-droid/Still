package app.still.gallery.security

import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockSettingStoreTest {
    @Test fun firstRunIsOffAndAuthenticatedSettingWritesSurviveReopen() {
        val disk = FakeStorage()
        assertFalse(LockSettingStore(disk).load().enabled)
        assertTrue(LockSettingStore(disk).persist(true))
        assertTrue(LockSettingStore(disk).load().enabled)
        assertTrue(LockSettingStore(disk).persist(false))
        assertFalse(LockSettingStore(disk).load().enabled)
        assertFalse(disk.pending)
    }

    @Test fun corruptOrUnreadableExistingSettingStartsLocked() {
        val disk = FakeStorage().apply { record = "false".toByteArray() }
        assertTrue(LockSettingStore(disk).load().enabled)
        assertTrue(LockSettingStore(disk).load().needsRecovery)
        disk.failRead = true
        assertTrue(LockSettingStore(disk).load().enabled)
    }

    @Test fun failedDisableRetainsProtectionAcrossRecreationEvenIfRollbackFails() {
        val disk = FakeStorage()
        val store = LockSettingStore(disk)
        assertTrue(store.persist(true))
        disk.failAfterMutation = true
        assertFalse(store.persist(false))
        // Reproduces a partial disable: the record now says OFF and rollback
        // also failed. Without the separate fence a recreated app would open.
        assertTrue(disk.record!!.contentEquals("still-app-lock-v1:off\n".toByteArray()))
        assertTrue(disk.pending)
        assertTrue(LockSettingStore(disk).load().enabled)
    }

    @Test fun readbackFailureAfterOffWasWrittenCannotReopenGallery() {
        val disk = FakeStorage()
        val store = LockSettingStore(disk)
        assertTrue(store.persist(true))
        disk.failReadAfterWrite = true
        assertFalse(store.persist(false))
        assertTrue(disk.pending)
        assertTrue(LockSettingStore(disk).load().enabled)
    }

    @Test fun failureToRemoveFenceIsNotReportedAsSuccessfulDisable() {
        val disk = FakeStorage()
        val store = LockSettingStore(disk)
        assertTrue(store.persist(true))
        disk.failClear = true
        assertFalse(store.persist(false))
        assertTrue(LockSettingStore(disk).load().enabled)
        disk.failClear = false
        assertTrue(store.persist(false))
        assertFalse(LockSettingStore(disk).load().enabled)
    }

    @Test fun interruptedFirstEnableHasNoRestorableAuthenticationAndStaysLocked() {
        val disk = FakeStorage().apply { pending = true }
        val setting = LockSettingStore(disk).load()
        assertTrue(setting.enabled)
        assertTrue(setting.needsRecovery)
        assertTrue(LockSettingStore(disk).persist(true))
        assertFalse(LockSettingStore(disk).load().needsRecovery)
    }

    @Test fun markerCreationFailureDoesNotChangePreviouslyEnabledRecord() {
        val disk = FakeStorage()
        val store = LockSettingStore(disk)
        assertTrue(store.persist(true))
        disk.failMark = true
        assertFalse(store.persist(false))
        assertTrue(LockSettingStore(disk).load().enabled)
    }

    private class FakeStorage : LockSettingStorage {
        var record: ByteArray? = null
        var pending = false
        var failRead = false
        var failWrites = false
        var failAfterMutation = false
        var failClear = false
        var failMark = false
        var failReadAfterWrite = false
        override fun hasPendingWrite() = pending
        override fun read(): ByteArray? {
            if (failRead) throw IOException("read failure")
            return record?.copyOf()
        }
        override fun markPendingWrite() {
            if (failMark) throw IOException("marker failure")
            pending = true
        }
        override fun write(record: ByteArray) {
            if (failWrites) throw IOException("write failure")
            this.record = record.copyOf()
            if (failAfterMutation) {
                failAfterMutation = false
                failWrites = true // Both this uncertain write and rollback fail.
                throw IOException("write changed bytes before failing")
            }
            if (failReadAfterWrite) failRead = true
        }
        override fun clearPendingWrite() {
            if (failClear) throw IOException("marker removal failure")
            pending = false
        }
    }
}
