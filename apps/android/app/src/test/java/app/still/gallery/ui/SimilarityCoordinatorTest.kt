package app.still.gallery.ui

import app.still.gallery.media.MediaItem
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
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

class SimilarityCoordinatorTest {
    @Test fun initializationSurvivesOrdinaryInvalidationAndStillAllowsCurrentSearch() = runBlocking {
        val gate = CompletableDeferred<SimilaritySettlement>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val owner = owner(scope, recover = { gate.await() })
        try {
            owner.initialize(); owner.invalidate()
            assertEquals(SimilarityPhase.SETTLING, owner.state.phase)
            gate.complete(SimilaritySettlement.CLEAN)
            assertTrue(owner.canStart)
            start(owner, Work())
            assertEquals(SimilarityPhase.READY, owner.state.phase)
        } finally { owner.dispose(); scope.cancel() }
    }

    @Test fun canceledBeforeWorkerBodyStillSettlesAdmittedTokenExactlyOnce() {
        val dispatcher = QueueDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val owner = owner(scope)
        val work = Work()
        owner.initialize(); start(owner, work)
        scope.cancel(); dispatcher.drain()
        assertEquals(0, work.scanned)
        assertEquals(1, work.settled)
        assertFalse(work.active)
        assertEquals(SimilarityPhase.STOPPED, owner.state.phase)
        assertNull(owner.state.result)
        owner.dispose()
    }

    @Test fun stoppingNeverStartedWorkerAcknowledgesExitInsteadOfRemainingStopping() {
        val dispatcher = QueueDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val owner = owner(scope)
        val work = Work()
        try {
            owner.initialize(); start(owner, work)
            owner.stop(owner.state.request)
            assertEquals(SimilarityPhase.STOPPING, owner.state.phase)
            assertEquals(0, work.settled)
            dispatcher.drain()
            assertEquals(SimilarityPhase.STOPPED, owner.state.phase)
            assertEquals(1, work.settled)
        } finally { owner.dispose(); scope.cancel(); dispatcher.drain() }
    }

    @Test fun stoppedBlockedWorkerKeepsOwnershipUntilActualExit() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val owner = owner(scope)
        val work = Work { withContext(Dispatchers.IO) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); found } }
        try {
            owner.initialize(); start(owner, work)
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            owner.stop(owner.state.request)
            assertNull(owner.state.result)
            assertEquals(SimilarityPhase.STOPPING, owner.state.phase)
            assertEquals(0, work.settled)
            release.countDown()
            withTimeout(5_000) { while (owner.state.phase == SimilarityPhase.STOPPING) delay(5) }
            assertEquals(1, work.settled)
            assertEquals(SimilarityPhase.STOPPED, owner.state.phase)
        } finally { release.countDown(); owner.dispose(); scope.cancel() }
    }

    @Test fun synchronousFailureUsesAssignedJobAndCanStartAgainAfterClose() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val owner = owner(scope)
        val work = Work { throw SimilarityWorkException(SimilarityFailure.SCOPE_LIMIT) }
        try {
            owner.initialize(); start(owner, work)
            assertEquals(SimilarityPhase.FAILED, owner.state.phase)
            assertEquals(SimilarityFailure.SCOPE_LIMIT, owner.state.failure)
            assertEquals(1, work.settled)
            owner.close(owner.state.request)
            assertTrue(owner.canStart)
        } finally { owner.dispose(); scope.cancel() }
    }

    @Test fun wholeScopeWithdrawalRemovesResultAndSettlesWithoutClosingHiddenDialog() {
        var current = true
        val withdrawn = mutableListOf<SimilarityRequest>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val owner = owner(scope, onWithdraw = { withdrawn += it })
        val work = Work()
        try {
            owner.initialize()
            owner.start(owner.actionIdentity, 2, { current }) { work }
            val request = owner.state.request
            assertNotNull(owner.state.result)
            current = false
            assertFalse(owner.state.visible)
            assertNull(owner.state.result)
            assertEquals(listOf(request), withdrawn)
            assertEquals(1, work.settled)
            assertTrue(owner.canStart)
            current = true
            owner.close(request)
            assertFalse(owner.state.visible)
        } finally { owner.dispose(); scope.cancel() }
    }

    @Test fun backendTicketWithdrawalAlsoHidesAndSettlesReadyResult() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val owner = owner(scope)
        val work = Work()
        try {
            owner.initialize(); start(owner, work)
            work.active = false
            assertFalse(owner.state.visible)
            assertNull(owner.state.result)
            assertEquals(1, work.settled)
            assertTrue(owner.canStart)
        } finally { owner.dispose(); scope.cancel() }
    }

    @Test fun failedCloseRemainsVisibleOutsideReviewAndOnlyCurrentRetrySettlesIt() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val owner = owner(scope)
        val old = Work().apply { settlement = SimilaritySettlement.CLOSE_FAILED }
        try {
            owner.initialize(); start(owner, old)
            owner.close(owner.state.request)
            assertFalse(owner.state.visible)
            assertEquals(SimilarityPhase.CLOSE_FAILED, owner.state.phase)
            assertFalse(owner.canStart)
            val oldRetry = owner.actionIdentity
            old.settlement = SimilaritySettlement.CLEAN
            owner.retry(oldRetry)
            assertTrue(owner.canStart)
            val next = Work(); start(owner, next)
            owner.retry(oldRetry)
            assertEquals(SimilarityPhase.READY, owner.state.phase)
            assertEquals(0, next.settled)
        } finally { owner.dispose(); scope.cancel() }
    }

    @Test fun sameLookingReplacementRejectsOldRequestResultAndPair() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val owner = owner(scope)
        val old = Work()
        try {
            owner.initialize(); start(owner, old)
            val request = owner.state.request
            assertEquals(2, owner.resolve(request, old.found, old.found.pair)?.size)
            assertNull(owner.resolve(request, old.found, Any()))
            owner.close(request)
            val next = Work(); start(owner, next)
            val nextRequest = owner.state.request
            owner.close(request); owner.stop(request)
            assertSame(nextRequest, owner.state.request)
            assertNull(owner.resolve(request, old.found, old.found.pair))
            assertNull(owner.resolve(nextRequest, old.found, old.found.pair))
            assertEquals(2, owner.resolve(nextRequest, next.found, next.found.pair)?.size)
        } finally { owner.dispose(); scope.cancel() }
    }

    @Test fun selectionABAAndOutOfBoundsActionsCannotAcquireAnotherRead() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val owner = owner(scope)
        try {
            owner.initialize()
            val old = owner.actionIdentity
            owner.invalidate(); owner.invalidate()
            var acquisitions = 0
            owner.start(old, 2, { true }) { acquisitions++; Work() }
            for (count in listOf(0, 1, 201)) owner.start(owner.actionIdentity, count, { true }) { acquisitions++; Work() }
            assertEquals(0, acquisitions)
            owner.start(owner.actionIdentity, 2, { true }) { acquisitions++; Work() }
            assertEquals(1, acquisitions)
        } finally { owner.dispose(); scope.cancel() }
    }

    @Test fun retryFromVisibleFailedCloseAcknowledgesSettlementWithoutRestartingSearch() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val owner = owner(scope)
        val work = Work { throw SimilarityWorkException(SimilarityFailure.CLOSE_FAILED) }.apply { settlement = SimilaritySettlement.CLOSE_FAILED }
        try {
            owner.initialize(); start(owner, work)
            assertEquals(SimilarityPhase.CLOSE_FAILED, owner.state.phase)
            assertTrue(owner.state.visible)
            work.settlement = SimilaritySettlement.CLEAN
            owner.retry(owner.actionIdentity)
            assertEquals(SimilarityPhase.STOPPED, owner.state.phase)
            assertNull(owner.state.result)
            assertEquals(1, work.scanned)
            owner.close(owner.state.request)
            assertTrue(owner.canStart)
        } finally { owner.dispose(); scope.cancel() }
    }

    @Test fun retryAfterAdmissionFailureDoesNotLeaveIdleDialogWithStaleFailureMessage() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val owner = owner(scope)
        try {
            owner.initialize()
            owner.start(owner.actionIdentity, 2, { true }) { throw SimilarityWorkException(SimilarityFailure.CLOSE_FAILED) }
            assertEquals(SimilarityPhase.CLOSE_FAILED, owner.state.phase)
            owner.retry(owner.actionIdentity)
            assertEquals(SimilarityPhase.STOPPED, owner.state.phase)
            assertEquals(SimilarityFailure.UNAVAILABLE, owner.state.failure)
            assertFalse(owner.canStart)
            owner.close(owner.state.request)
            assertTrue(owner.canStart)
        } finally { owner.dispose(); scope.cancel() }
    }

    private fun owner(scope: CoroutineScope, recover: suspend () -> SimilaritySettlement = { SimilaritySettlement.CLEAN },
        onWithdraw: (SimilarityRequest) -> Unit = {}) = SimilarityCoordinator<Result>(scope, { true }, recover, onWithdraw, Dispatchers.Unconfined, Dispatchers.Unconfined)
    private fun start(owner: SimilarityCoordinator<Result>, work: Work) = owner.start(owner.actionIdentity, 2, { true }) { work }
    private class Result { val pair = Any() }
    private class Work(private val scan: (suspend Work.() -> Result)? = null) : SimilarityWork<Result> {
        val found = Result()
        var active = true
        var scanned = 0
        @Volatile var settled = 0
        var settlement = SimilaritySettlement.CLEAN
        override suspend fun scan(progress: suspend (SimilarityCoverageUi) -> Unit): Result { scanned++; progress(SimilarityCoverageUi(selected = 2, attempted = 2, featured = 2)); return scan?.invoke(this) ?: found }
        override fun isCurrent(result: Result): Boolean = active && result === found
        override fun resolve(result: Result, pair: Any): List<MediaItem>? = if (isCurrent(result) && pair === found.pair) listOf(photo(1), photo(2)) else null
        override fun invalidate() { active = false }
        override suspend fun settle(): SimilaritySettlement { settled++; return settlement }
        private fun photo(id: Int) = MediaItem("content://media/external_primary/images/media/$id", "same.jpg", "image/jpeg", false, 100, 100, 100, 100, 0, "album", "Album")
    }
    private class QueueDispatcher : CoroutineDispatcher() {
        private val queue = ConcurrentLinkedQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queue.add(block) }
        fun drain() { while (true) (queue.poll() ?: return).run() }
    }
}
