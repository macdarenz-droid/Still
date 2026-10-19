package app.still.gallery.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.still.gallery.R
import kotlin.math.min
import kotlin.math.roundToInt

/** All callbacks carry the owner's exact opening identity. No saved media or outgoing animation. */
@Composable
fun CompareScreen(
    state: CompareUiState<ComparePreview>,
    onClose: () -> Unit,
    onStop: () -> Unit,
    onChange: (Float, Float, Float) -> Unit,
    onReset: () -> Unit,
) {
    if (!state.visible) return
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BackHandler(onBack = onClose)
        BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
            val wide = maxWidth >= 600.dp
            val paneHeight = ((maxHeight - 300.dp) / if (wide) 1 else 2).coerceIn(140.dp, 420.dp)
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.compare_title), Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.compare_close)) }
                }
                Text(stringResource(R.string.compare_scope), style = MaterialTheme.typography.bodySmall)
                if (state.phase == ComparePhase.READY && state.previews.size == 2) {
                    if (wide) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        state.previews.forEachIndexed { index, preview ->
                            ComparePane(state, preview, index, paneHeight, onChange, Modifier.weight(1f))
                        }
                    } else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        state.previews.forEachIndexed { index, preview -> ComparePane(state, preview, index, paneHeight, onChange, Modifier.fillMaxWidth()) }
                    }
                    val linkedState = stringResource(R.string.compare_linked_state, (state.transform.zoom * 100).roundToInt())
                    Text(linkedState, Modifier.semantics { stateDescription = linkedState }, style = MaterialTheme.typography.bodyMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { onChange(1f / 1.5f, 0f, 0f) }, enabled = state.transform.zoom > 1f, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.compare_zoom_out)) }
                        TextButton(onClick = { onChange(1.5f, 0f, 0f) }, enabled = state.transform.zoom < 5f, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.compare_zoom_in)) }
                        TextButton(onClick = onReset, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.compare_reset)) }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ComparePanButton(R.string.compare_left, R.string.compare_pan_left, state.transform.zoom > 1f) { onChange(1f, -.15f, 0f) }
                        ComparePanButton(R.string.compare_right, R.string.compare_pan_right, state.transform.zoom > 1f) { onChange(1f, .15f, 0f) }
                        ComparePanButton(R.string.compare_up, R.string.compare_pan_up, state.transform.zoom > 1f) { onChange(1f, 0f, -.15f) }
                        ComparePanButton(R.string.compare_down, R.string.compare_pan_down, state.transform.zoom > 1f) { onChange(1f, 0f, .15f) }
                    }
                } else {
                    val status = stringResource(compareStatusLabel(state.phase, state.failure))
                    Text(status, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyLarge)
                    if (state.phase == ComparePhase.PREPARING) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(stringResource(if (state.progress.stage == CompareStage.COPYING) R.string.compare_copy_progress else R.string.compare_preview_progress, state.progress.completed))
                        Button(onClick = onStop, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.compare_stop)) }
                    }
                    if (state.phase == ComparePhase.STOPPING) Text(stringResource(R.string.compare_stopping_detail))
                }
            }
        }
    }
}

@Composable
private fun ComparePane(state: CompareUiState<ComparePreview>, preview: ComparePreview, index: Int, height: Dp,
    onChange: (Float, Float, Float) -> Unit, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val label = stringResource(R.string.compare_photo_label, index + 1, state.names.getOrElse(index) { "" })
        Text(label, style = MaterialTheme.typography.bodyMedium)
        val bitmap = preview.bitmap
        if (bitmap != null) BoxWithConstraints(Modifier.fillMaxWidth().height(height).clipToBounds(), contentAlignment = Alignment.Center) {
            val aspect = bitmap.width.toFloat() / bitmap.height
            Image(bitmap.asImageBitmap(), label, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().pointerInput(state.request) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val fittedWidth = min(size.width.toFloat(), size.height * aspect)
                        val fittedHeight = min(size.height.toFloat(), size.width / aspect)
                        if (fittedWidth > 0f && fittedHeight > 0f) onChange(zoom, pan.x / fittedWidth, pan.y / fittedHeight)
                    }
                }.graphicsLayer {
                    scaleX = state.transform.zoom; scaleY = state.transform.zoom
                    translationX = state.transform.x * min(size.width, size.height * aspect)
                    translationY = state.transform.y * min(size.height, size.width / aspect)
                })
        }
    }
}

@Composable
private fun ComparePanButton(label: Int, description: Int, enabled: Boolean, onClick: () -> Unit) {
    val spoken = stringResource(description)
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = spoken }) { Text(stringResource(label)) }
}

fun compareStatusLabel(phase: ComparePhase, failure: CompareFailure): Int = when (phase) {
    ComparePhase.PREPARING -> R.string.compare_preparing
    ComparePhase.STOPPING -> R.string.compare_stopping
    ComparePhase.CANCELLED -> R.string.compare_stopped
    ComparePhase.CLEANING -> R.string.compare_cleaning
    ComparePhase.CLEANUP_FAILED -> R.string.compare_cleanup_failed
    ComparePhase.BUSY -> R.string.compare_busy
    else -> when (failure) {
        CompareFailure.SIZE_LIMIT -> R.string.compare_size_limit
        CompareFailure.SCOPE_LIMIT -> R.string.compare_scope_limit
        CompareFailure.SOURCE_CHANGED -> R.string.compare_source_changed
        CompareFailure.INVALID_SELECTION -> R.string.compare_invalid_selection
        CompareFailure.BUSY -> R.string.compare_busy
        CompareFailure.CLEANUP -> R.string.compare_cleanup_failed
        CompareFailure.PREVIEW -> R.string.compare_preview_failed
        CompareFailure.UNAVAILABLE -> R.string.compare_failed
    }
}

/** This status stays in the gallery after its images/dialog have been removed. */
@Composable
fun CompareStorageNotice(state: CompareUiState<ComparePreview>, onRetry: () -> Unit) {
    if (state.visible || state.phase !in listOf(ComparePhase.CLEANING, ComparePhase.CLEANUP_FAILED, ComparePhase.BUSY)) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(stringResource(compareStatusLabel(state.phase, state.failure)), Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall)
        if (state.phase != ComparePhase.CLEANING) TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.compare_retry_cleanup)) }
    }
}
