package app.still.gallery.ui

import app.still.gallery.media.CleanupSourceReader
import app.still.gallery.media.CleanupSourceSnapshot
import app.still.gallery.media.MediaItem
import app.still.gallery.similarity.SimilarityFeature
import app.still.gallery.similarity.SimilarityIndexCandidates
import app.still.gallery.similarity.SimilarityIndexClearSession
import app.still.gallery.similarity.SimilarityIndexEntry
import app.still.gallery.similarity.SimilarityIndexNamespace
import app.still.gallery.similarity.SimilarityIndexProgress
import app.still.gallery.similarity.SimilarityIndexRunControl
import app.still.gallery.similarity.SimilarityIndexStoreSession
import app.still.gallery.similarity.SimilaritySettleResult
import java.io.InputStream
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SimilarityIndexCoordinatorTest {
    @Test fun stoppingBeforeQueuedIndexWorkerEntersClosesStoreAndReleasesCoordinator() {
        val dispatcher = QueueDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val stores = mutableListOf<FakeStore>()
        val work = FakeRun()
        val coordinator = SimilarityIndexCoordinator(
            scope = scope,
            allowed = { true },
            openStore = { FakeStore().also(stores::add) },
            openClearStore = { FakeStore() },
            createWork = { _, _, _, _ -> work },
            publicationDispatcher = Dispatchers.Unconfined,
            ioDispatcher = Dispatchers.Unconfined,
            settlementDispatcher = Dispatchers.Unconfined,
        )
        try {
            coordinator.initialize()
            dispatcher.drain()
            assertEquals(SimilarityIndexPhase.IDLE, coordinator.state.phase)

            coordinator.start(coordinator.state.action, FakeReader(), emptySet(), {}, {})
            assertEquals(SimilarityIndexPhase.RUNNING, coordinator.state.phase)
            coordinator.stop()
            dispatcher.drain()

            assertEquals(0, work.runCalls)
            assertEquals(1, work.invalidateCalls)
            assertEquals(1, work.settleCalls)
            assertTrue(stores.last().closed)
            assertEquals(SimilarityIndexPhase.STOPPED, coordinator.state.phase)
            assertTrue("The settled run must not retain work or block another action", coordinator.canStart)
        } finally {
            coordinator.dispose()
            scope.cancel()
            dispatcher.drain()
        }
    }

    @Test fun alreadyCancelledParentStillSettlesWorkCreatedByExplicitStart() {
        val dispatcher = QueueDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val stores = mutableListOf<FakeStore>()
        val work = FakeRun()
        val coordinator = SimilarityIndexCoordinator(
            scope, { true }, { FakeStore().also(stores::add) }, { FakeStore() }, { _, _, _, _ -> work },
            Dispatchers.Unconfined, Dispatchers.Unconfined, Dispatchers.Unconfined,
        )
        try {
            coordinator.initialize()
            dispatcher.drain()
            scope.cancel()

            coordinator.start(coordinator.state.action, FakeReader(), emptySet(), {}, {})
            dispatcher.drain()

            assertEquals(0, work.runCalls)
            assertEquals(1, work.settleCalls)
            assertTrue(stores.last().closed)
            assertEquals(SimilarityIndexPhase.STOPPED, coordinator.state.phase)
            assertTrue(coordinator.canStart)
        } finally {
            coordinator.dispose()
            dispatcher.drain()
        }
    }

    private class FakeRun : SimilarityIndexRunControl {
        var runCalls = 0
        var invalidateCalls = 0
        var settleCalls = 0
        override fun invalidate() { invalidateCalls++ }
        override suspend fun run(onSnapshot: suspend (CleanupSourceSnapshot) -> Unit,
            onProgress: suspend (SimilarityIndexProgress) -> Unit): SimilarityIndexProgress {
            runCalls++
            error("A canceled queued worker must not enter run")
        }
        override suspend fun settle(): SimilaritySettleResult { settleCalls++; return SimilaritySettleResult.CLEAN }
    }

    private class FakeStore : SimilarityIndexStoreSession, SimilarityIndexClearSession {
        var closed = false
        override fun revisions(): Map<String, String> = emptyMap()
        override fun candidates(feature: SimilarityFeature, maximum: Int) = SimilarityIndexCandidates(emptyList(), false)
        override fun features(sourceRevisions: Map<String, String>, sourceKeys: List<String>): Map<String, SimilarityFeature> = emptyMap()
        override fun reconcileNamespaces(namespaces: List<SimilarityIndexNamespace>, checkCurrent: () -> Unit): Map<String, String> = emptyMap()
        override fun retainCurrent(identities: Map<String, String>, checkCurrent: () -> Unit) = Unit
        override fun upsertPage(entries: List<SimilarityIndexEntry>, checkCurrent: () -> Unit) = Unit
        override fun count(): Int = 0
        override fun clear() = Unit
        override fun close() { closed = true }
    }

    private class FakeReader : CleanupSourceReader {
        override fun checkAccess() = Unit
        override suspend fun snapshot(favorites: Set<String>): CleanupSourceSnapshot = error("The queued worker must not read")
        override suspend fun probe(item: MediaItem): MediaItem? = error("The queued worker must not read")
        override suspend fun validateScope(snapshot: CleanupSourceSnapshot) = error("The queued worker must not read")
        override fun open(item: MediaItem): InputStream = error("The queued worker must not read")
    }

    private class QueueDispatcher : CoroutineDispatcher() {
        private val queue = ConcurrentLinkedQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queue.add(block) }
        fun drain() { while (true) (queue.poll() ?: return).run() }
    }
}
