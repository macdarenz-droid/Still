package app.still.gallery.backup

import org.junit.Assert.*
import org.junit.Test

class DriveReadinessLeaseTest {
    private val account = ChosenDriveAccount(DriveAccountDisplay("chosen@example.com", "Chosen account"), "a".repeat(64))
    private val grant = DriveGrant("memory-only-fixture-token", setOf(DRIVE_FILE_SCOPE))
    private fun connected(): DriveConnectionSession = DriveConnectionSession(true, null) { true }.also {
        assertTrue(it.complete(it.begin(it.state.action)!!, account, grant))
    }
    private fun denied(action: () -> Unit) {
        try { action(); fail("expected stale lease denial") }
        catch (failure: DriveReadinessFailure) { assertEquals(DriveReadinessError.NEEDS_SIGN_IN, failure.reason) }
    }

    @Test fun onlyCurrentConnectedInMemoryGrantCanMintALease() {
        val saved = SavedDriveAccount(account.display, "saved-epoch")
        for (owner in listOf(DriveConnectionSession(false, saved) { true }, DriveConnectionSession(true, saved) { true }, DriveConnectionSession(true, null) { true }))
            assertNull(owner.accessLease(owner.state.action))
        val owner = connected(); val lease = owner.accessLease(owner.state.action)!!
        assertTrue(lease.isCurrent()); assertTrue(lease.epoch.isNotBlank()); assertTrue(lease.revision > 0)
        assertFalse(lease.toString().contains(grant.token)); assertFalse(lease.toString().contains(lease.epoch))
    }

    @Test fun switchingWithdrawsBeforeResultAndCancelledSwitchNeverRevivesAnOldLease() {
        val owner = connected(); val action = owner.state.action; val old = owner.accessLease(action)!!
        owner.begin(action)!!
        assertFalse(old.isCurrent()); denied { old.authorizationHeader() }
        assertNull(owner.accessLease(owner.state.action))
        assertTrue(owner.cancel(owner.state.action))
        assertEquals(DriveConnectionPhase.CONNECTED, owner.state.phase)
        val current = owner.accessLease(owner.state.action)!!
        assertEquals(old.epoch, current.epoch); assertTrue(current.revision > old.revision)
        assertTrue(current.isCurrent()); assertFalse(old.isCurrent()); assertNull(owner.accessLease(action))
    }

    @Test fun failedSwitchRetainsPriorGrantOnlyUnderFreshLeaseAndSuccessfulReconnectGetsNewEpoch() {
        val owner = connected(); val old = owner.accessLease(owner.state.action)!!
        val failed = owner.begin(owner.state.action)!!; owner.fail(failed, DriveConnectionError.SIGN_IN_FAILED)
        val afterFailure = owner.accessLease(owner.state.action)!!
        assertFalse(old.isCurrent()); assertTrue(afterFailure.isCurrent())
        assertTrue(owner.complete(owner.begin(owner.state.action)!!, account, grant))
        val reconnected = owner.accessLease(owner.state.action)!!
        assertNotEquals(afterFailure.epoch, reconnected.epoch); assertTrue(reconnected.revision > afterFailure.revision)
        assertFalse(afterFailure.isCurrent())
    }

    @Test fun disconnectDisposeAndFailedPersistenceWithdrawHeldLeases() {
        val owner = connected(); val old = owner.accessLease(owner.state.action)!!
        assertTrue(owner.disconnect(owner.state.action)); assertFalse(old.isCurrent())
        assertNull(owner.accessLease(owner.state.action)); denied { old.authorizationHeader() }
        val disposed = connected(); val held = disposed.accessLease(disposed.state.action)!!
        disposed.dispose(); assertFalse(held.isCurrent()); assertNull(disposed.accessLease(disposed.state.action))
        var writable = true
        val failing = DriveConnectionSession(true, null) { writable }
        failing.complete(failing.begin(failing.state.action)!!, account, grant)
        val stale = failing.accessLease(failing.state.action)!!; writable = false
        assertFalse(failing.complete(failing.begin(failing.state.action)!!, account, grant))
        assertFalse(stale.isCurrent()); assertNull(failing.accessLease(failing.state.action))
    }

    @Test fun bearerHeaderControlCharactersAndWhitespaceCannotEnterAConnection() {
        for (token in listOf("token\r\nInjected: value", "token value", "token\tvalue", "token\u0000", "token\u0080")) {
            val owner = DriveConnectionSession(true, null) { error("invalid grant must not persist") }
            assertFalse(owner.complete(owner.begin(owner.state.action)!!, account, DriveGrant(token, setOf(DRIVE_FILE_SCOPE))))
            assertNull(owner.accessLease(owner.state.action))
        }
    }

    @Test fun requestCancellationCallsAbortAtMostOnceAndPermanentlyDeniesFurtherWork() {
        var aborts = 0
        val request = DriveReadinessRequest { true }; request.attachAbort { aborts++ }
        request.cancel(); request.cancel(); assertEquals(1, aborts)
        try { request.check(); fail("cancelled request") }
        catch (failure: DriveReadinessFailure) { assertEquals(DriveReadinessError.CANCELLED, failure.reason) }
        try { request.attachAbort { aborts++ }; fail("late attachment") }
        catch (failure: DriveReadinessFailure) { assertEquals(DriveReadinessError.CANCELLED, failure.reason) }
        assertEquals(1, aborts)
    }
}
