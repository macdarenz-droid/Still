package app.still.gallery.security

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Models distinct durable file bytes and directory entries; not real power-loss evidence. */
class LockSettingDurabilityTest {
    @Test fun markerDirectorySyncFailurePreventsRecordMutation() {
        val disk = DurabilityModel(initiallyEnabled = true).apply { failSyncAt = 1 }
        assertFalse(LockSettingStore(disk).persist(false))
        assertEquals(0, disk.recordWrites)
        disk.crashAndReopen()
        assertTrue(LockSettingStore(disk).load().enabled)
    }

    @Test fun failedReplacementSyncRetainsPublishedFenceEvenWhenOffBytesSurvive() {
        val disk = DurabilityModel(initiallyEnabled = true).apply {
            recordMayPersistEarly = true
            failSyncAt = 2
        }
        assertFalse(LockSettingStore(disk).persist(false))
        disk.crashAndReopen()
        assertTrue(disk.read().contentEquals(record(false)))
        assertTrue(LockSettingStore(disk).load().enabled)
        assertTrue(LockSettingStore(disk).load().needsRecovery)
    }

    @Test fun abruptDisableCrashCannotPreserveOffWhileLosingTheNewFence() {
        val disk = DurabilityModel(initiallyEnabled = true).apply {
            recordMayPersistEarly = true
            crashDuringRecordWrite = true
        }
        assertThrows(SimulatedPowerLoss::class.java) { LockSettingStore(disk).persist(false) }
        disk.crashAndReopen()
        assertTrue(disk.read().contentEquals(record(false)))
        assertTrue(LockSettingStore(disk).load().enabled)
    }

    @Test fun reportedEnableSurvivesRecordRenameLossAndPersistedFenceDeletion() {
        val disk = DurabilityModel(initiallyEnabled = false)
        assertTrue(LockSettingStore(disk).persist(true))
        disk.crashAndReopen()
        assertFalse(disk.hasPendingWrite())
        assertTrue(LockSettingStore(disk).load().enabled)
    }

    private class SimulatedPowerLoss : Error()

    private class DurabilityModel(initiallyEnabled: Boolean) : LockSettingStorage {
        private var visibleRecord = record(initiallyEnabled)
        private var durableRecord = visibleRecord.copyOf()
        private var visibleMarker = false
        private var durableMarker = false
        private var syncCount = 0
        private var rejectWrites = false
        var recordWrites = 0
            private set
        var recordMayPersistEarly = false
        var crashDuringRecordWrite = false
        var failSyncAt: Int? = null

        override fun hasPendingWrite() = visibleMarker
        override fun read() = visibleRecord.copyOf()
        override fun markPendingWrite() {
            // fd.sync makes marker bytes stable, but its new dentry is not yet stable.
            visibleMarker = true
        }
        override fun write(record: ByteArray) {
            if (rejectWrites) throw IOException("Rollback also failed")
            recordWrites += 1
            visibleRecord = record.copyOf()
            // A filesystem may persist a rename before an explicit directory sync.
            if (recordMayPersistEarly) durableRecord = visibleRecord.copyOf()
            if (crashDuringRecordWrite) throw SimulatedPowerLoss()
        }
        override fun syncDirectory() {
            syncCount += 1
            if (syncCount == failSyncAt) {
                rejectWrites = true
                throw IOException("Directory sync failed")
            }
            durableMarker = visibleMarker
            durableRecord = visibleRecord.copyOf()
        }
        override fun clearPendingWrite() {
            visibleMarker = false
            // Model allowed early persistence of the deletion independently of
            // a preceding unsynced record rename. Keeping the marker is safer.
            durableMarker = false
        }
        fun crashAndReopen() {
            visibleRecord = durableRecord.copyOf()
            visibleMarker = durableMarker
            rejectWrites = false
            crashDuringRecordWrite = false
        }
    }

    companion object {
        private fun record(enabled: Boolean) = "still-app-lock-v1:${if (enabled) "on" else "off"}\n".toByteArray()
    }
}
