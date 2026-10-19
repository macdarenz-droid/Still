package app.still.gallery.ui

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.still.gallery.R
import app.still.gallery.media.DeviceMedia
import app.still.gallery.media.MediaItem
import app.still.gallery.similarity.SimilarityPair
import app.still.gallery.similarity.SimilarityResult
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Current display rows, kept distinct from the backend's source-derived analysis evidence. */
data class SimilarityPairDisplay(val pair: SimilarityPair, val first: MediaItem, val second: MediaItem)

@Composable
fun SimilarityScreen(state: SimilarityUiState<SimilarityResult>, pairs: List<SimilarityPairDisplay>, device: DeviceMedia,
    generation: Int, canCompare: Boolean, onCompare: (SimilarityPair) -> Unit, onClose: () -> Unit,
    onStop: () -> Unit, onRetry: () -> Unit, comparisonNotice: @Composable () -> Unit) {
    if (!state.visible) return
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BackHandler(onBack = onClose)
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.similarity_title), Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.similarity_close)) }
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item(key = "scope") { Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.similarity_scope), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.similarity_detail_warning), style = MaterialTheme.typography.bodySmall)
                    comparisonNotice()
                    Text(stringResource(similarityStatusLabel(state.phase, state.failure)), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    if (state.phase == SimilarityPhase.SCANNING) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Button(onClick = onStop, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.similarity_stop)) }
                    }
                    if (state.phase == SimilarityPhase.STOPPING) Text(stringResource(R.string.similarity_stopping_detail))
                    if (state.phase == SimilarityPhase.BUSY || state.phase == SimilarityPhase.CLOSE_FAILED) {
                        TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.similarity_retry)) }
                    }
                    SimilarityCoverage(state.coverage)
                } }
                val result = state.result
                if (result != null) {
                    item(key = "summary") { Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (pairs.size != result.pairs.size) Text(stringResource(R.string.similarity_source_changed))
                        else if (pairs.isEmpty()) Text(stringResource(if (state.coverage.featured < 2) R.string.similarity_not_enough else R.string.similarity_no_pairs))
                        else Text(pluralStringResource(R.plurals.similarity_pairs, pairs.size, pairs.size), Modifier.semantics { heading() })
                        Text(stringResource(R.string.similarity_exact_scope), style = MaterialTheme.typography.bodySmall)
                        if (!canCompare) Text(stringResource(R.string.similarity_compare_wait), style = MaterialTheme.typography.bodySmall)
                    } }
                    // The caller keys the whole screen to its opaque request/result;
                    // Bundle-safe row keys never carry action authority.
                    if (pairs.size == result.pairs.size) itemsIndexed(pairs, key = { index, _ -> "similar-pair-$index" }, contentType = { _, _ -> "pair" }) { index, display ->
                        SimilarityPairCard(index, display, device, generation, canCompare) { onCompare(display.pair) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SimilarityPairCard(index: Int, display: SimilarityPairDisplay, device: DeviceMedia, generation: Int, canCompare: Boolean, onCompare: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.similarity_pair_number, index + 1), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val stacked = maxWidth < 280.dp || LocalDensity.current.fontScale > 1.5f
                if (stacked) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SimilarityPhoto(display.first, 1, device, generation, Modifier.fillMaxWidth())
                    SimilarityPhoto(display.second, 2, device, generation, Modifier.fillMaxWidth())
                } else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SimilarityPhoto(display.first, 1, device, generation, Modifier.weight(1f))
                    SimilarityPhoto(display.second, 2, device, generation, Modifier.weight(1f))
                }
            }
            Button(onClick = onCompare, enabled = canCompare, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.similarity_compare)) }
        }
    }
}

@Composable
private fun SimilarityPhoto(item: MediaItem, position: Int, device: DeviceMedia, generation: Int, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.similarity_photo_name, position, item.name), style = MaterialTheme.typography.bodyMedium)
        Text(if (item.capturedAt <= 0) stringResource(R.string.similarity_date_unavailable) else
            DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(Instant.ofEpochMilli(item.capturedAt).atZone(ZoneId.systemDefault())), style = MaterialTheme.typography.bodySmall)
        LibraryImage(item, device, generation, Modifier.fillMaxWidth().height(150.dp), edge = 256)
    }
}

@Composable
private fun SimilarityCoverage(coverage: SimilarityCoverageUi) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(pluralStringResource(R.plurals.similarity_selected, coverage.selected, coverage.selected))
        Text(pluralStringResource(R.plurals.similarity_checked, coverage.selected, coverage.attempted, coverage.selected), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        Text(pluralStringResource(R.plurals.similarity_analyzed, coverage.featured, coverage.featured))
        listOf(R.plurals.similarity_empty to coverage.empty, R.plurals.similarity_unreadable to coverage.unreadable,
            R.plurals.similarity_oversized to coverage.oversized, R.plurals.similarity_unsupported to coverage.unsupported,
            R.plurals.similarity_low_information to coverage.lowInformation, R.plurals.similarity_budget_omitted to coverage.budgetOmitted,
            R.plurals.similarity_not_attempted to (coverage.selected - coverage.attempted).coerceAtLeast(0),
            R.plurals.similarity_exact_omitted to coverage.exactPairsOmitted, R.plurals.similarity_pairs_omitted to coverage.resultPairsOmitted,
        ).forEach { (label, count) -> if (count > 0) Text(pluralStringResource(label, count, count), style = MaterialTheme.typography.bodySmall) }
        Text(stringResource(R.string.similarity_bytes_read, Formatter.formatShortFileSize(context, coverage.admittedBytes + coverage.quotaProbeBytes)), style = MaterialTheme.typography.bodySmall)
    }
}

private fun similarityStatusLabel(phase: SimilarityPhase, failure: SimilarityFailure): Int = when (phase) {
    SimilarityPhase.SCANNING -> R.string.similarity_scanning
    SimilarityPhase.READY -> R.string.similarity_ready
    SimilarityPhase.STOPPING -> R.string.similarity_stopping
    SimilarityPhase.STOPPED -> R.string.similarity_stopped
    SimilarityPhase.SETTLING -> R.string.similarity_settling
    SimilarityPhase.BUSY -> R.string.similarity_busy
    SimilarityPhase.CLOSE_FAILED -> R.string.similarity_close_failed
    else -> when (failure) {
        SimilarityFailure.INVALID_SELECTION -> R.string.similarity_selection_limit
        SimilarityFailure.SOURCE_CHANGED -> R.string.similarity_source_changed
        SimilarityFailure.SCOPE_LIMIT -> R.string.similarity_scope_limit
        SimilarityFailure.BUSY -> R.string.similarity_busy
        SimilarityFailure.CLOSE_FAILED -> R.string.similarity_close_failed
        SimilarityFailure.UNAVAILABLE -> R.string.similarity_failed
    }
}

@Composable
fun <R : Any> SimilarityReadNotice(state: SimilarityUiState<R>, onRetry: () -> Unit) {
    if (state.visible || state.phase !in listOf(SimilarityPhase.SETTLING, SimilarityPhase.BUSY, SimilarityPhase.CLOSE_FAILED)) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(stringResource(similarityStatusLabel(state.phase, state.failure)), Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall)
        if (state.phase != SimilarityPhase.SETTLING) TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.similarity_retry)) }
    }
}
