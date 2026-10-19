package app.still.gallery.cleanup

import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure fault model, with file bytes separate from directory entries; not actual power-loss proof. */
class ProtectionDurabilityTest {
    @Test fun pendingFenceIsDirectorySyncedBeforeRecordMutationAndRemovedLast() {
        val disk = DurabilityModel()
        val result = store(disk).setProtected(SECOND, true, REVISION)

        assertEquals(ProtectionWriteStatus.SAVED, result.status)
        assertEquals(setOf(FIRST, SECOND), result.snapshot.keys)
        assertEquals(
            listOf("mark-pending", "sync-directory-1", "write-record", "sync-directory-2", "readback", "clear-pending"),
            disk.events.filter { it != "has-pending" && it != "read" },
        )
        assertTrue(disk.fenceWasDurableAtEveryWrite)
        assertFalse(disk.hasPendingWrite())
    }

    @Test fun failedFenceDirectorySyncCannotMutateTheRecord() {
        val disk = DurabilityModel().apply { fault = Fault.FENCE_DIRECTORY_SYNC }
        val initial = disk.rawRecord()

        assertUnknown(store(disk).setProtected(FIRST, false, REVISION))
        assertEquals(0, disk.recordWriteAttempts)
        assertArrayEquals(initial, disk.rawRecord())
        assertFalse(disk.events.contains("clear-pending"))

        disk.fault = null
        disk.crashAndReopen()
        val reopened = store(disk).load()
        assertEquals(ProtectionStatus.READY, reopened.status)
        assertEquals(setOf(FIRST), reopened.keys)
    }

    @Test fun failedReplacementDirectorySyncRetainsFenceWithEitherRenameOutcomeAndNoRollback() {
        for (renameSurvivesEarly in listOf(false, true)) {
            val disk = DurabilityModel().apply {
                renameMayPersistEarly = renameSurvivesEarly
                fault = Fault.REPLACEMENT_DIRECTORY_SYNC
            }
            assertUnknown(store(disk).setProtected(FIRST, false, REVISION))
            assertEquals(1, disk.recordWriteAttempts)
            assertTrue(disk.rejectFurtherWrites)
            // The failed replacement has already changed the currently readable bytes.
            assertTrue(ProtectionRecordCodec.decode(requireNotNull(disk.rawRecord())).keys.isEmpty())

            disk.fault = null
            disk.crashAndReopen()
            val durableKeys = ProtectionRecordCodec.decode(requireNotNull(disk.rawRecord())).keys
            assertEquals(if (renameSurvivesEarly) emptySet<ReadableContentKey>() else setOf(FIRST), durableKeys)
            assertTrue(disk.hasPendingWrite())
            assertEquals(ProtectionStatus.UNKNOWN, store(disk).load().status)
            assertUnknownStoreDoesNotOverwrite(disk)
            assertEquals(1, disk.recordWriteAttempts)
        }
    }

    @Test fun abruptCrashBeforeFencePublicationCannotExposeUnprotection() {
        val disk = DurabilityModel().apply { crashAt = CrashPoint.AFTER_MARKER_WRITE }
        assertThrows(SimulatedPowerLoss::class.java) {
            store(disk).setProtected(FIRST, false, REVISION)
        }
        assertEquals(0, disk.recordWriteAttempts)

        disk.crashAndReopen()
        val reopened = store(disk).load()
        assertEquals(ProtectionStatus.READY, reopened.status)
        assertEquals(setOf(FIRST), reopened.keys)
    }

    @Test fun interruptedUnprotectNeverTurnsUncertainEmptyBytesIntoAnAuthoritativeEmptySet() {
        val interruptionPoints = listOf(
            CrashPoint.AFTER_RECORD_WRITE,
            CrashPoint.AFTER_REPLACEMENT_DIRECTORY_SYNC,
            CrashPoint.AFTER_READBACK,
        )
        for (point in interruptionPoints) {
            for (renameSurvivesEarly in listOf(false, true)) {
                val disk = DurabilityModel().apply {
                    renameMayPersistEarly = renameSurvivesEarly
                    crashAt = point
                }
                // Error deliberately bypasses the production catch(Exception).
                assertThrows(SimulatedPowerLoss::class.java) {
                    store(disk).setProtected(FIRST, false, REVISION)
                }
                disk.crashAndReopen()
                if (renameSurvivesEarly || point != CrashPoint.AFTER_RECORD_WRITE) {
                    assertTrue(ProtectionRecordCodec.decode(requireNotNull(disk.rawRecord())).keys.isEmpty())
                }
                assertTrue(disk.hasPendingWrite())
                assertEquals(ProtectionStatus.UNKNOWN, store(disk).load().status)
                assertUnknownStoreDoesNotOverwrite(disk)
            }
        }
    }

    @Test fun reportedProtectionSurvivesUnsyncedRenameLossAndEarlyFenceDeletion() {
        val disk = DurabilityModel().apply { renameMayPersistEarly = false }
        val result = store(disk).setProtected(SECOND, true, REVISION)
        assertEquals(ProtectionWriteStatus.SAVED, result.status)

        // clearPendingWrite persists deletion early; crash discards every unsynced rename.
        disk.crashAndReopen()
        assertFalse(disk.hasPendingWrite())
        val reopened = store(disk).load()
        assertEquals(ProtectionStatus.READY, reopened.status)
        assertEquals(REVISION + 1, reopened.revision)
        assertEquals(setOf(FIRST, SECOND), reopened.keys)
    }

    @Test fun storageErrorsNeverReportRemovalAndLoadsNeverRepairOrOverwriteRecords() {
        for (failure in Fault.values()) {
            val disk = DurabilityModel().apply {
                fault = failure
                renameMayPersistEarly = true
            }
            assertUnknown(store(disk).setProtected(FIRST, false, REVISION))
            val survivingRawBytes = disk.rawRecord()
            val writes = disk.recordWriteAttempts
            val clears = disk.events.count { it == "clear-pending" }
            val markerWrites = disk.events.count { it == "mark-pending" }
            repeat(2) { store(disk).load() }
            assertArrayEquals(survivingRawBytes, disk.rawRecord())
            assertEquals(writes, disk.recordWriteAttempts)
            assertEquals(clears, disk.events.count { it == "clear-pending" })
            assertEquals(markerWrites, disk.events.count { it == "mark-pending" })

            disk.fault = null
            disk.crashAndReopen()
            val reopened = store(disk).load()
            assertTrue("Uncertain removal after $failure", reopened.status == ProtectionStatus.UNKNOWN || FIRST in reopened.keys)
            if (reopened.status == ProtectionStatus.UNKNOWN) assertUnknownStoreDoesNotOverwrite(disk)
        }
    }

    @Test fun readAndFenceReadErrorsPreserveAllExistingRawEntries() {
        for (failure in listOf(Fault.PENDING_READ, Fault.INITIAL_READ)) {
            val raw = ProtectionRecordCodec.encode(REVISION, setOf(FIRST, SECOND))
            val disk = DurabilityModel(raw).apply { fault = failure }
            assertEquals(ProtectionStatus.UNKNOWN, store(disk).load().status)
            assertUnknown(store(disk).setProtected(FIRST, false, REVISION))
            assertArrayEquals(raw, disk.rawRecord())
            assertEquals(0, disk.recordWriteAttempts)
            assertFalse(disk.events.contains("mark-pending"))
            disk.fault = null
            disk.crashAndReopen()
            assertArrayEquals(raw, disk.rawRecord())
            assertEquals(setOf(FIRST, SECOND), store(disk).load().keys)
        }
    }

    private fun assertUnknownStoreDoesNotOverwrite(disk: DurabilityModel) {
        val before = disk.rawRecord()
        val writes = disk.recordWriteAttempts
        val clears = disk.events.count { it == "clear-pending" }
        val markerWrites = disk.events.count { it == "mark-pending" }
        repeat(2) {
            assertEquals(ProtectionStatus.UNKNOWN, store(disk).load().status)
            assertUnknown(store(disk).setProtected(FIRST, false, REVISION))
            assertUnknown(store(disk).setProtected(SECOND, true, REVISION))
        }
        assertArrayEquals(before, disk.rawRecord())
        assertEquals(writes, disk.recordWriteAttempts)
        assertEquals(clears, disk.events.count { it == "clear-pending" })
        assertEquals(markerWrites, disk.events.count { it == "mark-pending" })
    }

    private fun assertUnknown(result: ProtectionWriteResult) {
        assertEquals(ProtectionWriteStatus.UNKNOWN, result.status)
        assertEquals(ProtectionStatus.UNKNOWN, result.snapshot.status)
    }

    private fun store(disk: DurabilityModel) = ProtectionStore(disk, Any())

    private class SimulatedPowerLoss : Error()
    private enum class CrashPoint {
        AFTER_MARKER_WRITE, AFTER_RECORD_WRITE, AFTER_REPLACEMENT_DIRECTORY_SYNC, AFTER_READBACK,
    }
    private enum class Fault {
        PENDING_READ, INITIAL_READ, MARKER_WRITE, MARKER_WRITE_AFTER_CREATE,
        FENCE_DIRECTORY_SYNC, RECORD_WRITE, RECORD_WRITE_AFTER_REPLACE,
        REPLACEMENT_DIRECTORY_SYNC, READBACK, READBACK_MISSING, READBACK_MISMATCH, MARKER_CLEAR,
    }

    private class DurabilityModel(initialRecord: ByteArray = ProtectionRecordCodec.encode(REVISION, setOf(FIRST))) : ProtectionStorage {
        private val originalRecord = initialRecord.copyOf()
        private val visibleFileBytes = mutableMapOf(0 to initialRecord.copyOf())
        private val durableFileBytes = mutableMapOf(0 to initialRecord.copyOf())
        private var visibleRecordEntry = 0
        private var durableRecordEntry = 0
        private var nextInode = 1
        private var visibleMarker = false
        private var durableMarker = false
        private var directorySyncs = 0
        private var replacementWritten = false
        var fault: Fault? = null
        var crashAt: CrashPoint? = null
        var renameMayPersistEarly = false
        var recordWriteAttempts = 0
            private set
        var rejectFurtherWrites = false
            private set
        var fenceWasDurableAtEveryWrite = true
            private set
        val events = mutableListOf<String>()

        override fun hasPendingWrite(): Boolean {
            events += "has-pending"
            failAt(Fault.PENDING_READ)
            return visibleMarker
        }

        override fun read(): ByteArray? {
            events += if (replacementWritten) "readback" else "read"
            if (!replacementWritten) failAt(Fault.INITIAL_READ)
            if (replacementWritten) {
                failAt(Fault.READBACK)
                if (fault == Fault.READBACK_MISSING) return null
                if (fault == Fault.READBACK_MISMATCH) return originalRecord.copyOf()
                crashAfter(CrashPoint.AFTER_READBACK)
            }
            return rawRecord()
        }

        override fun markPendingWrite() {
            events += "mark-pending"
            failAt(Fault.MARKER_WRITE)
            // Marker file bytes are synced, but its new dentry is still volatile.
            visibleMarker = true
            failAt(Fault.MARKER_WRITE_AFTER_CREATE)
            crashAfter(CrashPoint.AFTER_MARKER_WRITE)
        }

        override fun write(record: ByteArray) {
            events += "write-record"
            recordWriteAttempts += 1
            if (rejectFurtherWrites) throw IOException("Rollback/retry also failed")
            failAt(Fault.RECORD_WRITE)
            fenceWasDurableAtEveryWrite = fenceWasDurableAtEveryWrite && durableMarker
            val inode = nextInode++
            visibleFileBytes[inode] = record.copyOf()
            // File fsync persists replacement bytes independently of the rename.
            durableFileBytes[inode] = record.copyOf()
            visibleRecordEntry = inode
            replacementWritten = true
            if (renameMayPersistEarly) durableRecordEntry = inode
            failAt(Fault.RECORD_WRITE_AFTER_REPLACE)
            crashAfter(CrashPoint.AFTER_RECORD_WRITE)
        }

        override fun syncDirectory() {
            directorySyncs += 1
            events += "sync-directory-$directorySyncs"
            if (directorySyncs == 1) failAt(Fault.FENCE_DIRECTORY_SYNC)
            if (directorySyncs == 2 && fault == Fault.REPLACEMENT_DIRECTORY_SYNC) {
                rejectFurtherWrites = true
                throw IOException("Replacement directory sync failed; rollback unavailable")
            }
            durableRecordEntry = visibleRecordEntry
            durableMarker = visibleMarker
            if (directorySyncs == 2) crashAfter(CrashPoint.AFTER_REPLACEMENT_DIRECTORY_SYNC)
        }

        override fun clearPendingWrite() {
            events += "clear-pending"
            failAt(Fault.MARKER_CLEAR)
            visibleMarker = false
            // Worst permitted ordering: marker deletion persists immediately.
            durableMarker = false
        }

        fun rawRecord(): ByteArray? = visibleFileBytes[visibleRecordEntry]?.copyOf()

        fun crashAndReopen() {
            visibleFileBytes.clear()
            durableFileBytes.forEach { (inode, bytes) -> visibleFileBytes[inode] = bytes.copyOf() }
            visibleRecordEntry = durableRecordEntry
            visibleMarker = durableMarker
            directorySyncs = 0
            replacementWritten = false
            rejectFurtherWrites = false
            crashAt = null
        }

        private fun failAt(stage: Fault) {
            if (fault == stage) throw IOException("Injected $stage")
        }

        private fun crashAfter(point: CrashPoint) {
            if (crashAt == point) throw SimulatedPowerLoss()
        }
    }

    companion object {
        private const val REVISION = 7L
        private val FIRST = ReadableContentKey("a".repeat(64), 12L)
        private val SECOND = ReadableContentKey("b".repeat(64), 24L)
    }
}
