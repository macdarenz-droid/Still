package app.still.gallery.backup

import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class DriveConnectionPolicyTest {
    private val a = ChosenDriveAccount(DriveAccountDisplay("a@example.com", "Account A"), "a".repeat(64))
    private val b = ChosenDriveAccount(DriveAccountDisplay("b@example.com", "Account B"), "b".repeat(64))
    private val grant = DriveGrant("memory-token-never-saved", setOf(DRIVE_FILE_SCOPE))

    @Test fun missingConfigurationHasNoAuthenticationAttemptOrConnectedState() {
        val owner = DriveConnectionSession(false, null) { error("must not persist") }
        assertEquals(DriveConnectionPhase.UNAVAILABLE, owner.state.phase)
        assertNull(owner.begin(owner.state.action))
        assertFalse(validDriveWebClientId(""))
        assertFalse(validDriveWebClientId("https://example.com"))
        assertFalse(validDriveWebClientId("123.apps.googleusercontent.com\n"))
        assertTrue(validDriveWebClientId("123-public-web-client.apps.googleusercontent.com"))
    }

    @Test fun identityAloneAndUnrelatedScopesCannotBecomeDriveConnection() {
        val owner = DriveConnectionSession(true, null) { error("missing scope must not persist") }
        val attempt = owner.begin(owner.state.action)!!
        assertEquals(DriveConnectionPhase.CONNECTING, owner.state.phase)
        assertFalse(owner.complete(attempt, a, DriveGrant("token", setOf("openid", "email"))))
        assertEquals(DriveConnectionPhase.DISCONNECTED, owner.state.phase)
        assertEquals(DriveConnectionError.PERMISSION_DENIED, owner.state.error)
        assertFalse(owner.complete(attempt, a, grant))
    }

    @Test fun onlyDisplayAndRandomEpochAreSavedAndRestartAlwaysRequiresAuthentication() {
        var saved: SavedDriveAccount? = null
        val owner = DriveConnectionSession(true, null) { saved = it; true }
        assertTrue(owner.complete(owner.begin(owner.state.action)!!, a, grant))
        assertEquals(DriveConnectionPhase.CONNECTED, owner.state.phase)
        assertEquals(a.display, saved!!.display)
        assertEquals(saved!!.epoch, UUID.fromString(saved!!.epoch).toString())
        assertFalse(saved.toString().contains(grant.token))
        assertFalse(saved.toString().contains(a.subjectBinding))
        assertFalse(grant.toString().contains(grant.token))
        val restarted = DriveConnectionSession(true, saved) { true }
        assertEquals(DriveConnectionPhase.REAUTH_REQUIRED, restarted.state.phase)
        assertEquals(a.display, restarted.state.account)
    }

    @Test fun cancelledSwitchPreservesPriorAccountAndRejectsItsLateSuccess() {
        val owner = DriveConnectionSession(true, null) { true }
        owner.complete(owner.begin(owner.state.action)!!, a, grant)
        val staleAction = owner.state.action
        val switch = owner.begin(staleAction)!!
        assertTrue(owner.cancel(owner.state.action))
        assertEquals(DriveConnectionPhase.CONNECTED, owner.state.phase)
        assertEquals(a.display, owner.state.account)
        assertNull(owner.begin(staleAction))
        assertFalse(owner.complete(switch, b, grant))
        val next = owner.begin(owner.state.action)!!
        assertTrue(owner.complete(next, b, grant))
        assertEquals(b.display, owner.state.account)
        owner.fail(switch, DriveConnectionError.SIGN_IN_FAILED)
        assertEquals(b.display, owner.state.account)
    }

    @Test fun staleCancelAndDisconnectActionsCannotAffectSameLookingNewConnection() {
        val owner = DriveConnectionSession(true, null) { true }
        val first = owner.begin(owner.state.action)!!
        val oldCancel = owner.state.action
        owner.cancel(oldCancel)
        val second = owner.begin(owner.state.action)!!
        assertFalse(owner.cancel(oldCancel))
        assertFalse(owner.complete(first, a, grant))
        owner.complete(second, a, grant)
        val oldDisconnect = owner.state.action
        assertTrue(owner.disconnect(oldDisconnect))
        assertEquals(DriveConnectionPhase.DISCONNECTED, owner.state.phase)
        assertNull(owner.state.account)
        owner.complete(owner.begin(owner.state.action)!!, a, grant)
        assertFalse(owner.disconnect(oldDisconnect))
        assertEquals(DriveConnectionPhase.CONNECTED, owner.state.phase)
    }

    @Test fun localDisconnectClearsSavedIdentityAndRequiresFreshSignIn() {
        var saved: SavedDriveAccount? = null
        val owner = DriveConnectionSession(true, null) { saved = it; true }
        owner.complete(owner.begin(owner.state.action)!!, a, grant)
        val oldAction = owner.state.action
        assertTrue(owner.disconnect(oldAction))
        assertNull(saved)
        assertNull(owner.state.account)
        assertEquals(DriveConnectionPhase.DISCONNECTED, owner.state.phase)
        assertNull(owner.state.error)
        assertFalse(owner.disconnect(oldAction))
        assertNotNull(owner.begin(owner.state.action))
    }

    @Test fun broadOrUnexpectedResourceScopesAreRejectedButIdentityScopesAreAllowed() {
        for (extra in listOf("https://www.googleapis.com/auth/drive", "https://www.googleapis.com/auth/drive.readonly",
            "https://www.googleapis.com/auth/calendar", "unknown-scope")) {
            var writes = 0
            val owner = DriveConnectionSession(true, null) { writes++; true }
            assertFalse(owner.complete(owner.begin(owner.state.action)!!, a, DriveGrant("token", setOf(DRIVE_FILE_SCOPE, extra))))
            assertEquals(DriveConnectionPhase.DISCONNECTED, owner.state.phase)
            assertEquals(DriveConnectionError.PERMISSION_DENIED, owner.state.error)
            assertEquals(0, writes)
        }
        val allowed = DriveConnectionSession(true, null) { true }
        assertTrue(allowed.complete(allowed.begin(allowed.state.action)!!, a, DriveGrant("token", setOf(DRIVE_FILE_SCOPE,
            "openid", "email", "profile", "https://www.googleapis.com/auth/userinfo.email", "https://www.googleapis.com/auth/userinfo.profile"))))
    }

    @Test fun failedPersistenceNeverLeavesAuthenticatedState() {
        var writesAllowed = true
        val owner = DriveConnectionSession(true, null) { writesAllowed }
        owner.complete(owner.begin(owner.state.action)!!, a, grant)
        writesAllowed = false
        val attempt = owner.begin(owner.state.action)!!
        assertFalse(owner.complete(attempt, b, grant))
        assertEquals(DriveConnectionPhase.REAUTH_REQUIRED, owner.state.phase)
        assertEquals(a.display, owner.state.account)
        assertEquals(DriveConnectionError.STORAGE_FAILED, owner.state.error)
        assertFalse(owner.disconnect(owner.state.action))
        assertEquals(DriveConnectionPhase.REAUTH_REQUIRED, owner.state.phase)
        val unconfigured = DriveConnectionSession(false, SavedDriveAccount(a.display, UUID.randomUUID().toString())) { false }
        assertFalse(unconfigured.disconnect(unconfigured.state.action))
        assertEquals(DriveConnectionPhase.UNAVAILABLE, unconfigured.state.phase)
        assertEquals(DriveConnectionError.STORAGE_FAILED, unconfigured.state.error)
        assertNull(unconfigured.begin(unconfigured.state.action))
    }

    @Test fun missingTokenMalformedIdentityAndDisposedAttemptsCannotPersist() {
        var saves = 0
        val owner = DriveConnectionSession(true, null) { saves++; true }
        assertFalse(owner.complete(owner.begin(owner.state.action)!!, a, DriveGrant("", setOf(DRIVE_FILE_SCOPE))))
        assertFalse(owner.complete(owner.begin(owner.state.action)!!, a.copy(subjectBinding = "raw-subject"), grant))
        val last = owner.begin(owner.state.action)!!
        owner.dispose()
        assertFalse(owner.complete(last, a, grant))
        assertNull(owner.begin(owner.state.action))
        assertEquals(0, saves)
    }
}
