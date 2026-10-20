package app.still.gallery.cleanup

import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class ProtectionStoreTest {
    private val keyA = ReadableContentKey("a".repeat(64), 123)
    private val keyB = ReadableContentKey("b".repeat(64), 456)

    @Test fun canonicalRecordRoundTripsAllKeysAndRevisionIncludingEmptySet() {
        val bytes = ProtectionRecordCodec.encode(12, setOf(keyB, keyA))
        val restored = ProtectionRecordCodec.decode(bytes)
        assertEquals(ProtectionStatus.READY, restored.status)
        assertEquals(12L, restored.revision)
        assertEquals(setOf(keyA, keyB), restored.keys)
        assertArrayEquals(bytes, ProtectionRecordCodec.encode(restored.revision, restored.keys))
        assertTrue(ProtectionRecordCodec.decode(ProtectionRecordCodec.encode(13, emptySet())).keys.isEmpty())
    }

    @Test fun malformedUnknownOversizedAndDuplicateRecordsRemainUnknownAndUnchanged() {
        val valid = ProtectionRecordCodec.encode(1, setOf(keyA))
        val line = "${keyA.algorithm}:${keyA.sha256}:${keyA.byteCount}\n"
        val malformed = listOf(
            ByteArray(0), "false".toByteArray(), valid.copyOf(valid.size - 1),
            valid.toString(Charsets.US_ASCII).replace("-v1", "-v2").toByteArray(),
            valid.toString(Charsets.US_ASCII).replace("revision:1", "revision:0").toByteArray(),
            valid.toString(Charsets.US_ASCII).replace("revision:1", "revision:9223372036854775808").toByteArray(),
            (valid.toString(Charsets.US_ASCII) + line).toByteArray(),
            valid.toString(Charsets.US_ASCII).replace(":123", ":0").toByteArray(),
            valid.toString(Charsets.US_ASCII).replace(":123", ":0123").toByteArray(),
            valid.toString(Charsets.US_ASCII).replace("a".repeat(64), "A".repeat(64)).toByteArray(),
            ByteArray(ProtectionRecordCodec.MAX_BYTES + 1) { 65 },
        )
        for (raw in malformed) {
            val disk = MemoryStorage(raw)
            val store = ProtectionStore(disk, Any())
            assertEquals(ProtectionStatus.UNKNOWN, store.load().status)
            assertEquals(ProtectionWriteStatus.UNKNOWN, store.setProtected(keyB, true, 0).status)
            assertArrayEquals(raw, disk.record)
            assertEquals(0, disk.writes)
        }
    }

    @Test fun corruptUnknownAndPendingRecordsAreNeverAutomaticallyRepaired() {
        val disk = MemoryStorage(ProtectionRecordCodec.encode(1, setOf(keyA))).apply { pending = true }
        val store = ProtectionStore(disk, Any())
        repeat(3) {
            assertEquals(ProtectionStatus.UNKNOWN, store.load().status)
            assertEquals(ProtectionWriteStatus.UNKNOWN, store.setProtected(keyA, false, 1).status)
        }
        assertTrue(disk.pending)
        assertEquals(0, disk.writes)
        assertEquals(setOf(keyA), ProtectionRecordCodec.decode(requireNotNull(disk.record)).keys)
    }

    @Test fun noOpMutationsAreIdempotentButStaleRevisionsStillConflict() {
        val disk = MemoryStorage()
        val store = ProtectionStore(disk, Any())
        assertEquals(ProtectionStatus.READY, store.load().status)
        assertEquals(0L, store.load().revision)
        val first = store.setProtected(keyA, true, 0)
        assertEquals(ProtectionWriteStatus.SAVED, first.status)
        assertEquals(1L, first.snapshot.revision)
        assertEquals(ProtectionWriteStatus.SAVED, store.setProtected(keyA, true, 1).status)
        assertEquals(1, disk.writes)
        assertEquals(ProtectionWriteStatus.CONFLICT, store.setProtected(keyA, false, 0).status)
        assertEquals(setOf(keyA), store.load().keys)
    }

    @Test fun protectionsRetainAbsentContentAcrossRealFileReopenAndExplicitRemoval() {
        val directory = Files.createTempDirectory("still-protections-").toFile()
        try {
            val coordinator = Any()
            val first = ProtectionStore(RealStorage(directory), coordinator)
            assertEquals(ProtectionWriteStatus.SAVED, first.setProtected(keyA, true, 0).status)
            assertEquals(ProtectionWriteStatus.SAVED, first.setProtected(keyB, true, 1).status)
            val recreated = ProtectionStore(RealStorage(directory), coordinator)
            assertEquals(setOf(keyA, keyB), recreated.load().keys)
            // No scan membership is passed to storage; missing sources never prune keys.
            assertEquals(2L, recreated.load().revision)
            assertEquals(ProtectionWriteStatus.SAVED, recreated.setProtected(keyA, false, 2).status)
            val reopened = ProtectionStore(RealStorage(directory), coordinator).load()
            assertEquals(setOf(keyB), reopened.keys)
            assertEquals(3L, reopened.revision)
            assertFalse(File(directory, "pending").exists())
        } finally { directory.deleteRecursively() }
    }

    @Test fun competingInstancesCannotBothCommitTheSameExpectedRevision() {
        val disk = MemoryStorage()
        val coordinator = Any()
        val first = ProtectionStore(disk, coordinator)
        val recreated = ProtectionStore(disk, coordinator)
        val executor = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val futures = listOf(keyA to first, keyB to recreated).map { (key, store) ->
                executor.submit<ProtectionWriteResult> { start.await(); store.setProtected(key, true, 0) }
            }
            start.countDown()
            val results = futures.map { it.get(3, TimeUnit.SECONDS).status }
            assertEquals(1, results.count { it == ProtectionWriteStatus.SAVED })
            assertEquals(1, results.count { it == ProtectionWriteStatus.CONFLICT })
            assertEquals(1, first.load().keys.size)
            assertEquals(1L, first.load().revision)
            assertEquals(1, disk.writes)
        } finally { executor.shutdownNow() }
    }

    @Test fun queuedWriteRechecksLiveAdmissionInsideTheProcessWideCoordinator() {
        val disk = MemoryStorage()
        val coordinator = Any()
        val store = ProtectionStore(disk, coordinator)
        val allowed = AtomicBoolean(true)
        val started = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = synchronized(coordinator) {
                val queued = executor.submit<ProtectionWriteResult> {
                    started.countDown()
                    store.setProtected(keyA, true, 0) { if (!allowed.get()) throw StaleExactReviewException() }
                }
                assertTrue(started.await(3, TimeUnit.SECONDS))
                allowed.set(false)
                queued
            }
            val failure = assertThrows(java.util.concurrent.ExecutionException::class.java) { future.get(3, TimeUnit.SECONDS) }
            assertTrue(failure.cause is StaleExactReviewException)
            assertEquals(0, disk.writes)
            assertFalse(disk.pending)
        } finally { executor.shutdownNow() }
    }

    @Test fun revisionExhaustionAndRecordCapacityNeverWrapPruneOrOverwrite() {
        val exhausted = ProtectionRecordCodec.encode(Long.MAX_VALUE, setOf(keyA))
        val disk = MemoryStorage(exhausted)
        val store = ProtectionStore(disk, Any())
        assertEquals(ProtectionWriteStatus.UNKNOWN, store.setProtected(keyB, true, Long.MAX_VALUE).status)
        assertArrayEquals(exhausted, disk.record)
        assertEquals(0, disk.writes)
        val keys = (1..ProtectionRecordCodec.MAX_KEYS).map { ReadableContentKey(it.toString(16).padStart(64, '0'), 1) }.toSet()
        val full = ProtectionRecordCodec.encode(9, keys)
        val fullDisk = MemoryStorage(full)
        assertEquals(ProtectionWriteStatus.UNKNOWN, ProtectionStore(fullDisk, Any()).setProtected(keyA, true, 9).status)
        assertArrayEquals(full, fullDisk.record)
        assertEquals(0, fullDisk.writes)
    }

    private class MemoryStorage(initial: ByteArray? = null) : ProtectionStorage {
        var record = initial?.copyOf()
        var pending = false
        var writes = 0
        override fun hasPendingWrite() = pending
        override fun read() = record?.copyOf()
        override fun markPendingWrite() { pending = true }
        override fun write(record: ByteArray) { this.record = record.copyOf(); writes++ }
        override fun syncDirectory() = Unit
        override fun clearPendingWrite() { pending = false }
    }

    /** Real host-file/record reopening, not an Android AtomicFile or power-loss test. */
    private class RealStorage(private val directory: File) : ProtectionStorage {
        private val data = File(directory, "record")
        private val pending = File(directory, "pending")
        override fun hasPendingWrite() = pending.exists()
        override fun read() = if (data.exists()) data.readBytes() else null
        override fun markPendingWrite() { FileOutputStream(pending).use { it.write(1); it.fd.sync() } }
        override fun write(record: ByteArray) {
            val replacement = File(directory, "replacement")
            FileOutputStream(replacement).use { it.write(record); it.fd.sync() }
            Files.move(replacement.toPath(), data.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
        override fun syncDirectory() { FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) } }
        override fun clearPendingWrite() { check(pending.delete()) }
    }
}
