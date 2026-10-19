package app.still.gallery.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.still.gallery.R
import app.still.gallery.security.AppLockController
import app.still.gallery.security.AppLockNotice
import app.still.gallery.security.AppLockUiState
import app.still.gallery.security.LockAction
import app.still.gallery.security.LockError

@Composable
fun AppLockRoot(controller: AppLockController) {
    val state = controller.state
    // Only gallery navigation/selection may survive this gate. Authentication never does.
    val galleryUi = rememberSaveableStateHolder()
    if (state.showGallery) {
        galleryUi.SaveableStateProvider("gallery") {
            StillApp(state, { controller.authenticate(it) }, controller::openSecuritySettings,
                canAccessGallery = { controller.state.showGallery })
        }
    } else {
        BackHandler(state.lock.authenticating) { controller.cancelAuthentication() }
        // Gate presentation is memory-only, including its scroll position.
        val scroll = remember { ScrollState(0) }
        val paneTitle = stringResource(R.string.app_lock_title)
        Surface(Modifier.fillMaxSize()) {
            Box(
                Modifier.fillMaxSize().safeDrawingPadding().semantics { this.paneTitle = paneTitle },
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    Modifier.widthIn(max = 480.dp).fillMaxWidth().verticalScroll(scroll)
                        .padding(horizontal = 24.dp, vertical = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Image(painterResource(R.drawable.ic_still), null, Modifier.size(52.dp))
                    if (!state.lock.enabled && !state.lock.persistenceFailed && !state.lock.authenticating) {
                        Text(
                            stringResource(R.string.app_lock_opening),
                            Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite },
                            style = MaterialTheme.typography.titleLarge,
                        )
                    } else {
                        val action = if (state.lock.enabled) LockAction.UNLOCK else LockAction.ENABLE
                        val title = when {
                            state.lock.authenticating -> R.string.app_lock_authenticating
                            state.lock.enabled -> R.string.app_lock_locked
                            else -> R.string.app_lock_retry_enable
                        }
                        Text(
                            stringResource(title),
                            Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite },
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            stringResource(if (state.lock.authenticating || action == LockAction.ENABLE) R.string.app_lock_prompt_body else R.string.app_lock_unlock_body),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        AppLockStatus(state)
                        if (state.credentialAvailable && !state.lock.authenticating) {
                            Button(
                                onClick = { controller.authenticate(action) },
                                enabled = state.lock.inForeground,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            ) {
                                Text(stringResource(if (action == LockAction.UNLOCK) R.string.app_lock_unlock else R.string.app_lock_retry_enable), textAlign = TextAlign.Center)
                            }
                            if (state.biometricAvailable) {
                                TextButton(
                                    onClick = { controller.authenticate(action, useCredential = true) },
                                    enabled = state.lock.inForeground,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                ) {
                                    Text(stringResource(R.string.app_lock_use_credential), textAlign = TextAlign.Center)
                                }
                            }
                        }
                        if (state.lock.authenticating) {
                            TextButton(onClick = controller::cancelAuthentication, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                Text(stringResource(R.string.cancel), textAlign = TextAlign.Center)
                            }
                        }
                        TextButton(
                            onClick = controller::openSecuritySettings,
                            enabled = state.lock.inForeground && !state.lock.authenticating,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) {
                            Text(stringResource(R.string.app_lock_security_settings), textAlign = TextAlign.Center)
                        }
                        Text(stringResource(R.string.app_lock_boundary), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
fun AppLockSettings(state: AppLockUiState, authenticate: (LockAction) -> Unit, securitySettings: () -> Unit) {
    val canAct = state.lock.inForeground && !state.lock.authenticating
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.app_lock_title), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
        Text(stringResource(if (state.lock.enabled) R.string.app_lock_on else R.string.app_lock_off), Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        Text(stringResource(R.string.app_lock_settings_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.app_lock_leaving), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.app_lock_screenshots), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.app_lock_boundary), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        AppLockStatus(state)
        if (!state.credentialAvailable) {
            TextButton(onClick = securitySettings, enabled = canAct, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.app_lock_security_settings), textAlign = TextAlign.Center)
            }
        } else {
            TextButton(
                onClick = { authenticate(if (state.lock.enabled) LockAction.DISABLE else LockAction.ENABLE) },
                enabled = canAct,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text(stringResource(if (state.lock.enabled) R.string.app_lock_disable else R.string.app_lock_enable), textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun AppLockStatus(state: AppLockUiState) {
    val persistenceFailed = state.lock.persistenceFailed || state.lock.error == LockError.PERSISTENCE_FAILED
    val credentialMessage = when {
        state.credentialAvailable -> null
        state.lock.enabled || persistenceFailed -> R.string.app_lock_no_credential
        else -> R.string.app_lock_setup_credential
    }
    val message = when {
        persistenceFailed && !state.lock.enabled -> R.string.app_lock_enable_failed
        persistenceFailed -> R.string.app_lock_save_failed
        state.settingNeedsRecovery -> R.string.app_lock_recovery
        state.notice == AppLockNotice.CANCELLED -> R.string.app_lock_cancelled
        state.notice == AppLockNotice.NOT_RECOGNIZED -> R.string.app_lock_not_recognized
        state.notice == AppLockNotice.UNAVAILABLE -> R.string.app_lock_unavailable
        state.notice == AppLockNotice.NO_CREDENTIAL -> credentialMessage ?: R.string.app_lock_unavailable
        state.lock.error == LockError.AUTHENTICATION_FAILED -> R.string.app_lock_auth_failed
        else -> null
    }
    listOfNotNull(message, credentialMessage).distinct().forEach { status ->
        Text(stringResource(status), Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
