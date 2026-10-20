package app.still.gallery.ui

import app.still.gallery.catalogue.CatalogueNamespace
import app.still.gallery.media.*
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class CleanupCoordinatorTest {
    private val access = MediaAccess(30, legacy = true)

    @Test fun stopBeforeDefaultLaunchEntersBodyAcknowledgesActualCompletion() {
        val dispatcher = QueuedDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        var opens = 0
        val coordinator = CleanupCoordinator(scope, GalleryActionLifetime { true },
            { Reader { opens++; ByteArrayInputStream("abc".toByteArray()) } },
            { error("Annotations are not used by this scan") }, dispatcher)
        try {
            coordinator.start(coordinator.actionIdentity, bindings())
            assertEquals(CleanupPhase.SCANNING, coordinator.display(access, true).phase)
            coordinator.stop(coordinator.currentScan)
            assertEquals(CleanupPhase.STOPPING, coordinator.display(access, true).phase)
            dispatcher.drain()
            assertEquals(CleanupPhase.CANCELLED, coordinator.display(access, true).phase)
            assertEquals(0, opens)
            assertNull(coordinator.currentReview)
        } finally { scope.cancel(); dispatcher.drain() }
    }

    @Test fun stoppingWaitsForBlockedReadAndCloseBeforeAcknowledgingAndReleasingLane() = runBlocking {
        val enteredRead = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val enteredClose = CountDownLatch(1)
        val releaseClose = CountDownLatch(1)
        val closed = AtomicBoolean(false)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = CleanupCoordinator(scope, GalleryActionLifetime { true },
            { Reader { object : InputStream() {
                override fun read() = error("Unused")
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    enteredRead.countDown(); check(releaseRead.await(5, TimeUnit.SECONDS)); buffer[0] = 1; return 1
                }
                override fun close() {
                    enteredClose.countDown(); check(releaseClose.await(5, TimeUnit.SECONDS)); closed.set(true)
                }
            } } }, { error("Annotations are not used by this scan") }, Dispatchers.Unconfined)
        try {
            coordinator.start(coordinator.actionIdentity, bindings())
            assertTrue(withContext(Dispatchers.IO) { enteredRead.await(5, TimeUnit.SECONDS) })
            coordinator.stop(coordinator.currentScan)
            assertEquals(CleanupPhase.STOPPING, coordinator.display(access, true).phase)
            assertNull(cleanupProcessReads.tryEnter())
            releaseRead.countDown()
            assertTrue(withContext(Dispatchers.IO) { enteredClose.await(5, TimeUnit.SECONDS) })
            assertEquals(CleanupPhase.STOPPING, coordinator.display(access, true).phase)
            assertNull(cleanupProcessReads.tryEnter())
            releaseClose.countDown()
            withTimeout(5_000) { while (coordinator.display(access, true).phase != CleanupPhase.CANCELLED) delay(5) }
            assertTrue(closed.get())
            val lease = cleanupProcessReads.tryEnter()
            assertNotNull(lease)
            lease?.close()
            assertNull(coordinator.currentReview)
        } finally { releaseRead.countDown(); releaseClose.countDown(); scope.cancel() }
    }

    @Test fun oldCompletionCannotPublishAfterInvalidationOrIntoFreshGallery() {
        val dispatcher = QueuedDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val lifetime = GalleryActionLifetime { true }
        val old = CleanupCoordinator(scope, lifetime, { Reader { ByteArrayInputStream("abc".toByteArray()) } },
            { error("Unused") }, dispatcher)
        val fresh = CleanupCoordinator(scope, GalleryActionLifetime { true }, { Reader { ByteArrayInputStream("abc".toByteArray()) } },
            { error("Unused") }, dispatcher)
        try {
            old.start(old.actionIdentity, bindings())
            old.stop(old.currentScan)
            old.invalidate()
            lifetime.invalidate()
            dispatcher.drain()
            assertEquals(CleanupPhase.INVALIDATED, old.display(access, true).phase)
            assertEquals(CleanupPhase.IDLE, fresh.display(access, true).phase)
            fresh.start(fresh.actionIdentity, bindings())
            fresh.stop(fresh.currentScan)
            dispatcher.drain()
            assertEquals(CleanupPhase.CANCELLED, fresh.display(access, true).phase)
            assertNull(old.currentReview)
            assertNull(fresh.currentReview)
        } finally { scope.cancel(); dispatcher.drain() }
    }

    private fun bindings() = CleanupStartBindings(access, emptySet(), { true }, { true }, { emptySet() }, { emptyMap() }, {})

    private class Reader(private val stream: () -> InputStream) : CleanupSourceReader {
        private val rows = listOf(cleanupTestItem(1), cleanupTestItem(2))
        override fun checkAccess() {}
        override suspend fun snapshot(favorites: Set<String>) = CleanupSourceSnapshot(rows, listOf(CatalogueNamespace("media", "external_primary", "v", 5)), emptyMap(), MediaAccess(30, legacy = true))
        override suspend fun probe(item: MediaItem) = item
        override suspend fun validateScope(snapshot: CleanupSourceSnapshot) {}
        override fun open(item: MediaItem) = stream()
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val queue = ConcurrentLinkedQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queue.add(block) }
        fun drain() {
            var calls = 0
            while (true) {
                val next = queue.poll() ?: return
                check(++calls < 1000) { "Synthetic dispatcher did not become idle" }
                next.run()
            }
        }
    }
}
