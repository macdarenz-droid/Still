package app.still.gallery.ui

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

class CompareCoordinatorTest {
    @Test fun recoveryCompletesAfterOrdinaryInvalidationWithoutStickingInCleaning() = runBlocking {
        val recover = CompletableDeferred<CompareCleanup>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val owner = coordinator(scope, recover = { recover.await() })
        try {
            owner.initialize()
            assertEquals(ComparePhase.CLEANING, owner.state.phase)
            owner.invalidate()
            recover.complete(CompareCleanup.CLEAN)
            assertEquals(ComparePhase.IDLE, owner.state.phase)
            assertTrue(owner.canStart)
            start(owner, Work())
            assertEquals(ComparePhase.READY, owner.state.phase)
        } finally { owner.dispose(); scope.cancel() }
    }

    @Test fun parentCancellationBeforeWorkerBodyStillCleansAlreadyAcquiredWork() {
        val dispatcher = QueueDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val owner = coordinator(scope)
        val work = Work()
        owner.initialize()
        start(owner, work)
        assertEquals(ComparePhase.PREPARING, owner.state.phase)
        scope.cancel()
        dispatcher.drain()
        assertEquals(0, work.prepared)
        assertEquals(1, work.cleaned)
        assertTrue(work.invalidated)
        assertEquals(ComparePhase.CANCELLED, owner.state.phase)
        assertTrue(owner.state.previews.isEmpty())
        owner.dispose()
    }

    @Test fun stopBeforeQueuedWorkerStartsWaitsForCompletionAndAcknowledgesStop() {
        val dispatcher = QueueDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val owner = coordinator(scope)
        val work = Work()
        try {
            owner.initialize()
            start(owner, work)
            owner.stop(owner.state.request)
            assertEquals(ComparePhase.STOPPING, owner.state.phase)
            assertEquals(0, work.cleaned)
            dispatcher.drain()
            assertEquals(ComparePhase.CANCELLED, owner.state.phase)
            assertEquals(0, work.prepared)
            assertEquals(1, work.cleaned)
        } finally { owner.dispose(); scope.cancel(); dispatcher.drain() }
    }

    @Test fun synchronousPreparationFailureHasAssignedWorkerAndRecoversOnClose() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val owner = coordinator(scope)
        val work = Work { throw CompareWorkException(CompareFailure.SIZE_LIMIT) }
        try {
            owner.initialize()
            start(owner, work)
            assertEquals(ComparePhase.FAILED, owner.state.phase)
            assertEquals(CompareFailure.SIZE_LIMIT, owner.state.failure)
            assertEquals(1, work.cleaned)
            assertTrue(owner.state.previews.isEmpty())
            owner.close(owner.state.request)
            assertTrue(owner.canStart)
            start(owner, Work())
            assertEquals(ComparePhase.READY, owner.state.phase)
        } finally { owner.dispose(); scope.cancel() }
    }

    @Test fun stopHidesPairButCleanupWaitsUntilBlockingPreparationActuallyExits() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val owner = coordinator(scope)
        val work = Work {
            withContext(Dispatchers.IO) {
                try { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); emptyList() }
                finally { exited.countDown() }
            }
        }
        try {
            owner.initialize()
            start(owner, work)
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            owner.stop(owner.state.request)
            assertEquals(ComparePhase.STOPPING, owner.state.phase)
            assertEquals(0, work.cleaned)
            assertTrue(owner.state.previews.isEmpty())
            release.countDown()
            withTimeout(5_000) { while (work.cleaned == 0) delay(5) }
            assertEquals(0, exited.count)
            assertEquals(ComparePhase.CANCELLED, owner.state.phase)
        } finally { release.countDown(); owner.dispose(); scope.cancel() }
    }

    @Test fun oldOpeningActionsAndCleanupRetryCannotActOnSameLookingNewPair() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val owner = coordinator(scope)
        val first = Work().apply { cleanupResult = CompareCleanup.FAILED }
        try {
            owner.initialize()
            start(owner, first)
            val old = owner.state.request
            owner.close(old)
            assertEquals(ComparePhase.CLEANUP_FAILED, owner.state.phase)
            val oldRetry = owner.actionIdentity
            first.cleanupResult = CompareCleanup.CLEAN
            owner.retryCleanup(oldRetry)
            assertTrue(owner.canStart)
            val second = Work()
            start(owner, second)
            val current = owner.state.request
            owner.change(current, 2f, .2f, -.2f)
            val changed = owner.state.transform
            owner.close(old); owner.stop(old); owner.reset(old); owner.change(old, 2f, 1f, 1f)
            owner.retryCleanup(oldRetry)
            assertSame(current, owner.state.request)
            assertEquals(ComparePhase.READY, owner.state.phase)
            assertEquals(changed, owner.state.transform)
            assertEquals(0, second.cleaned)
            owner.reset(current)
            assertEquals(CompareTransform(), owner.state.transform)
        } finally { owner.dispose(); scope.cancel() }
    }

    @Test fun authorityLossHidesPreviewsBeforeDisposalAndLateResultCannotReviveThem() = runBlocking {
        var allowed = true
        val waiting = CompletableDeferred<List<Preview>>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val owner = coordinator(scope, { allowed })
        val work = Work { waiting.await() }
        try {
            owner.initialize()
            start(owner, work)
            val request = owner.state.request
            allowed = false
            assertFalse(owner.state.visible)
            assertTrue(owner.state.previews.isEmpty())
            allowed = true
            waiting.complete(work.previews)
            assertFalse(owner.state.visible)
            assertTrue(owner.state.previews.isEmpty())
            assertEquals(1, work.cleaned)
            owner.change(request, 5f)
            assertEquals(CompareTransform(), owner.state.transform)
            owner.close(request)
            assertTrue(owner.canStart)
        } finally { owner.dispose(); scope.cancel() }
    }

    @Test fun failedCleanupStaysVisibleOutsideClosedDialogAndBlocksFurtherStaging() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val owner = coordinator(scope)
        val work = Work().apply { cleanupResult = CompareCleanup.FAILED }
        try {
            owner.initialize()
            start(owner, work)
            owner.close(owner.state.request)
            assertFalse(owner.state.visible)
            assertEquals(ComparePhase.CLEANUP_FAILED, owner.state.phase)
            assertFalse(owner.canStart)
            var extraWork = 0
            owner.start(owner.actionIdentity, listOf("photo", "photo"), { true }) { extraWork++; Work() }
            assertEquals(0, extraWork)
            work.cleanupResult = CompareCleanup.CLEAN
            owner.retryCleanup(owner.actionIdentity)
            assertTrue(owner.canStart)
        } finally { owner.dispose(); scope.cancel() }
    }

    @Test fun sourceLossHidesBothPreviewsAndCleansWithoutClosingTheVanishedOpening() {
        var sourcesCurrent = true
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val owner = coordinator(scope)
        val work = Work()
        try {
            owner.initialize()
            owner.start(owner.actionIdentity, listOf("a", "b"), { sourcesCurrent }) { work }
            val request = owner.state.request
            assertEquals(2, owner.state.previews.size)
            sourcesCurrent = false
            assertFalse(owner.state.visible)
            assertTrue(owner.state.previews.isEmpty())
            sourcesCurrent = true
            assertFalse(owner.state.visible)
            owner.change(request, 5f)
            assertEquals(CompareTransform(), owner.state.transform)
            assertEquals(1, work.cleaned)
            assertTrue(owner.canStart)
        } finally { owner.dispose(); scope.cancel() }
    }

    @Test fun disposedOwnerAndOldActionIdentityCannotAcquireMoreWork() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val owner = coordinator(scope)
        owner.initialize()
        val oldAction = owner.actionIdentity
        owner.invalidate()
        var acquired = 0
        owner.start(oldAction, listOf("photo", "photo"), { true }) { acquired++; Work() }
        owner.dispose()
        owner.start(owner.actionIdentity, listOf("photo", "photo"), { true }) { acquired++; Work() }
        assertEquals(0, acquired)
        scope.cancel()
    }

    @Test fun selectionChangeAwayAndBackRejectsOldCompareButFreshActionWorks() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val owner = coordinator(scope)
        try {
            owner.initialize()
            val pair = listOf("same.jpg", "same.jpg")
            val previousAction = owner.actionIdentity
            owner.invalidate() // One selection changes.
            owner.invalidate() // The same selected pair is chosen again.
            var acquired = 0
            owner.start(previousAction, pair, { true }) { acquired++; Work() }
            assertEquals(0, acquired)
            owner.start(owner.actionIdentity, pair, { true }) { acquired++; Work() }
            assertEquals(1, acquired)
            assertEquals(ComparePhase.READY, owner.state.phase)
        } finally { owner.dispose(); scope.cancel() }
    }

    private fun coordinator(scope: CoroutineScope, allowed: () -> Boolean = { true }, recover: suspend () -> CompareCleanup = { CompareCleanup.CLEAN }) =
        CompareCoordinator<Preview>(scope, allowed, recover, Dispatchers.Unconfined, Dispatchers.Unconfined)
    private fun start(owner: CompareCoordinator<Preview>, work: Work) = owner.start(owner.actionIdentity, listOf("same.jpg", "same.jpg"), { true }) { work }
    private class Preview : AutoCloseable { var closed = false; override fun close() { closed = true } }
    private class Work(private val prepare: (suspend Work.() -> List<Preview>)? = null) : CompareWork<Preview> {
        val previews = listOf(Preview(), Preview())
        var prepared = 0
        @Volatile var cleaned = 0
        var invalidated = false
        var cleanupResult = CompareCleanup.CLEAN
        override suspend fun prepare(progress: (CompareProgress) -> Unit): List<Preview> { prepared++; return prepare?.invoke(this) ?: previews }
        override fun invalidate() { invalidated = true }
        override suspend fun cleanup(): CompareCleanup { cleaned++; previews.forEach { it.close() }; return cleanupResult }
    }
    private class QueueDispatcher : CoroutineDispatcher() {
        private val queue = ConcurrentLinkedQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queue.add(block) }
        fun drain() { while (true) (queue.poll() ?: return).run() }
    }
}
