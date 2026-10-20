package app.still.gallery.backup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.still.gallery.R

@Composable
fun DriveConnectionSettings(holder: DriveConnectionHolder?, canAct: () -> Boolean) {
    val controller = holder?.controller
    val state = controller?.state
    val phase = state?.phase ?: DriveConnectionPhase.UNAVAILABLE
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.drive_connection_title), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.drive_connection_scope), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(when (phase) {
            DriveConnectionPhase.UNAVAILABLE -> R.string.drive_connection_unavailable
            DriveConnectionPhase.DISCONNECTED -> R.string.drive_connection_disconnected
            DriveConnectionPhase.REAUTH_REQUIRED -> R.string.drive_connection_reauth
            DriveConnectionPhase.CONNECTING -> R.string.drive_connection_connecting
            DriveConnectionPhase.CONNECTED -> R.string.drive_connection_authorized
        }), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        state?.account?.let { account ->
            if (phase == DriveConnectionPhase.REAUTH_REQUIRED || phase == DriveConnectionPhase.UNAVAILABLE)
                Text(stringResource(R.string.drive_connection_saved_account), style = MaterialTheme.typography.labelLarge)
            if (account.name.isNotBlank()) Text(account.name, style = MaterialTheme.typography.titleMedium)
            Text(account.email, style = MaterialTheme.typography.bodyMedium)
        }
        state?.error?.let { problem ->
            Text(stringResource(when (problem) {
                DriveConnectionError.NO_ACCOUNT -> R.string.drive_connection_no_account
                DriveConnectionError.CANCELLED -> R.string.drive_connection_cancelled
                DriveConnectionError.SIGN_IN_FAILED -> R.string.drive_connection_failed
                DriveConnectionError.PERMISSION_DENIED -> R.string.drive_connection_permission_denied
                DriveConnectionError.STORAGE_FAILED -> R.string.drive_connection_storage_failed
            }), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        if (state?.busy == true) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            if (phase == DriveConnectionPhase.CONNECTING) TextButton(onClick = {
                if (canAct()) controller.cancel(state.action)
            }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.cancel)) }
        } else {
            Button(onClick = { if (state != null && canAct()) controller.connect(state.action) },
                enabled = phase != DriveConnectionPhase.UNAVAILABLE && canAct(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(if (phase == DriveConnectionPhase.CONNECTED) R.string.drive_connection_switch else R.string.drive_connection_connect))
            }
            if (state?.account != null) {
                Text(stringResource(R.string.drive_connection_disconnect_note), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { if (canAct()) controller.disconnect(state.action) },
                    enabled = canAct(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.drive_connection_disconnect))
                }
            }
        }
    }
}
