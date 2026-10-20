package app.still.gallery.inputs

import app.still.gallery.catalogue.CatalogueNamespace
import app.still.gallery.media.*
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class SelectedInputPreparationTest {
    @Test fun capturesActualReadableBytesAfterCompleteCloseSyncAndReadback() = runBlocking {
        val fixture = Fixture()
        fixture.reader.stream = { ByteArrayInputStream("readable representation".toByteArray()) }
        val pair = fixture.prepare()
        assertEquals(listOf(23L, 23L), pair.members.map { it.byteCount })
        assertEquals(listOf(3L, 3L), fixture.reader.rows.map { it.bytes })
        assertEquals(3, fixture.reader.validations)
        assertEquals(2, fixture.reader.closed)
        assertEquals(2, fixture.files.sinksClosed)
        assertEquals(2, fixture.files.readsClosed)
        assertTrue(fixture.files.syncs >= 3)
        pair.members.forEach { member ->
            assertEquals(SelectedInputFidelity.READABLE_REPRESENTATION, member.fidelity)
            assertEquals("readable representation", fixture.store.openRead(fixture.ticket, member).use { String(it.readBytes()) })
        }
        assertEquals(SelectedInputCleanupResult.CLEAN, fixture.store.closeAndCleanup(fixture.ticket))
        assertEquals(0L, Files.list(fixture.root).use { it.count() })
    }

    @Test fun failedSecondSourceNeverPublishesFirstAndRemovesBothPartials() = runBlocking {
        val fixture = Fixture()
        fixture.reader.stream = { if (it.externalHandle == "2") throw IOException() else ByteArrayInputStream(byteArrayOf(1, 2)) }
        assertFailure(SelectedInputFailure.IO) { fixture.prepare() }
        assertEquals(0L, Files.list(fixture.root).use { it.count() })
        fixture.assertLaneFree()
    }

    @Test fun firstSourceChangeDuringSecondReadInvalidatesWholePair() = runBlocking {
        val fixture = Fixture()
        fixture.reader.stream = { source ->
            if (source.externalHandle == "2") fixture.reader.rows[0] = fixture.reader.rows[0].copy(modifiedAt = 999)
            ByteArrayInputStream(byteArrayOf(1))
        }
        assertFailure(SelectedInputFailure.SOURCE_CHANGED) { fixture.prepare() }
        assertEquals(0L, Files.list(fixture.root).use { it.count() })
    }

    @Test fun namespaceChangeAfterSecondSourceInvalidatesBoth() = runBlocking {
        val fixture = Fixture()
        fixture.reader.validate = { if (fixture.reader.validations == 3) throw CleanupSourceChangedException() }
        assertFailure(SelectedInputFailure.SOURCE_CHANGED) { fixture.prepare() }
        assertEquals(2, fixture.reader.closed)
    }

    @Test fun scopeLimitHasDirectTypedFailureBeforeOpeningSource() = runBlocking {
        val fixture = Fixture()
        fixture.reader.snapshotFailure = CleanupScopeLimitException()
        assertFailure(SelectedInputFailure.SCOPE_LIMIT) { fixture.prepare() }
        assertEquals(0, fixture.reader.opened)
        fixture.assertLaneFree()
    }

    @Test fun removedOrAmbiguousSelectedTokenStopsBeforeSourceOpen() = runBlocking {
        for (ambiguous in listOf(false, true)) {
            val fixture = Fixture()
            if (ambiguous) fixture.reader.rows += fixture.reader.rows[0] else fixture.reader.rows.removeAt(0)
            assertFailure(SelectedInputFailure.SOURCE_CHANGED) { fixture.prepare() }
            assertEquals(0, fixture.reader.opened)
        }
    }

    @Test fun accessLossAfterReadClosesSourceWithoutAnotherOpenOrPublication() = runBlocking {
        val fixture = Fixture()
        var authorized = true
        fixture.reader.access = { if (!authorized) throw IOException() }
        fixture.reader.stream = { object : ByteArrayInputStream(byteArrayOf(1, 2)) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int { val count = super.read(buffer, offset, length); authorized = false; return count }
        } }
        assertFailure(SelectedInputFailure.IO) { fixture.prepare() }
        assertEquals(1, fixture.reader.opened)
        assertEquals(1, fixture.reader.closed)
    }

    @Test fun emptyStalledAndDeclaredRepresentationShortReadsFailWithoutUsingObservedSize() = runBlocking {
        for (mode in listOf("empty", "stalled", "declared")) {
            val fixture = Fixture()
            fixture.reader.stream = { when (mode) {
                "empty" -> ByteArrayInputStream(byteArrayOf())
                "stalled" -> object : InputStream() { override fun read(): Int = 0; override fun read(b: ByteArray, o: Int, l: Int): Int = 0 }
                else -> ByteArrayInputStream(byteArrayOf(1, 2))
            } }
            assertFailure(if (mode == "empty") SelectedInputFailure.EMPTY else SelectedInputFailure.IO) {
                if (mode == "declared") SelectedInputPreparation(fixture.reader, fixture.store, fixture.lane) { 4 }.prepare(fixture.ticket, emptySet()) else fixture.prepare()
            }
            fixture.assertLaneFree()
        }
    }

    @Test fun actualByteLimitStopsEvenWhenObservedSizeIsSmall() = runBlocking {
        val fixture = Fixture()
        var read = 0L
        fixture.reader.stream = { object : InputStream() {
            override fun read(): Int = throw IOException()
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (read > SelectedInputBounds.MAX_INPUT_BYTES) return -1
                java.util.Arrays.fill(buffer, offset, offset + length, 1.toByte())
                read += length
                return length
            }
        } }
        assertFailure(SelectedInputFailure.SIZE_LIMIT) { fixture.prepare() }
        assertEquals(SelectedInputBounds.MAX_INPUT_BYTES + SelectedInputBounds.BUFFER_BYTES, read)
        assertEquals(1, fixture.reader.closed)
    }

    @Test fun readbackMismatchAndFileSyncFailureCannotBecomeReady() = runBlocking {
        for (mode in listOf("readback", "sync")) {
            val fixture = Fixture()
            fixture.files.badReadback = mode == "readback"
            fixture.files.badSync = mode == "sync"
            assertFailure(SelectedInputFailure.IO) { fixture.prepare() }
            fixture.assertLaneFree()
            assertEquals(0L, Files.list(fixture.root).use { it.count() })
        }
    }

    @Test fun failedProviderCloseRetainsHeavyLaneAndQuotaAcrossNewStoreUntilVerifiedRetry() = runBlocking {
        val fixture = Fixture()
        var failClose = true
        fixture.reader.close = { if (failClose) throw IOException() }
        assertFailure(SelectedInputFailure.CLEANUP_FAILED) { fixture.prepare() }
        assertNull(fixture.lane.tryEnter())
        assertTrue(Files.list(fixture.root).use { it.count() } > 0)
        val replacement = fixture.replacement()
        assertEquals(SelectedInputCleanupResult.FAILED, replacement.initialize())
        assertFailure(SelectedInputFailure.BUSY) { replacement.acquire(fixture.reader.rows) {} }
        failClose = false
        assertEquals(SelectedInputCleanupResult.CLEAN, replacement.initialize())
        fixture.assertLaneFree()
        assertEquals(0L, Files.list(fixture.root).use { it.count() })
    }

    @Test fun sinkAndReadbackFailedClosesRetainFilesAndAdmissionUntilActualSuccess() = runBlocking {
        for (mode in listOf("sink", "readback")) {
            val fixture = Fixture()
            fixture.files.failSinkClose = mode == "sink"
            fixture.files.failReadClose = mode == "readback"
            assertFailure(SelectedInputFailure.CLEANUP_FAILED) { fixture.prepare() }
            assertNull(fixture.lane.tryEnter())
            assertTrue(Files.list(fixture.root).use { it.count() } > 0)
            fixture.files.failSinkClose = false; fixture.files.failReadClose = false
            assertEquals(SelectedInputCleanupResult.CLEAN, fixture.replacement().initialize())
            fixture.assertLaneFree()
        }
    }

    @Test fun suppressedCloseFailureUnderAuthorityLossStillRetainsOwnerAndLane() = runBlocking {
        val fixture = Fixture()
        var current = true
        var failClose = true
        fixture.reader.close = { if (failClose) throw IOException() }
        fixture.reader.stream = { object : ByteArrayInputStream(byteArrayOf(1)) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int { current = false; return super.read(buffer, offset, length) }
        } }
        fixture.ticket = fixture.store.acquireAfterFreshInitialize(fixture) { if (!current) throw IOException() }
        assertFailure(SelectedInputFailure.CLEANUP_FAILED) { fixture.prepare() }
        assertNull(fixture.lane.tryEnter())
        failClose = false
        assertEquals(SelectedInputCleanupResult.CLEAN, fixture.replacement().initialize())
        fixture.assertLaneFree()
    }

    @Test fun cancellationDuringBlockedProviderCloseCannotSweepOrStartNewHeavyRead() = runBlocking {
        val fixture = Fixture()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        fixture.reader.close = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        val preparing = async { fixture.prepare() }
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            preparing.cancel()
            fixture.store.invalidate(fixture.ticket)
            assertEquals(SelectedInputCleanupResult.BUSY, fixture.replacement().initialize())
            assertNull(fixture.lane.tryEnter())
            assertTrue(Files.list(fixture.root).use { it.count() } > 0)
        } finally { release.countDown(); preparing.cancelAndJoin() }
        fixture.assertLaneFree()
        assertEquals(SelectedInputCleanupResult.CLEAN, fixture.store.initialize())
    }

    @Test fun readyDecoderRetainsOwnerUntilSuccessfulActualCloseAndRejectsReadsAfterInvalidation() = runBlocking {
        val fixture = Fixture()
        val pair = fixture.prepare()
        val stream = fixture.store.openRead(fixture.ticket, pair.members[0])
        fixture.store.invalidate(fixture.ticket)
        assertEquals(SelectedInputCleanupResult.BUSY, fixture.replacement().initialize())
        assertFailure(SelectedInputFailure.STALE) { stream.read() }
        fixture.files.failReadClose = true
        assertFailure(SelectedInputFailure.IO) { stream.close() }
        assertEquals(SelectedInputCleanupResult.FAILED, fixture.replacement().initialize())
        fixture.files.failReadClose = false
        assertEquals(SelectedInputCleanupResult.CLEAN, fixture.replacement().initialize())
        stream.close() // successful retry has made this exact handle idempotent
    }

    @Test fun failedDeletionBlocksAdmissionAndNewInstanceCleansTheRetainedOldFilesystem() = runBlocking {
        val fixture = Fixture()
        fixture.prepare()
        fixture.files.failRemove = true
        assertEquals(SelectedInputCleanupResult.FAILED, fixture.store.closeAndCleanup(fixture.ticket))
        val replacement = fixture.replacement()
        assertEquals(SelectedInputCleanupResult.FAILED, replacement.initialize())
        assertFailure(SelectedInputFailure.BUSY) { replacement.acquire(fixture.reader.rows) {} }
        fixture.files.failRemove = false
        assertEquals(SelectedInputCleanupResult.CLEAN, replacement.initialize())
        assertEquals(0L, Files.list(fixture.root).use { it.count() })
        assertEquals(0, fixture.newFilesCalls)
    }

    @Test fun failedDirectoryCloseRetainsOldOwnerUntilRecreatedControllerCanVerifyClosure() = runBlocking {
        val fixture = Fixture()
        fixture.prepare()
        fixture.files.failRootClose = true
        assertEquals(SelectedInputCleanupResult.FAILED, fixture.store.closeAndCleanup(fixture.ticket))
        assertFailure(SelectedInputFailure.BUSY) { fixture.replacement().acquire(fixture.reader.rows) {} }
        assertEquals(SelectedInputCleanupResult.FAILED, fixture.replacement().initialize())
        fixture.files.failRootClose = false
        assertEquals(SelectedInputCleanupResult.CLEAN, fixture.replacement().initialize())
        assertEquals(0, fixture.newFilesCalls)
    }

    @Test fun decoderCloseCannotReleaseRetentionWhileItsReadIsStillBlocked() = runBlocking {
        val fixture = Fixture()
        val pair = fixture.prepare()
        val stream = fixture.store.openRead(fixture.ticket, pair.members[0])
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        fixture.files.onRead = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        val reading = async(Dispatchers.IO) { runCatching { stream.read(ByteArray(1)) } }
        var closing: kotlinx.coroutines.Deferred<Unit>? = null
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            fixture.store.invalidate(fixture.ticket)
            closing = async(Dispatchers.IO) { stream.close() }
            assertEquals(SelectedInputCleanupResult.BUSY, fixture.replacement().initialize())
            assertFalse(closing.isCompleted)
        } finally { release.countDown(); reading.await(); closing?.await() }
        assertEquals(SelectedInputCleanupResult.CLEAN, fixture.replacement().initialize())
    }

    private class Reader : CleanupSourceReader {
        val rows = mutableListOf(cleanupTestItem(1), cleanupTestItem(2))
        var stream: (MediaItem) -> InputStream = { ByteArrayInputStream("abc".toByteArray()) }
        var close: () -> Unit = {}
        var access: () -> Unit = {}
        var validate: () -> Unit = {}
        var snapshotFailure: Exception? = null
        var opened = 0; var closed = 0; var validations = 0
        override fun checkAccess() { access() }
        override suspend fun snapshot(favorites: Set<String>): CleanupSourceSnapshot {
            snapshotFailure?.let { throw it }
            return CleanupSourceSnapshot(rows.toList(), listOf(CatalogueNamespace("media", "external_primary", "v", 5)), emptyMap(), MediaAccess(30, legacy = true))
        }
        override suspend fun probe(item: MediaItem) = rows.singleOrNull { it.id == item.id }
        override suspend fun validateScope(snapshot: CleanupSourceSnapshot) { validations++; validate() }
        override fun open(item: MediaItem): InputStream {
            opened++
            val actual = stream(item)
            return object : java.io.FilterInputStream(actual) {
                override fun close() { close.invoke(); super.close(); closed++ }
            }
        }
    }
    private class Filesystem(root: Path) : SelectedInputFileSystem by NioSelectedInputFileSystem(root) {
        private val actual = NioSelectedInputFileSystem(root)
        var failSinkClose = false; var failReadClose = false; var failRemove = false; var failRootClose = false
        var onRead: () -> Unit = {}
        var badReadback = false; var badSync = false
        var sinksClosed = 0; var readsClosed = 0; var syncs = 0
        override fun initialize() = actual.initialize()
        override fun names() = actual.names()
        override fun info(name: String) = actual.info(name)
        override fun create(name: String): SelectedInputSink {
            val sink = actual.create(name)
            return object : SelectedInputSink {
                override val stream = sink.stream
                override fun sync() { if (badSync) throw IOException(); sink.sync() }
                override fun close() { if (failSinkClose) throw IOException(); sink.close(); sinksClosed++ }
            }
        }
        override fun open(name: String, expected: SelectedInputFileInfo?): InputStream {
            val input = actual.open(name, expected)
            return object : java.io.FilterInputStream(input) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int { onRead(); val count = super.read(buffer, offset, length); if (badReadback && count > 0) buffer[offset] = 99; return count }
                override fun close() { if (failReadClose) throw IOException(); super.close(); readsClosed++ }
            }
        }
        override fun remove(name: String) { if (failRemove) throw IOException(); actual.remove(name) }
        override fun syncDirectory() { syncs++; actual.syncDirectory() }
        override fun settle() = actual.settle()
        override val hasUnsettledHandles: Boolean get() = actual.hasUnsettledHandles
        override fun close() { actual.close(); if (failRootClose) throw IOException() }
    }
    private class Fixture {
        val root: Path = Files.createDirectory(Files.createTempDirectory("still-selected-test").resolve("selected"))
        val owner = SelectedInputOwnership()
        val files = Filesystem(root)
        val store = SelectedInputStore(root, owner, files)
        val reader = Reader()
        val lane = CleanupReadAdmission()
        var newFilesCalls = 0
        var ticket: SelectedInputTicket
        init { runBlocking { assertEquals(SelectedInputCleanupResult.CLEAN, store.initialize()) }; ticket = store.acquire(reader.rows) {} }
        suspend fun prepare() = SelectedInputPreparation(reader, store, lane).prepare(ticket, emptySet())
        fun assertLaneFree() { val lease = lane.tryEnter(); assertNotNull(lease); lease!!.close() }
        fun replacement(): SelectedInputStore = SelectedInputStore(root, owner, object : SelectedInputFileSystem by NioSelectedInputFileSystem(root) {
            override fun initialize() { newFilesCalls++; throw IOException("wrong filesystem") }
        })
    }
    private suspend fun SelectedInputStore.acquireAfterFreshInitialize(fixture: Fixture, check: () -> Unit): SelectedInputTicket {
        assertEquals(SelectedInputCleanupResult.CLEAN, closeAndCleanup(fixture.ticket))
        return acquire(fixture.reader.rows, check)
    }
    private suspend fun assertFailure(reason: SelectedInputFailure, block: suspend () -> Any?) {
        val failure = runCatching { block() }.exceptionOrNull()
        assertTrue("Expected $reason, got $failure", failure is SelectedInputException)
        assertEquals(reason, (failure as SelectedInputException).reason)
    }
}
