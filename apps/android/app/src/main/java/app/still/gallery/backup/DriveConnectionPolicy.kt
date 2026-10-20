package app.still.gallery.backup

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.UUID

const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"

data class DriveAccountDisplay(val email: String, val name: String)
data class SavedDriveAccount(val display: DriveAccountDisplay, val epoch: String)
data class ChosenDriveAccount(val display: DriveAccountDisplay, val subjectBinding: String)
class DriveGrant(val token: String, val scopes: Set<String>) { override fun toString() = "DriveGrant(redacted)" }
enum class DriveConnectionPhase { UNAVAILABLE, DISCONNECTED, REAUTH_REQUIRED, CONNECTING, CONNECTED }
enum class DriveConnectionError { NO_ACCOUNT, CANCELLED, SIGN_IN_FAILED, PERMISSION_DENIED, STORAGE_FAILED }
class DriveConnectionAction internal constructor()
class DriveConnectionAttempt internal constructor(internal val previous: DriveConnectionState)
data class DriveConnectionState(val phase: DriveConnectionPhase, val account: DriveAccountDisplay?,
    val action: DriveConnectionAction, val error: DriveConnectionError? = null) {
    val busy: Boolean get() = phase == DriveConnectionPhase.CONNECTING
}

/** This is a build identifier, not a secret. Never accept a client ID from an intent or text field. */
fun validDriveWebClientId(value: String): Boolean = Regex("[A-Za-z0-9_-]+\\.apps\\.googleusercontent\\.com").matches(value) && value.length <= 256

/** Identity scopes may accompany sign-in; no other resource scope is accepted. */
internal fun driveScopesAllowed(scopes: Set<String>): Boolean = DRIVE_FILE_SCOPE in scopes && scopes.all {
    it in setOf(DRIVE_FILE_SCOPE, "openid", "email", "profile",
        "https://www.googleapis.com/auth/userinfo.email", "https://www.googleapis.com/auth/userinfo.profile")
}

/** No tokens, raw Google subjects, or authenticated state are serialized by this policy. */
class DriveConnectionSession(private val configured: Boolean, saved: SavedDriveAccount?,
    private val save: (SavedDriveAccount?) -> Boolean) {
    private var token: String? = null
    private var pending: DriveConnectionAttempt? = null
    private var alive = true
    var state by mutableStateOf(DriveConnectionState(
        if (!configured) DriveConnectionPhase.UNAVAILABLE else if (saved != null) DriveConnectionPhase.REAUTH_REQUIRED else DriveConnectionPhase.DISCONNECTED,
        saved?.display, DriveConnectionAction()))
        private set

    fun begin(expected: DriveConnectionAction): DriveConnectionAttempt? {
        if (!alive || !configured || state.action !== expected || state.busy) return null
        return DriveConnectionAttempt(state).also {
            pending = it
            state = state.copy(phase = DriveConnectionPhase.CONNECTING, action = DriveConnectionAction(), error = null)
        }
    }

    fun isCurrent(attempt: DriveConnectionAttempt): Boolean = alive && pending === attempt

    fun complete(attempt: DriveConnectionAttempt, account: ChosenDriveAccount, grant: DriveGrant): Boolean {
        if (!isCurrent(attempt)) return false
        if (account.display.email.isBlank() || account.display.email.length > 320 ||
            account.display.email.any { it.isWhitespace() } || account.display.name.length > 512 ||
            !Regex("[a-f0-9]{64}").matches(account.subjectBinding) ||
            grant.token.isBlank() || grant.token.length > 32_768 || !driveScopesAllowed(grant.scopes)) {
            fail(attempt, DriveConnectionError.PERMISSION_DENIED)
            return false
        }
        val saved = SavedDriveAccount(account.display, UUID.randomUUID().toString())
        if (!try { save(saved) } catch (_: RuntimeException) { false }) {
            token = null; pending = null
            state = DriveConnectionState(DriveConnectionPhase.REAUTH_REQUIRED, attempt.previous.account, DriveConnectionAction(), DriveConnectionError.STORAGE_FAILED)
            return false
        }
        token = grant.token
        pending = null
        state = DriveConnectionState(DriveConnectionPhase.CONNECTED, account.display, DriveConnectionAction())
        return true
    }

    fun fail(attempt: DriveConnectionAttempt, reason: DriveConnectionError) {
        if (!isCurrent(attempt)) return
        pending = null
        val previous = attempt.previous
        state = previous.copy(phase = if (token != null) DriveConnectionPhase.CONNECTED else if (previous.account != null) DriveConnectionPhase.REAUTH_REQUIRED else DriveConnectionPhase.DISCONNECTED,
            action = DriveConnectionAction(), error = reason)
    }

    fun cancel(expected: DriveConnectionAction): Boolean {
        val attempt = pending ?: return false
        if (state.action !== expected) return false
        fail(attempt, DriveConnectionError.CANCELLED)
        return true
    }

    /** Disconnect is local-only; it never revokes a remote Google grant. */
    fun disconnect(expected: DriveConnectionAction): Boolean {
        if (!alive || state.action !== expected || state.busy || state.account == null) return false
        token = null
        pending = null
        if (!try { save(null) } catch (_: RuntimeException) { false }) {
            state = state.copy(phase = if (configured) DriveConnectionPhase.REAUTH_REQUIRED else DriveConnectionPhase.UNAVAILABLE,
                action = DriveConnectionAction(), error = DriveConnectionError.STORAGE_FAILED)
            return false
        }
        state = DriveConnectionState(if (configured) DriveConnectionPhase.DISCONNECTED else DriveConnectionPhase.UNAVAILABLE,
            null, DriveConnectionAction())
        return true
    }

    fun dispose() { alive = false; token = null; pending = null }
}
