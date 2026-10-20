package app.still.gallery.backup

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Separate from interactive sign-in: leaving Settings immediately withdraws this check. */
class DriveReadinessController(private val acquire: (DriveConnectionAction) -> DriveAccessLease?,
    private val transport: DriveReadinessTransport, private val scope: CoroutineScope) {
    private class Attempt(val lease: DriveAccessLease, val canAct: () -> Boolean) { lateinit var request: DriveReadinessRequest }
    private var pending: Attempt? = null
    private var worker: Job? = null
    private var alive = true
    private var observedLease: DriveAccessLease? = null
    private var observedOrigin: (() -> Boolean)? = null
    private var value by mutableStateOf(DriveReadinessState(DriveReadinessPhase.IDLE, DriveReadinessAction()))
    val state: DriveReadinessState get() = if (staleOrigin())
        DriveReadinessState(DriveReadinessPhase.IDLE, value.action) else value

    fun check(connection: DriveConnectionAction, expected: DriveReadinessAction, canAct: () -> Boolean) {
        if (!alive || pending != null || state.action !== expected || !allowed(canAct)) return
        val lease = acquire(connection) ?: return
        if (!lease.isCurrent()) return
        val attempt = Attempt(lease, canAct)
        attempt.request = DriveReadinessRequest { isCurrent(attempt) }
        pending = attempt; observedLease = lease; observedOrigin = canAct
        value = DriveReadinessState(DriveReadinessPhase.CHECKING, DriveReadinessAction())
        val next = scope.launch(start = CoroutineStart.LAZY) {
            try {
                attempt.request.check()
                val quota = transport.check(lease, attempt.request)
                if (isCurrent(attempt)) value = DriveReadinessState(DriveReadinessPhase.CHECKED, DriveReadinessAction(), quota)
            } catch (cancelled: CancellationException) {
                fail(attempt, DriveReadinessError.CANCELLED); throw cancelled
            } catch (failure: DriveReadinessFailure) { fail(attempt, failure.reason) }
            catch (_: Exception) { fail(attempt, DriveReadinessError.NETWORK) }
            finally { finish(attempt) }
        }
        worker = next
        next.invokeOnCompletion { cause -> if (cause != null) { fail(attempt, DriveReadinessError.CANCELLED); finish(attempt) } }
        next.start()
    }

    fun cancel(expected: DriveReadinessAction) {
        if (value.action !== expected || pending == null) return
        hide()
        value = DriveReadinessState(DriveReadinessPhase.FAILED, DriveReadinessAction(), error = DriveReadinessError.CANCELLED)
    }

    fun revalidateVisibility() { if (staleOrigin()) hide() }

    /** Call on lock, foreground loss, Settings disposal, or connection withdrawal. */
    fun hide() {
        val old = pending; pending = null; observedLease = null; observedOrigin = null
        value = DriveReadinessState(DriveReadinessPhase.IDLE, DriveReadinessAction())
        old?.request?.cancel(); worker?.cancel(); worker = null
    }

    fun dispose() { alive = false; hide() }
    private fun allowed(check: () -> Boolean) = try { check() } catch (_: RuntimeException) { false }
    private fun staleOrigin() = observedLease?.isCurrent() == false || observedOrigin?.let { !allowed(it) } == true
    private fun isCurrent(attempt: Attempt) = alive && pending === attempt && attempt.lease.isCurrent() && allowed(attempt.canAct)
    private fun fail(attempt: Attempt, reason: DriveReadinessError) {
        if (isCurrent(attempt)) value = DriveReadinessState(DriveReadinessPhase.FAILED, DriveReadinessAction(), error = reason)
    }
    private fun finish(attempt: Attempt) {
        if (pending !== attempt) return
        if (!isCurrent(attempt)) {
            observedLease = null; observedOrigin = null; value = DriveReadinessState(DriveReadinessPhase.IDLE, DriveReadinessAction())
        }
        pending = null; worker = null
    }
}
