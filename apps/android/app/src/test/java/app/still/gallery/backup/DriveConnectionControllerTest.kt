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

class DriveConnectionControllerTest {
    private val account = ChosenDriveAccount(DriveAccountDisplay("a@example.com", "Account A"), "a".repeat(64))
    private val grant = DriveGrant("memory-only", setOf(DRIVE_FILE_SCOPE))

    @Test fun noConfigNeverCallsGoogleAndSignInDoesNotFinishBeforeDriveAuthorization() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val permission = CompletableDeferred<DriveGrant>()
        var signs = 0
        val gateway = gateway(choose = { signs++; account }, authorize = { permission.await() })
        val unavailable = DriveConnectionController(DriveConnectionSession(false, null) { true }, gateway, scope)
        unavailable.connect(unavailable.state.action)
        assertEquals(0, signs)
        val connected = DriveConnectionController(DriveConnectionSession(true, null) { true }, gateway, scope)
        try {
            connected.connect(connected.state.action)
            assertEquals(1, signs)
            assertEquals(DriveConnectionPhase.CONNECTING, connected.state.phase)
            permission.complete(grant)
            assertEquals(DriveConnectionPhase.CONNECTED, connected.state.phase)
        } finally { connected.dispose(); unavailable.dispose(); scope.cancel() }
    }

    @Test fun noAccountReturnedIsSeparateFromPermissionDenialAndCanRetry() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = DriveConnectionController(DriveConnectionSession(true, null) { true },
            gateway(choose = { throw DriveConnectionFailure(DriveConnectionError.NO_ACCOUNT) }), scope)
        try {
            controller.connect(controller.state.action)
            assertEquals(DriveConnectionPhase.DISCONNECTED, controller.state.phase)
            assertEquals(DriveConnectionError.NO_ACCOUNT, controller.state.error)
        } finally { controller.dispose(); scope.cancel() }
    }

    @Test fun lateNonCancellableGrantCannotConnectAfterCancelOrReplaceNewAccount() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val late = CompletableDeferred<DriveGrant>()
        var attempts = 0
        var saves = 0
        val controller = DriveConnectionController(DriveConnectionSession(true, null) { saves++; true },
            gateway(authorize = { if (++attempts == 1) withContext(NonCancellable) { late.await() } else grant }), scope)
        try {
            controller.connect(controller.state.action)
            val staleCancel = controller.state.action
            controller.cancel(staleCancel)
            controller.connect(controller.state.action)
            assertEquals(DriveConnectionPhase.CONNECTED, controller.state.phase)
            controller.cancel(staleCancel)
            late.complete(grant)
            assertEquals(1, saves)
            assertEquals(DriveConnectionPhase.CONNECTED, controller.state.phase)
        } finally { controller.dispose(); scope.cancel() }
    }

    @Test fun parentCancellationBeforeWorkerStartsDoesNotLeaveConnectingForever() {
        val queued = QueuedDispatcher()
        val scope = CoroutineScope(SupervisorJob() + queued)
        var calls = 0
        val controller = DriveConnectionController(DriveConnectionSession(true, null) { true }, gateway(choose = { calls++; account }), scope)
        controller.connect(controller.state.action)
        assertEquals(DriveConnectionPhase.CONNECTING, controller.state.phase)
        scope.cancel()
        queued.drain()
        assertEquals(0, calls)
        assertEquals(DriveConnectionPhase.DISCONNECTED, controller.state.phase)
        controller.dispose()
    }

    @Test fun synchronousFailureCanRetryAndDisconnectDoesNotCallGoogle() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var fail = true
        var saved: SavedDriveAccount? = null
        var signs = 0
        var authorizations = 0
        val controller = DriveConnectionController(DriveConnectionSession(true, null) { saved = it; true },
            gateway(choose = { signs++; if (fail) throw DriveConnectionFailure(DriveConnectionError.SIGN_IN_FAILED); account },
                authorize = { authorizations++; grant }), scope)
        try {
            controller.connect(controller.state.action)
            assertEquals(DriveConnectionPhase.DISCONNECTED, controller.state.phase)
            fail = false
            controller.connect(controller.state.action)
            assertEquals(DriveConnectionPhase.CONNECTED, controller.state.phase)
            assertEquals(2, signs)
            assertEquals(1, authorizations)
            controller.disconnect(controller.state.action)
            assertNull(saved)
            assertNull(controller.state.account)
            assertEquals(DriveConnectionPhase.DISCONNECTED, controller.state.phase)
            assertNull(controller.state.error)
            assertEquals(2, signs)
            assertEquals(1, authorizations)
            controller.connect(controller.state.action)
            assertEquals(DriveConnectionPhase.CONNECTED, controller.state.phase)
            assertEquals(3, signs)
            assertEquals(2, authorizations)
        } finally { controller.dispose(); scope.cancel() }
    }

    private fun gateway(choose: suspend () -> ChosenDriveAccount = { account },
        authorize: suspend (ChosenDriveAccount) -> DriveGrant = { grant }) =
        object : DriveConnectionGateway {
            override suspend fun chooseAccount() = choose()
            override suspend fun authorize(account: ChosenDriveAccount) = authorize(account)
        }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queue.addLast(block) }
        fun drain() { while (queue.isNotEmpty()) queue.removeFirst().run() }
    }
}
