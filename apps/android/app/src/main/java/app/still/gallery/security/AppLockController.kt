package app.still.gallery.security

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import app.still.gallery.R
import java.util.UUID

enum class AppLockNotice { CANCELLED, NOT_RECOGNIZED, UNAVAILABLE, NO_CREDENTIAL }

data class AppLockUiState(
    val lock: LockState,
    val credentialAvailable: Boolean,
    val biometricAvailable: Boolean,
    val settingNeedsRecovery: Boolean,
    val notice: AppLockNotice?,
) {
    val showGallery: Boolean get() = lock.canAccessContent && !lock.authenticating
}

/** Activity-owned and memory-only. A new Activity always creates a fresh locked session. */
class AppLockController(
    private val activity: ComponentActivity,
) {
    private val preferences = AppLockPreferences(activity)
    private var setting = preferences.load()
    private val keyguard = activity.getSystemService(KeyguardManager::class.java)
    private var session = newSession(setting.enabled)
    private var hostForeground = false
    private var activeAttempt: LockAttempt? = null
    private var biometricSignal: CancellationSignal? = null
    private var credentialLauncher: ActivityResultLauncher<Intent>? = null
    private var notice: AppLockNotice? = null
    var state by mutableStateOf(AppLockUiState(session.state, hasCredential(), false, setting.needsRecovery, null))
        private set

    init { publish() }

    fun onForeground() {
        if (hostForeground) return
        hostForeground = true
        val refreshed = preferences.load()
        // A durable change starts a NEW session, never restores any authorization.
        // A read must never clear a previous failed-write gate in the current session.
        if (!session.state.persistenceFailed && refreshed.enabled != session.state.enabled) {
            clearPlatformAttempt()
            session.cancelAuthentication()
            session = newSession(refreshed.enabled)
        }
        setting = refreshed
        session.onForeground()
        if (session.state.enabled && !hasCredential()) {
            clearPlatformAttempt()
            session.onExternalEntry()
            notice = AppLockNotice.NO_CREDENTIAL
        }
        publish()
    }

    fun onBackground() {
        // Top-resumed loss, onPause and onStop can describe the same departure.
        if (!hostForeground) return
        hostForeground = false
        session.onBackground()
        if (!session.state.authenticating) clearPlatformAttempt()
        publish()
    }

    fun onScreenOff() {
        hostForeground = false
        clearPlatformAttempt()
        session.onScreenOff()
        publish()
    }

    fun onExternalEntry() {
        clearPlatformAttempt()
        session.onExternalEntry()
        notice = null
        // Even an entry delivered while resumed must recheck the durable setting.
        if (hostForeground) { hostForeground = false; onForeground() } else publish()
    }

    fun authenticate(action: LockAction, useCredential: Boolean = false) {
        clearPlatformAttempt()
        session.cancelAuthentication()
        notice = null
        if (!hasCredential()) {
            if (session.state.enabled) session.onExternalEntry()
            notice = AppLockNotice.NO_CREDENTIAL
            publish()
            return
        }
        val biometric = !useCredential && canUseBiometric()
        val flow = if (biometric) AuthenticationFlow.IN_APP_BIOMETRIC else AuthenticationFlow.EXTERNAL_DEVICE_CREDENTIAL
        val attempt = session.beginAuthentication(action, flow) ?: run { publish(); return }
        activeAttempt = attempt
        publish() // Hide gallery and apply secure flags before any system prompt/Activity.
        if (biometric && Build.VERSION.SDK_INT >= 28) startBiometric(attempt)
        else startCredential(attempt)
    }

    fun cancelAuthentication() {
        clearPlatformAttempt()
        session.cancelAuthentication()
        notice = AppLockNotice.CANCELLED
        publish()
    }

    fun openSecuritySettings() {
        clearPlatformAttempt()
        session.cancelAuthentication()
        notice = null
        publish()
        try { activity.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }
        catch (_: RuntimeException) { notice = AppLockNotice.UNAVAILABLE; publish() }
    }

    fun dispose() {
        clearPlatformAttempt()
        session.onScreenOff()
    }

    private fun newSession(enabled: Boolean) = LockSession(enabled) { requested ->
        val persisted = preferences.persist(requested)
        setting = if (persisted) StoredLockSetting(requested) else preferences.load()
        persisted
    }

    private fun startCredential(attempt: LockAttempt) {
        try {
            val intent = keyguard?.createConfirmDeviceCredentialIntent(promptTitle(attempt.action), activity.getString(R.string.app_lock_prompt_body))
            if (intent == null) { finish(attempt, AuthenticationResult.FAILED, AppLockNotice.NO_CREDENTIAL); return }
            // Each launch has its own callback capturing the exact memory-only challenge.
            // A recreated Activity never re-registers an old key or restores an attempt.
            val launcher = activity.activityResultRegistry.register(
                "still-device-auth-${UUID.randomUUID()}",
                ActivityResultContracts.StartActivityForResult(),
            ) { result ->
                finish(attempt, if (result.resultCode == Activity.RESULT_OK) AuthenticationResult.SUCCESS else AuthenticationResult.CANCELLED,
                    if (result.resultCode == Activity.RESULT_OK) null else AppLockNotice.CANCELLED)
            }
            credentialLauncher = launcher
            launcher.launch(intent)
        } catch (_: RuntimeException) {
            finish(attempt, AuthenticationResult.FAILED, AppLockNotice.UNAVAILABLE)
        }
    }

    private fun startBiometric(attempt: LockAttempt) {
        if (Build.VERSION.SDK_INT < 28) { finish(attempt, AuthenticationResult.FAILED, AppLockNotice.UNAVAILABLE); return }
        try {
            val executor = ContextCompat.getMainExecutor(activity)
            val builder = BiometricPrompt.Builder(activity)
                .setTitle(promptTitle(attempt.action))
                .setDescription(activity.getString(R.string.app_lock_prompt_body))
                .setNegativeButton(activity.getString(R.string.app_lock_use_credential), executor) { _, _ ->
                    if (activeAttempt === attempt) authenticate(attempt.action, useCredential = true)
                }
            if (Build.VERSION.SDK_INT >= 30) builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            val signal = CancellationSignal()
            biometricSignal = signal
            builder.build().authenticate(signal, executor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { finish(attempt, AuthenticationResult.SUCCESS) }
                override fun onAuthenticationFailed() {
                    if (activeAttempt === attempt) { notice = AppLockNotice.NOT_RECOGNIZED; publish() }
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    val cancelled = errorCode == BiometricPrompt.BIOMETRIC_ERROR_CANCELED || errorCode == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED
                    finish(attempt, if (cancelled) AuthenticationResult.CANCELLED else AuthenticationResult.FAILED,
                        if (cancelled) AppLockNotice.CANCELLED else AppLockNotice.UNAVAILABLE)
                }
            })
        } catch (_: RuntimeException) { finish(attempt, AuthenticationResult.FAILED, AppLockNotice.UNAVAILABLE) }
    }

    private fun finish(attempt: LockAttempt, result: AuthenticationResult, nextNotice: AppLockNotice? = null) {
        if (activeAttempt !== attempt) return
        clearPlatformAttempt()
        session.completeAuthentication(attempt, result)
        notice = nextNotice
        publish()
    }

    private fun clearPlatformAttempt() {
        activeAttempt = null // Fence callbacks before cancelling platform objects.
        val signal = biometricSignal
        biometricSignal = null
        credentialLauncher?.unregister()
        credentialLauncher = null
        signal?.cancel()
    }

    private fun hasCredential(): Boolean = try { keyguard?.isDeviceSecure == true } catch (_: RuntimeException) { false }

    private fun canUseBiometric(): Boolean = try {
        when {
            Build.VERSION.SDK_INT >= 30 -> activity.getSystemService(BiometricManager::class.java)?.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
            Build.VERSION.SDK_INT >= 29 -> activity.getSystemService(BiometricManager::class.java)?.canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS
            Build.VERSION.SDK_INT >= 28 -> activity.packageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)
            else -> false
        }
    } catch (_: RuntimeException) { false }

    private fun promptTitle(action: LockAction) = activity.getString(when (action) {
        LockAction.UNLOCK -> R.string.app_lock_unlock
        LockAction.ENABLE -> R.string.app_lock_enable
        LockAction.DISABLE -> R.string.app_lock_disable
    })

    private fun publish() {
        val current = session.state
        state = AppLockUiState(current, hasCredential(), canUseBiometric(), setting.needsRecovery, notice)
    }
}
