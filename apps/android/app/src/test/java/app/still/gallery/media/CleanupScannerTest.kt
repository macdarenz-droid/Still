package app.still.gallery.media

import app.still.gallery.catalogue.CatalogueNamespace
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class CleanupScannerTest {
    @Test fun fullScanCountsActualReadableBytesAndRegistersBeforeOpen() = runBlocking {
        val reader = FakeReader()
        val registered = mutableSetOf<String>()
        reader.onOpen = { assertTrue(it.id in registered) }
        val progress = mutableListOf<CleanupCoverage>()
        val result = CleanupScanner(reader, CleanupReadAdmission()).scan(emptySet(), {}, {}, { registered += it.id }, { progress += it })
        assertEquals(listOf(3L, 3L), result.members.map { it.digest.byteCount })
        assertEquals(2, result.coverage.compared)
        assertEquals(2, result.coverage.checked)
        assertEquals(result.coverage, progress.last())
        assertEquals(2, reader.validations)
    }

    @Test fun earlyMemberChangingDuringLastReadInvalidatesEntireResult() = runBlocking {
        val reader = FakeReader()
        reader.onOpen = { if (it.externalHandle == "2") reader.rows[0] = reader.rows[0].copy(modifiedAt = 999) }
        val failure = runCatching { CleanupScanner(reader, CleanupReadAdmission()).scan(emptySet(), {}, {}, {}, {}) }.exceptionOrNull()
        assertTrue(failure is CleanupSourceChangedException)
    }

    @Test fun finalNamespaceAdvanceRejectsUnchangedLookingRows() = runBlocking {
        val reader = FakeReader()
        reader.onValidate = { throw CleanupSourceChangedException() }
        assertTrue(runCatching { CleanupScanner(reader, CleanupReadAdmission()).scan(emptySet(), {}, {}, {}, {}) }.exceptionOrNull() is CleanupSourceChangedException)
    }

    @Test fun failedEmptyAndChangedReadsNeverBecomeMembers() = runBlocking {
        val reader = FakeReader(mutableListOf(cleanupTestItem(1), cleanupTestItem(2), cleanupTestItem(3), cleanupTestItem(4)))
        reader.onStream = { item -> when (item.externalHandle) {
            "1" -> ByteArrayInputStream(byteArrayOf())
            "2" -> throw IOException("synthetic")
            else -> ByteArrayInputStream("abc".toByteArray())
        } }
        reader.onProbe = { item -> if (item.externalHandle == "3") null else item }
        val result = CleanupScanner(reader, CleanupReadAdmission()).scan(emptySet(), {}, {}, {}, {})
        assertEquals(listOf("4"), result.members.map { it.item.externalHandle })
        assertEquals(1, result.coverage.empty)
        assertEquals(1, result.coverage.unavailable)
        assertEquals(1, result.coverage.changed)
        assertEquals(1, result.coverage.compared)
    }

    @Test fun duplicateAliasesAreExcludedAndScopeMismatchPreventsOpen() = runBlocking {
        val a = cleanupTestItem(1)
        val reader = FakeReader(mutableListOf(a, a, cleanupTestItem(2), cleanupTestItem(3).copy(bytes = 9)))
        reader.onProbe = { it } // Enumeration repeats a reference; exact-row probes remain unique.
        val result = CleanupScanner(reader, CleanupReadAdmission()).scan(emptySet(), {}, {}, {}, {})
        assertEquals(4, result.coverage.considered)
        assertEquals(1, result.coverage.aliases)
        assertEquals(1, result.coverage.omitted)
        assertEquals(2, result.members.size)
        var opens = 0
        reader.onOpen = { opens++ }
        assertTrue(runCatching { CleanupScanner(reader, CleanupReadAdmission()).scan(emptySet(), {}, { throw CancellationException() }, {}, {}) }.exceptionOrNull() is CancellationException)
        assertEquals(0, opens)
    }

    @Test fun cancelledBlockedReaderRetainsSharedAdmissionAcrossNewScanner() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val lane = CleanupReadAdmission()
        val reader = FakeReader()
        var closed = false
        reader.onStream = { object : InputStream() {
            override fun read(): Int = throw IOException("unused")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                buffer[0] = 1
                return 1
            }
            override fun close() { closed = true }
        } }
        val first = async { CleanupScanner(reader, lane).scan(emptySet(), {}, {}, {}, {}) }
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            first.cancel()
            assertFalse(first.isCompleted)
            val busy = runCatching { CleanupScanner(FakeReader(), lane).scan(emptySet(), {}, {}, {}, {}) }.exceptionOrNull()
            assertTrue(busy is CleanupReaderBusyException)
        } finally {
            release.countDown()
            first.cancelAndJoin()
        }
        assertTrue(closed)
        assertEquals(2, CleanupScanner(FakeReader(), lane).scan(emptySet(), {}, {}, {}, {}).members.size)
    }

    @Test fun authorityLossDuringReadSuppressesProgressAndFinalOutput() = runBlocking {
        var authorized = true
        val reader = FakeReader()
        reader.onOpen = { authorized = false }
        var lastChecked = 0
        val failure = runCatching {
            CleanupScanner(reader, CleanupReadAdmission()).scan(emptySet(), { if (!authorized) throw CancellationException() }, {}, {}, { lastChecked = it.checked })
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals(0, lastChecked)
    }

    private class FakeReader(val rows: MutableList<MediaItem> = mutableListOf(cleanupTestItem(1), cleanupTestItem(2))) : CleanupSourceReader {
        var onOpen: (MediaItem) -> Unit = {}
        var onStream: (MediaItem) -> InputStream = { ByteArrayInputStream("abc".toByteArray()) }
        var onProbe: ((MediaItem) -> MediaItem?)? = null
        var onValidate: () -> Unit = {}
        var validations = 0
        override suspend fun snapshot(favorites: Set<String>) = CleanupSourceSnapshot(rows.toList(), listOf(CatalogueNamespace("media", "external_primary", "v", 5)), emptyMap(), MediaAccess(30, legacy = true))
        override suspend fun probe(item: MediaItem): MediaItem? = if (onProbe != null) onProbe?.invoke(item) else rows.singleOrNull { it.id == item.id }
        override suspend fun validateScope(snapshot: CleanupSourceSnapshot) { validations++; onValidate() }
        override fun open(item: MediaItem): InputStream { onOpen(item); return onStream(item) }
    }
}
