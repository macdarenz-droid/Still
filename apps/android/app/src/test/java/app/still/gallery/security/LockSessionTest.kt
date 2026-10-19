package app.still.gallery.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LockSessionTest {
    private class Fixture(enabled: Boolean) {
        var durableEnabled = enabled
        var writes = mutableListOf<Boolean>()
        var persistenceSucceeds = true
        var persistenceThrows = false
        val session = LockSession(enabled) { next ->
            writes.add(next)
            if (persistenceThrows) throw IllegalStateException("Storage unavailable")
            if (persistenceSucceeds) durableEnabled = next
            persistenceSucceeds
        }
        fun attempt(action: LockAction = LockAction.UNLOCK, flow: AuthenticationFlow = AuthenticationFlow.IN_APP_BIOMETRIC): LockAttempt {
            val attempt = session.beginAuthentication(action, flow)
            assertNotNull(attempt)
            return requireNotNull(attempt)
        }
        fun unlock() {
            session.onForeground()
            assertTrue(session.completeAuthentication(attempt(), AuthenticationResult.SUCCESS))
            assertTrue(session.state.canAccessContent)
        }
    }

    @Test fun enabledStartupAndRecreationNeverRestoreAuthorization() {
        val fixture = Fixture(true)
        assertFalse(fixture.session.state.canAccessContent)
        fixture.session.onForeground()
        assertFalse(fixture.session.state.canAccessContent)
        fixture.unlock()
        val oldAttempt = fixture.attempt(LockAction.DISABLE)
        val recreated = LockSession(fixture.durableEnabled) { error("Unexpected preference write") }
        recreated.onForeground()
        assertFalse(recreated.state.canAccessContent)
        assertFalse(recreated.completeAuthentication(oldAttempt, AuthenticationResult.SUCCESS))
        val fresh = requireNotNull(recreated.beginAuthentication(LockAction.UNLOCK, AuthenticationFlow.IN_APP_BIOMETRIC))
        assertFalse(recreated.completeAuthentication(oldAttempt, AuthenticationResult.SUCCESS))
        assertTrue(recreated.completeAuthentication(fresh, AuthenticationResult.SUCCESS))
    }

    @Test fun disabledStartupNeedsNoAccountOrAuthenticationButOnlyForegroundCanShowContent() {
        val fixture = Fixture(false)
        assertFalse(fixture.session.state.canAccessContent)
        fixture.session.onForeground()
        assertTrue(fixture.session.state.canAccessContent)
        assertNull(fixture.session.beginAuthentication(LockAction.UNLOCK, AuthenticationFlow.IN_APP_BIOMETRIC))
        assertNull(fixture.session.beginAuthentication(LockAction.DISABLE, AuthenticationFlow.IN_APP_BIOMETRIC))
        fixture.session.onBackground()
        assertFalse(fixture.session.state.canAccessContent)
        assertNull(fixture.session.beginAuthentication(LockAction.ENABLE, AuthenticationFlow.IN_APP_BIOMETRIC))
        assertTrue(fixture.writes.isEmpty())
    }

    @Test fun settingChangesRequireFreshPurposeBoundAuthenticationAndDurableSuccess() {
        val fixture = Fixture(false)
        fixture.session.onForeground()
        val enable = fixture.attempt(LockAction.ENABLE)
        assertFalse(fixture.durableEnabled)
        assertTrue(fixture.session.completeAuthentication(enable, AuthenticationResult.SUCCESS))
        assertTrue(fixture.durableEnabled)
        assertTrue(fixture.session.state.enabled)
        assertTrue(fixture.session.state.canAccessContent)
        assertFalse(fixture.session.completeAuthentication(enable, AuthenticationResult.SUCCESS))
        val disable = fixture.attempt(LockAction.DISABLE)
        assertFalse(fixture.session.completeAuthentication(enable, AuthenticationResult.SUCCESS))
        assertTrue(fixture.durableEnabled)
        assertTrue(fixture.session.completeAuthentication(disable, AuthenticationResult.SUCCESS))
        assertFalse(fixture.durableEnabled)
        val reenable = fixture.attempt(LockAction.ENABLE)
        assertFalse(fixture.session.completeAuthentication(disable, AuthenticationResult.SUCCESS))
        assertTrue(fixture.session.completeAuthentication(reenable, AuthenticationResult.SUCCESS))
        assertEquals(listOf(true, false, true), fixture.writes)
    }

    @Test fun disabledAndLockedSettingsRejectUnauthorizedActions() {
        val fixture = Fixture(true)
        fixture.session.onForeground()
        assertNull(fixture.session.beginAuthentication(LockAction.ENABLE, AuthenticationFlow.IN_APP_BIOMETRIC))
        assertNull(fixture.session.beginAuthentication(LockAction.DISABLE, AuthenticationFlow.IN_APP_BIOMETRIC))
        assertFalse(fixture.session.state.canAccessContent)
        assertTrue(fixture.writes.isEmpty())
    }

    @Test fun failureCancellationReplacementAndDuplicateResultsCannotUnlock() {
        val fixture = Fixture(true)
        fixture.session.onForeground()
        for (outcome in listOf(AuthenticationResult.CANCELLED, AuthenticationResult.FAILED)) {
            val attempt = fixture.attempt()
            assertFalse(fixture.session.completeAuthentication(attempt, outcome))
            assertFalse(fixture.session.state.authenticating)
            assertFalse(fixture.session.completeAuthentication(attempt, AuthenticationResult.SUCCESS))
            assertFalse(fixture.session.state.canAccessContent)
        }
        val old = fixture.attempt()
        val replacement = fixture.attempt()
        assertTrue(replacement.sequence > old.sequence)
        assertFalse(fixture.session.completeAuthentication(old, AuthenticationResult.SUCCESS))
        fixture.session.cancelAuthentication()
        assertFalse(fixture.session.completeAuthentication(replacement, AuthenticationResult.SUCCESS))
        assertFalse(fixture.session.state.canAccessContent)
        assertTrue(fixture.writes.isEmpty())
    }

    @Test fun backgroundScreenOffAndExternalEntryRelockImmediatelyAndFenceBiometrics() {
        for (invalidate in listOf<(LockSession) -> Unit>({ it.onBackground() }, { it.onScreenOff() }, { it.onExternalEntry() })) {
            val fixture = Fixture(true)
            fixture.unlock()
            val disable = fixture.attempt(LockAction.DISABLE)
            invalidate(fixture.session)
            assertFalse(fixture.session.state.canAccessContent)
            assertFalse(fixture.session.completeAuthentication(disable, AuthenticationResult.SUCCESS))
            fixture.session.onForeground()
            assertFalse(fixture.session.state.canAccessContent)
            fixture.unlock()
            assertTrue(fixture.writes.isEmpty())
        }
    }

    @Test fun credentialSuccessBeforeResumeDefersUnlockAndPreferenceChanges() {
        val fixture = Fixture(false)
        fixture.session.onForeground()
        val enable = fixture.attempt(LockAction.ENABLE, AuthenticationFlow.EXTERNAL_DEVICE_CREDENTIAL)
        fixture.session.onBackground()
        assertFalse(fixture.session.state.canAccessContent)
        assertTrue(fixture.session.completeAuthentication(enable, AuthenticationResult.SUCCESS))
        assertFalse(fixture.session.state.canAccessContent)
        assertFalse(fixture.durableEnabled)
        assertTrue(fixture.writes.isEmpty())
        assertFalse(fixture.session.completeAuthentication(enable, AuthenticationResult.SUCCESS))
        fixture.session.onForeground()
        assertTrue(fixture.session.state.canAccessContent)
        assertTrue(fixture.durableEnabled)
        assertEquals(listOf(true), fixture.writes)
        fixture.session.onForeground()
        assertEquals(listOf(true), fixture.writes)
    }

    @Test fun credentialResumeBeforeResultStillNeedsItsMatchingSuccess() {
        val fixture = Fixture(true)
        fixture.session.onForeground()
        val attempt = fixture.attempt(flow = AuthenticationFlow.EXTERNAL_DEVICE_CREDENTIAL)
        fixture.session.onBackground()
        fixture.session.onForeground()
        assertFalse(fixture.session.state.canAccessContent)
        assertTrue(fixture.session.completeAuthentication(attempt, AuthenticationResult.SUCCESS))
        assertTrue(fixture.session.state.canAccessContent)
        assertFalse(fixture.session.completeAuthentication(attempt, AuthenticationResult.SUCCESS))
    }

    @Test fun credentialRoundTripExceptionCannotAuthorizeWithoutHandoffOrAfterAnotherBackground() {
        val fixture = Fixture(true)
        fixture.session.onForeground()
        val noHandoff = fixture.attempt(flow = AuthenticationFlow.EXTERNAL_DEVICE_CREDENTIAL)
        assertFalse(fixture.session.completeAuthentication(noHandoff, AuthenticationResult.SUCCESS))
        assertFalse(fixture.session.state.canAccessContent)
        val interrupted = fixture.attempt(flow = AuthenticationFlow.EXTERNAL_DEVICE_CREDENTIAL)
        fixture.session.onBackground()
        fixture.session.onForeground()
        fixture.session.onBackground()
        assertFalse(fixture.session.completeAuthentication(interrupted, AuthenticationResult.SUCCESS))
        fixture.session.onForeground()
        assertFalse(fixture.session.state.canAccessContent)
    }

    @Test fun screenOffExternalEntryAndCancellationInvalidateCredentialResultsEvenBeforeResume() {
        for (invalidate in listOf<(LockSession) -> Unit>({ it.onScreenOff() }, { it.onExternalEntry() }, { it.cancelAuthentication() }, { it.onBackground() })) {
            val fixture = Fixture(true)
            fixture.session.onForeground()
            val attempt = fixture.attempt(flow = AuthenticationFlow.EXTERNAL_DEVICE_CREDENTIAL)
            fixture.session.onBackground()
            assertTrue(fixture.session.completeAuthentication(attempt, AuthenticationResult.SUCCESS))
            invalidate(fixture.session)
            fixture.session.onForeground()
            assertFalse(fixture.session.state.canAccessContent)
            assertFalse(fixture.session.completeAuthentication(attempt, AuthenticationResult.SUCCESS))
        }
    }

    @Test fun failedAndCancelledCredentialActivityNeverPersistsOrUnlocks() {
        for (outcome in listOf(AuthenticationResult.CANCELLED, AuthenticationResult.FAILED)) {
            val fixture = Fixture(false)
            fixture.session.onForeground()
            val attempt = fixture.attempt(LockAction.ENABLE, AuthenticationFlow.EXTERNAL_DEVICE_CREDENTIAL)
            fixture.session.onBackground()
            assertFalse(fixture.session.completeAuthentication(attempt, outcome))
            fixture.session.onForeground()
            assertFalse(fixture.session.state.enabled)
            assertFalse(fixture.session.state.authenticating)
            assertTrue(fixture.writes.isEmpty())
            assertFalse(fixture.session.completeAuthentication(attempt, AuthenticationResult.SUCCESS))
        }
    }

    @Test fun persistenceFailureNeverReportsChangeOrExposesContentAndFreshRetryCanRecover() {
        for (throws in listOf(false, true)) {
            val fixture = Fixture(false)
            fixture.persistenceSucceeds = false
            fixture.persistenceThrows = throws
            fixture.session.onForeground()
            assertFalse(fixture.session.completeAuthentication(fixture.attempt(LockAction.ENABLE), AuthenticationResult.SUCCESS))
            assertFalse(fixture.durableEnabled)
            assertFalse(fixture.session.state.enabled)
            assertFalse(fixture.session.state.canAccessContent)
            assertEquals(LockError.PERSISTENCE_FAILED, fixture.session.state.error)
            assertTrue(fixture.session.state.persistenceFailed)
            fixture.session.onBackground()
            fixture.session.onForeground()
            assertFalse(fixture.session.state.canAccessContent)
            val retry = fixture.attempt(LockAction.ENABLE)
            assertFalse(fixture.session.state.canAccessContent)
            fixture.persistenceSucceeds = true
            fixture.persistenceThrows = false
            assertTrue(fixture.session.completeAuthentication(retry, AuthenticationResult.SUCCESS))
            assertTrue(fixture.durableEnabled)
            assertTrue(fixture.session.state.canAccessContent)
            assertNull(fixture.session.state.error)
        }
    }

    @Test fun failedDisableRetainsDurableProtectionAndCannotReuseItsAuthentication() {
        val fixture = Fixture(true)
        fixture.unlock()
        fixture.persistenceSucceeds = false
        val disable = fixture.attempt(LockAction.DISABLE)
        assertFalse(fixture.session.completeAuthentication(disable, AuthenticationResult.SUCCESS))
        assertTrue(fixture.durableEnabled)
        assertTrue(fixture.session.state.enabled)
        assertFalse(fixture.session.state.canAccessContent)
        assertFalse(fixture.session.completeAuthentication(disable, AuthenticationResult.SUCCESS))
        assertNull(fixture.session.beginAuthentication(LockAction.DISABLE, AuthenticationFlow.IN_APP_BIOMETRIC))
        fixture.unlock()
        assertTrue(fixture.session.state.enabled)
        assertEquals(listOf(false), fixture.writes)
    }
}
