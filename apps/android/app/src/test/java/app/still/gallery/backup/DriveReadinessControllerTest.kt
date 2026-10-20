package app.still.gallery.backup

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class DriveReadinessControllerTest {
    private val account = ChosenDriveAccount(DriveAccountDisplay("chosen@example.com", "Chosen"), "a".repeat(64))
    private val grant = DriveGrant("fixture-token", setOf(DRIVE_FILE_SCOPE))
    private val quota = DriveStorageQuota(1_000, 100)
    private fun owner() = DriveConnectionSession(true, null) { true }.also { it.complete(it.begin(it.state.action)!!, account, grant) }
    private fun transport(run: suspend (DriveAccessLease, DriveReadinessRequest) -> DriveStorageQuota) = object : DriveReadinessTransport {
        override suspend fun check(lease: DriveAccessLease, request: DriveReadinessRequest) = run(lease, request)
    }

    @Test fun missingAuthorizationStaleConnectionActionAndHiddenActionNeverDispatch() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined); var calls = 0
        val owner = owner(); val stale = owner.state.action
        owner.begin(stale); owner.cancel(owner.state.action)
        val controller = DriveReadinessController(owner::accessLease, transport { _, _ -> calls++; quota }, scope)
        val disconnected = DriveConnectionSession(true, null) { true }
        val noGrant = DriveReadinessController(disconnected::accessLease, transport { _, _ -> calls++; quota }, scope)
        try {
            controller.check(stale, controller.state.action) { true }
            controller.check(owner.state.action, controller.state.action) { false }
            noGrant.check(disconnected.state.action, noGrant.state.action) { true }
            assertEquals(0, calls); assertEquals(DriveReadinessPhase.IDLE, controller.state.phase)
        } finally { controller.dispose(); noGrant.dispose(); scope.cancel() }
    }

    @Test fun explicitCheckPublishesQuotaAndUnknownFieldsStayUnknown() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined); val owner = owner(); var calls = 0
        val controller = DriveReadinessController(owner::accessLease, transport { lease, request ->
            calls++; assertTrue(lease.isCurrent()); request.check(); DriveStorageQuota(null, null)
        }, scope)
        try {
            assertEquals(0, calls)
            controller.check(owner.state.action, controller.state.action) { true }
            assertEquals(1, calls); assertEquals(DriveReadinessPhase.CHECKED, controller.state.phase)
            assertEquals(DriveStorageQuota(null, null), controller.state.quota)
        } finally { controller.dispose(); scope.cancel() }
    }

    @Test fun blockedResponseCannotPublishAfterHideCancelLockOrDisposal() = runBlocking {
        for (withdraw in listOf("hide", "cancel", "lock", "dispose")) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined); val owner = owner()
            val late = CompletableDeferred<DriveStorageQuota>(); var allowed = true; var request: DriveReadinessRequest? = null
            val controller = DriveReadinessController(owner::accessLease, transport { _, held -> request = held; withContext(NonCancellable) { late.await() } }, scope)
            try {
                controller.check(owner.state.action, controller.state.action) { allowed }
                assertEquals(DriveReadinessPhase.CHECKING, controller.state.phase)
                when (withdraw) { "hide" -> controller.hide(); "cancel" -> controller.cancel(controller.state.action)
                    "lock" -> { allowed = false; controller.hide() }; else -> controller.dispose() }
                val expected = if (withdraw == "cancel") DriveReadinessPhase.FAILED else DriveReadinessPhase.IDLE
                assertEquals(expected, controller.state.phase)
                if (withdraw == "cancel") assertEquals(DriveReadinessError.CANCELLED, controller.state.error)
                try { request!!.check(); fail("withdrawn request") } catch (_: DriveReadinessFailure) { }
                late.complete(quota)
                assertEquals(expected, controller.state.phase); assertNull(controller.state.quota)
                if (withdraw == "cancel") assertEquals(DriveReadinessError.CANCELLED, controller.state.error)
            } finally { late.complete(quota); controller.dispose(); scope.cancel() }
        }
    }

    @Test fun explicitCancelAnnouncesCancellationAndLateSuccessCannotReplaceIt() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined); val owner = owner()
        val late = CompletableDeferred<DriveStorageQuota>(); var aborts = 0
        val controller = DriveReadinessController(owner::accessLease, transport { _, request ->
            request.attachAbort { aborts++ }; withContext(NonCancellable) { late.await() }
        }, scope)
        try {
            controller.check(owner.state.action, controller.state.action) { true }
            controller.cancel(controller.state.action)
            assertEquals(1, aborts); assertEquals(DriveReadinessPhase.FAILED, controller.state.phase)
            assertEquals(DriveReadinessError.CANCELLED, controller.state.error); assertNull(controller.state.quota)
            late.complete(quota)
            assertEquals(DriveReadinessPhase.FAILED, controller.state.phase)
            assertEquals(DriveReadinessError.CANCELLED, controller.state.error); assertNull(controller.state.quota)
            assertEquals(DriveConnectionPhase.CONNECTED, owner.state.phase)
            controller.hide(); assertEquals(DriveReadinessPhase.IDLE, controller.state.phase)
        } finally { late.complete(quota); controller.dispose(); scope.cancel() }
    }

    @Test fun switchStartDisconnectAndSameAccountReconnectWithdrawBeforeAnyLateResponse() = runBlocking {
        for (change in listOf("switch_cancel", "disconnect", "reconnect")) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined); val owner = owner()
            val late = CompletableDeferred<DriveStorageQuota>()
            val readiness = DriveReadinessController(owner::accessLease, transport { _, _ -> withContext(NonCancellable) { late.await() } }, scope)
            val signing = CompletableDeferred<ChosenDriveAccount>()
            val connection = DriveConnectionController(owner, object : DriveConnectionGateway {
                override suspend fun chooseAccount() = signing.await()
                override suspend fun authorize(account: ChosenDriveAccount) = grant
            }, scope, readiness::hide)
            try {
                readiness.check(owner.state.action, readiness.state.action) { true }
                if (change == "disconnect") connection.disconnect(connection.state.action)
                else {
                    connection.connect(connection.state.action)
                    assertEquals(DriveReadinessPhase.IDLE, readiness.state.phase)
                    if (change == "switch_cancel") connection.cancel(connection.state.action) else signing.complete(account)
                }
                late.complete(quota)
                assertEquals(DriveReadinessPhase.IDLE, readiness.state.phase); assertNull(readiness.state.quota)
            } finally { signing.complete(account); late.complete(quota); readiness.dispose(); connection.dispose(); scope.cancel() }
        }
    }

    @Test fun oldFailureCannotOverwriteSuccessfulNewCheckOrInvalidateItsConnection() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined); val owner = owner()
        val late = CompletableDeferred<Unit>(); var calls = 0
        val controller = DriveReadinessController(owner::accessLease, transport { _, _ ->
            if (++calls == 1) { withContext(NonCancellable) { late.await() }; throw DriveReadinessFailure(DriveReadinessError.NEEDS_SIGN_IN) }; quota
        }, scope)
        try {
            controller.check(owner.state.action, controller.state.action) { true }; val staleCancel = controller.state.action
            controller.hide(); controller.check(owner.state.action, controller.state.action) { true }
            controller.cancel(staleCancel); late.complete(Unit)
            assertEquals(2, calls); assertEquals(DriveReadinessPhase.CHECKED, controller.state.phase); assertEquals(quota, controller.state.quota)
            assertEquals(DriveConnectionPhase.CONNECTED, owner.state.phase)
        } finally { late.complete(Unit); controller.dispose(); scope.cancel() }
    }

    @Test fun changedLiveAuthoritySuppressesPublicationEvenWithoutCoroutineCancellation() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined); val owner = owner()
        val late = CompletableDeferred<DriveStorageQuota>(); var allowed = true
        val controller = DriveReadinessController(owner::accessLease, transport { _, _ -> late.await() }, scope)
        try {
            controller.check(owner.state.action, controller.state.action) { allowed }
            allowed = false; late.complete(quota)
            assertEquals(DriveReadinessPhase.IDLE, controller.state.phase); assertNull(controller.state.quota)
        } finally { controller.dispose(); scope.cancel() }
    }

    @Test fun newSettingsPredicateCannotReauthorizeAnOldRequestAcrossViewRevisionAba() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined); val owner = owner()
        val late = CompletableDeferred<DriveStorageQuota>(); var revision = 1
        fun predicate(): () -> Boolean { val origin = revision; return { revision == origin } }
        var currentSettingsPredicate = predicate()
        val controller = DriveReadinessController(owner::accessLease, transport { _, _ -> late.await() }, scope)
        try {
            val origin = currentSettingsPredicate
            controller.check(owner.state.action, controller.state.action, origin)
            revision++; currentSettingsPredicate = predicate()
            assertFalse(origin()); assertTrue(currentSettingsPredicate())
            assertEquals(DriveReadinessPhase.IDLE, controller.state.phase)
            controller.revalidateVisibility()
            late.complete(quota)
            assertEquals(DriveReadinessPhase.IDLE, controller.state.phase); assertNull(controller.state.quota)
        } finally { controller.dispose(); scope.cancel() }
    }

    @Test fun completedQuotaIsHiddenImmediatelyWhenItsCapturedSettingsOriginBecomesStale() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined); val owner = owner(); var revision = 1
        val origin = revision
        val controller = DriveReadinessController(owner::accessLease, transport { _, _ -> quota }, scope)
        try {
            controller.check(owner.state.action, controller.state.action) { revision == origin }
            assertEquals(quota, controller.state.quota)
            revision++
            assertEquals(DriveReadinessPhase.IDLE, controller.state.phase); assertNull(controller.state.quota)
            controller.revalidateVisibility(); assertEquals(DriveReadinessPhase.IDLE, controller.state.phase)
        } finally { controller.dispose(); scope.cancel() }
    }

    @Test fun oneCheckAtATimeAndTerminalFailureCanRetryWithoutAutomaticWork() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined); val owner = owner(); var calls = 0
        val response = CompletableDeferred<DriveStorageQuota>()
        val controller = DriveReadinessController(owner::accessLease, transport { _, _ -> if (++calls == 1) response.await() else quota }, scope)
        try {
            controller.check(owner.state.action, controller.state.action) { true }
            controller.check(owner.state.action, controller.state.action) { true }; assertEquals(1, calls)
            response.completeExceptionally(DriveReadinessFailure(DriveReadinessError.RATE_LIMITED))
            assertEquals(DriveReadinessError.RATE_LIMITED, controller.state.error); assertEquals(1, calls)
            controller.check(owner.state.action, controller.state.action) { true }
            assertEquals(2, calls); assertEquals(DriveReadinessPhase.CHECKED, controller.state.phase)
        } finally { response.complete(quota); controller.dispose(); scope.cancel() }
    }

    @Test fun parentCancellationBeforeLazyWorkerStartsMakesNoNetworkCallOrStuckCheckingState() {
        val queue = Queue(); val scope = CoroutineScope(SupervisorJob() + queue); val owner = owner(); var calls = 0
        val controller = DriveReadinessController(owner::accessLease, transport { _, _ -> calls++; quota }, scope)
        controller.check(owner.state.action, controller.state.action) { true }; scope.cancel(); queue.drain()
        assertEquals(0, calls); assertNotEquals(DriveReadinessPhase.CHECKING, controller.state.phase); controller.dispose()
    }

    private class Queue : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.addLast(block) }
        fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }
}
