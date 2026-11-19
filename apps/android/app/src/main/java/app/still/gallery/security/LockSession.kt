package app.still.gallery.security

enum class LockAction { UNLOCK, ENABLE, DISABLE }
enum class AuthenticationFlow { IN_APP_BIOMETRIC, EXTERNAL_DEVICE_CREDENTIAL }
enum class AuthenticationResult { SUCCESS, CANCELLED, FAILED }
enum class LockError { AUTHENTICATION_FAILED, PERSISTENCE_FAILED }

/** Memory-only identity, captured by exactly one platform authentication callback. */
class LockAttempt internal constructor(
    val sequence: Long,
    val action: LockAction,
    val flow: AuthenticationFlow,
)

data class LockState(
    val enabled: Boolean,
    val unlocked: Boolean,
    val inForeground: Boolean,
    val pendingAction: LockAction?,
    val error: LockError?,
    val persistenceFailed: Boolean,
) {
    val authenticating: Boolean get() = pendingAction != null
    val canAccessContent: Boolean
        get() = inForeground && !persistenceFailed && (!enabled || unlocked)
}

/**
 * Whole-app access policy, independent of Android, Compose and saved UI state.
 *
 * Create a NEW instance from the durable enabled preference on every Activity
 * creation. Never save/restore this object, its state, unlocked flag or attempts.
 * Call lifecycle hooks and platform results on the main thread. Persistence must
 * synchronously commit just the enabled preference; true means durable success.
 * No platform callback or preference refresh may replace this session's state.
 *
 * An external credential Activity may cause exactly one expected foreground loss.
 * This exception preserves only its challenge, never gallery authorization. A
 * second background event, screen-off, external entry or replacement attempt
 * consumes that challenge. Biometric callbacks have no background exception.
 */
class LockSession(
    initiallyEnabled: Boolean,
    private val persistEnabled: (Boolean) -> Boolean,
) {
    private class Pending(val attempt: LockAttempt) {
        var credentialHandoffObserved = false
        var authenticatedBeforeForeground = false
    }

    private var enabled = initiallyEnabled
    private var unlocked = false
    private var inForeground = false
    private var sequence = 0L
    private var pending: Pending? = null
    private var error: LockError? = null
    private var persistenceFailed = false

    val state: LockState
        get() = LockState(enabled, unlocked, inForeground, pending?.attempt?.action, error, persistenceFailed)

    fun onForeground() {
        inForeground = true
        val challenge = pending
        if (challenge?.authenticatedBeforeForeground == true) {
            finishSuccess(challenge)
        }
    }

    fun onBackground() {
        inForeground = false
        unlocked = false
        val challenge = pending
        if (challenge != null &&
            challenge.attempt.flow == AuthenticationFlow.EXTERNAL_DEVICE_CREDENTIAL &&
            !challenge.credentialHandoffObserved
        ) {
            challenge.credentialHandoffObserved = true
        } else {
            invalidateAttempt()
        }
    }

    /** Screen-off also requires a subsequent foreground event before access. */
    fun onScreenOff() {
        inForeground = false
        unlocked = false
        invalidateAttempt()
    }

    /** Call for every new open/share/deep-link/notification entry, even while resumed. */
    fun onExternalEntry() {
        unlocked = false
        invalidateAttempt()
    }

    fun beginAuthentication(action: LockAction, flow: AuthenticationFlow): LockAttempt? {
        // Starting a new operation always fences the previous callback, even if
        // the requested transition is currently unavailable.
        invalidateAttempt()
        val allowed = inForeground && when (action) {
            LockAction.ENABLE -> !enabled
            LockAction.UNLOCK -> enabled && !unlocked
            LockAction.DISABLE -> enabled && unlocked
        }
        if (!allowed || sequence == Long.MAX_VALUE) return null
        if (!persistenceFailed) error = null
        val attempt = LockAttempt(sequence, action, flow)
        pending = Pending(attempt)
        return attempt
    }

    /**
     * true means this SUCCESS belongs to the active challenge. For external
     * credential results delivered before onResume, access remains blocked and
     * persistence is deferred until onForeground. Read state to render the gate.
     * Failed, cancelled, duplicate, unrelated or stale callbacks return false.
     */
    fun completeAuthentication(attempt: LockAttempt, result: AuthenticationResult): Boolean {
        val challenge = pending
        if (challenge == null || challenge.attempt !== attempt || attempt.sequence != sequence) return false
        if (result != AuthenticationResult.SUCCESS) {
            invalidateAttempt()
            if (!persistenceFailed) error = if (result == AuthenticationResult.FAILED) LockError.AUTHENTICATION_FAILED else null
            return false
        }
        if (challenge.authenticatedBeforeForeground) return false
        if (attempt.flow == AuthenticationFlow.EXTERNAL_DEVICE_CREDENTIAL && !challenge.credentialHandoffObserved) {
            invalidateAttempt()
            return false
        }
        if (!inForeground) {
            if (attempt.flow != AuthenticationFlow.EXTERNAL_DEVICE_CREDENTIAL) {
                invalidateAttempt()
                return false
            }
            challenge.authenticatedBeforeForeground = true
            return true
        }
        return finishSuccess(challenge)
    }

    fun cancelAuthentication() {
        invalidateAttempt()
        if (!persistenceFailed) error = null
    }

    private fun finishSuccess(challenge: Pending): Boolean {
        if (!inForeground || pending !== challenge || challenge.attempt.sequence != sequence) return false
        val action = challenge.attempt.action
        // Consume before committing so duplicate/reentrant callbacks cannot
        // repeat a durable preference write.
        invalidateAttempt()
        if (action != LockAction.UNLOCK) {
            val nextEnabled = action == LockAction.ENABLE
            val persisted = try {
                persistEnabled(nextEnabled)
            } catch (_: Exception) {
                false
            }
            if (!persisted) {
                unlocked = false
                persistenceFailed = true
                error = LockError.PERSISTENCE_FAILED
                return false
            }
            enabled = nextEnabled
        }
        persistenceFailed = false
        error = null
        unlocked = enabled && inForeground
        return true
    }

    private fun invalidateAttempt() {
        pending = null
        // Exhaustion permanently denies new challenges until a new session;
        // never wrap and reuse a prior callback generation.
        if (sequence < Long.MAX_VALUE) sequence += 1
    }
}
