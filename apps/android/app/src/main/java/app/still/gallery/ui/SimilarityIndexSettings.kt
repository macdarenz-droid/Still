package app.still.gallery.ui

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.still.gallery.R

/** Explicit, foreground user controls for the on-device appearance index. */
@Composable
fun SimilarityIndexSettings(
    state: SimilarityIndexUiState,
    canStart: Boolean,
    canClear: Boolean,
    onStart: (SimilarityIndexAction) -> Unit,
    onStop: () -> Unit,
    onRetryClose: (SimilarityIndexAction) -> Unit,
    onClear: (SimilarityIndexAction) -> Unit,
) {
    var confirmClear by remember { mutableStateOf(false) }
    val progress = state.progress
    val isRunning = state.phase == SimilarityIndexPhase.RUNNING
    val canRetryClose = state.phase == SimilarityIndexPhase.BUSY || state.phase == SimilarityIndexPhase.CLOSE_FAILED
    val startLabel = when {
        state.phase == SimilarityIndexPhase.COMPLETE -> R.string.similarity_index_update
        (state.phase == SimilarityIndexPhase.STOPPED || state.phase == SimilarityIndexPhase.PARTIAL) && progress.unattempted > 0 -> R.string.similarity_index_continue
        else -> R.string.similarity_index_start
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.similarity_index_title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.similarity_index_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(
            stringResource(statusText(state)),
            Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodyMedium,
        )
        when (state.failure) {
            SimilarityIndexUiFailure.UNAVAILABLE -> Text(stringResource(R.string.similarity_index_failure_unavailable), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            SimilarityIndexUiFailure.SOURCE_CHANGED -> Text(stringResource(R.string.similarity_index_failure_changed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            SimilarityIndexUiFailure.SCOPE_LIMIT -> Text(stringResource(R.string.similarity_index_failure_scope), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            SimilarityIndexUiFailure.BUSY -> Text(stringResource(R.string.similarity_index_failure_busy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SimilarityIndexUiFailure.CLOSE_FAILED -> Text(stringResource(R.string.similarity_index_failure_close), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            null -> Unit
        }

        if (isRunning || state.phase == SimilarityIndexPhase.STOPPING || state.phase == SimilarityIndexPhase.CLEARING || state.phase == SimilarityIndexPhase.LOADING) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator()
                Text(stringResource(R.string.similarity_index_working), style = MaterialTheme.typography.bodyMedium)
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val countText = if (progress.currentPhotos == 0) {
                    stringResource(R.string.similarity_index_saved_only, state.stored)
                } else {
                    stringResource(R.string.similarity_index_counts, state.stored, progress.currentPhotos)
                }
                Text(countText, style = MaterialTheme.typography.bodyMedium)
                if (progress.processed > 0 || progress.unattempted > 0 || progress.attempted > 0) {
                    Text(
                        stringResource(
                            R.string.similarity_index_run_counts,
                            pluralStringResource(R.plurals.similarity_index_processed, progress.processed, progress.processed),
                            pluralStringResource(R.plurals.similarity_index_unattempted, progress.unattempted, progress.unattempted),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val skipped = progress.identityUnavailable + progress.empty + progress.unreadable + progress.oversized + progress.unsupported + progress.lowInformation
                if (skipped > 0) {
                    val skippedDetails = listOfNotNull(
                        progress.identityUnavailable.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.similarity_index_skipped_identity, it, it) },
                        progress.unsupported.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.similarity_index_skipped_unsupported, it, it) },
                        progress.unreadable.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.similarity_index_skipped_unreadable, it, it) },
                        progress.oversized.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.similarity_index_skipped_oversized, it, it) },
                        progress.lowInformation.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.similarity_index_skipped_low_information, it, it) },
                        progress.empty.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.similarity_index_skipped_empty, it, it) },
                    ).joinToString(" · ")
                    Text(
                        stringResource(R.string.similarity_index_skipped, skippedDetails),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (progress.bytesRead > 0) {
                    val context = LocalContext.current
                    Text(
                        stringResource(R.string.similarity_index_bytes, Formatter.formatShortFileSize(context, progress.bytesRead)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (isRunning) {
                Button(onClick = onStop, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.similarity_index_stop)) }
            } else {
                Button(onClick = { onStart(state.action) }, enabled = canStart && state.phase != SimilarityIndexPhase.LOADING && state.phase != SimilarityIndexPhase.CLEARING, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(startLabel))
                }
            }
            if (canRetryClose) {
                OutlinedButton(onClick = { onRetryClose(state.action) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.similarity_index_retry_close)) }
            }
        }

        HorizontalDivider()
        Text(stringResource(R.string.similarity_index_clear_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { confirmClear = true }, enabled = canClear, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.similarity_index_clear))
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.similarity_index_clear_title)) },
            text = { Text(stringResource(R.string.similarity_index_clear_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    onClear(state.action)
                }, enabled = canClear, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.similarity_index_clear_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

private fun statusText(state: SimilarityIndexUiState): Int = when (state.phase) {
    SimilarityIndexPhase.LOADING -> R.string.similarity_index_status_loading
    SimilarityIndexPhase.IDLE -> R.string.similarity_index_status_idle
    SimilarityIndexPhase.RUNNING -> R.string.similarity_index_status_running
    SimilarityIndexPhase.STOPPING -> R.string.similarity_index_status_stopping
    SimilarityIndexPhase.STOPPED -> R.string.similarity_index_status_stopped
    SimilarityIndexPhase.COMPLETE -> R.string.similarity_index_status_complete
    SimilarityIndexPhase.PARTIAL -> R.string.similarity_index_status_partial
    SimilarityIndexPhase.CLEARING -> R.string.similarity_index_status_clearing
    SimilarityIndexPhase.FAILED -> R.string.similarity_index_status_failed
    SimilarityIndexPhase.BUSY -> R.string.similarity_index_status_busy
    SimilarityIndexPhase.CLOSE_FAILED -> R.string.similarity_index_status_close
}
