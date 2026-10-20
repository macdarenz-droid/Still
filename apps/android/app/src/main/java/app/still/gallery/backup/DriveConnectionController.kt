package app.still.gallery.backup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class DriveConnectionFailure(val reason: DriveConnectionError) : Exception()
interface DriveConnectionGateway {
    suspend fun chooseAccount(): ChosenDriveAccount
    suspend fun authorize(account: ChosenDriveAccount): DriveGrant
}

/** Interactive Google work can survive the gallery being hidden, but never unlocks Still. */
class DriveConnectionController(private val session: DriveConnectionSession, private val gateway: DriveConnectionGateway,
    private val scope: CoroutineScope, private val onAccessWithdraw: () -> Unit = {}) {
    private var worker: Job? = null
    val state: DriveConnectionState get() = session.state

    fun connect(expected: DriveConnectionAction) {
        val attempt = session.begin(expected) ?: return
        onAccessWithdraw()
        val next = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val account = gateway.chooseAccount()
                if (!session.isCurrent(attempt)) return@launch
                val grant = gateway.authorize(account)
                session.complete(attempt, account, grant)
            } catch (cancelled: CancellationException) {
                session.fail(attempt, DriveConnectionError.CANCELLED)
                throw cancelled
            } catch (failure: DriveConnectionFailure) {
                session.fail(attempt, failure.reason)
            } catch (_: Exception) { session.fail(attempt, DriveConnectionError.SIGN_IN_FAILED) }
        }
        worker = next
        next.invokeOnCompletion { cause -> if (cause != null) session.fail(attempt, DriveConnectionError.CANCELLED) }
        next.start()
    }

    fun cancel(expected: DriveConnectionAction) { if (session.cancel(expected)) worker?.cancel() }

    fun disconnect(expected: DriveConnectionAction) {
        if (state.action === expected && !state.busy && state.account != null) onAccessWithdraw()
        session.disconnect(expected)
    }

    fun dispose() { onAccessWithdraw(); session.dispose(); worker?.cancel() }
}
