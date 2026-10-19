package app.still.gallery.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.still.gallery.R
import app.still.gallery.media.DeviceMedia
import app.still.gallery.media.DriftDay
import app.still.gallery.media.DriftDayWindow
import app.still.gallery.media.DriftSnapshot
import app.still.gallery.media.DriftTarget
import app.still.gallery.media.MediaItem
import app.still.gallery.media.sourceUiToken
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.abs

@Composable
fun DriftRibbon(
    items: List<MediaItem>, snapshot: DriftSnapshot, focused: DriftTarget,
    device: DeviceMedia, generation: Int, favorite: Boolean, heading: String,
    day: DriftDay?, rail: DriftDayWindow?, hasUnknownDate: Boolean,
    canInteract: () -> Boolean,
    onMove: (Int) -> Unit, onJump: (LocalDate?) -> Boolean, onPage: (Int) -> Unit,
    onOpen: () -> Unit, onFavorite: () -> Unit, onSelect: () -> Unit, onGrid: () -> Unit,
) {
    val item = items.getOrNull(focused.index) ?: return
    if (sourceUiToken(item) != focused.sourceUiToken) return
    val openLabel = stringResource(if (item.isVideo) R.string.drift_open_video else R.string.drift_open_photo)
    val currentPermission by rememberUpdatedState(canInteract)
    var jumpOpen by remember(snapshot) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(heading, Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.displayLarge)
        BoxWithConstraints(Modifier.fillMaxWidth().clipToBounds()) {
            val cardWidth = (maxWidth * .66f).coerceIn(180.dp, 280.dp)
            val cardHeight = cardWidth * 1.3f
            val density = LocalDensity.current
            val widthPx = with(density) { maxWidth.toPx() }
            var drag by remember(snapshot, focused.sourceUiToken) { mutableFloatStateOf(0f) }
            var dragging by remember(snapshot, focused.sourceUiToken) { mutableStateOf(false) }
            val movement by animateFloatAsState(drag, if (dragging) snap() else spring(dampingRatio = .82f, stiffness = 300f), label = "ribbon release")
            Box(Modifier.fillMaxWidth().height(cardHeight + 54.dp).pointerInput(snapshot, focused.sourceUiToken) {
                detectHorizontalDragGestures(
                    onDragStart = { dragging = true; drag = 0f },
                    onDragCancel = { dragging = false; drag = 0f },
                    onDragEnd = {
                        val direction = if (abs(drag) > widthPx * .12f) if (drag < 0) 1 else -1 else 0
                        dragging = false; drag = 0f
                        if (direction != 0 && currentPermission()) onMove(direction)
                    },
                    onHorizontalDrag = { change, amount ->
                        if (currentPermission()) { change.consume(); drag = (drag + amount).coerceIn(-widthPx * .6f, widthPx * .6f) }
                    },
                )
            }) {
                // No full-library composition or decode: only the five neighboring source tokens.
                for (index in driftCardIndices(items.size, focused.index)) {
                    val media = items[index]
                    val slot = index - focused.index
                    key(sourceUiToken(media)) {
                        val x by animateFloatAsState(slot * .86f, spring(dampingRatio = .82f, stiffness = 300f), label = "ribbon position")
                        val tilt by animateFloatAsState(if (slot == 0) -5f else slot * 12f, spring(dampingRatio = .82f, stiffness = 300f), label = "ribbon tilt")
                        val scale by animateFloatAsState(if (slot == 0) 1f else if (abs(slot) == 1) .83f else .69f, spring(dampingRatio = .82f, stiffness = 300f), label = "ribbon depth")
                        val action = if (slot == 0) Modifier.combinedClickable(
                            onClickLabel = openLabel,
                            onClick = { if (currentPermission()) onOpen() },
                            onLongClickLabel = stringResource(R.string.select),
                            onLongClick = { if (currentPermission()) onSelect() },
                        ) else Modifier.clearAndSetSemantics { }
                        Box(Modifier.align(Alignment.TopCenter).padding(top = 22.dp).size(cardWidth, cardHeight)
                            .graphicsLayer {
                                translationX = x * cardWidth.toPx() - 12.dp.toPx() + movement * .7f
                                translationY = abs(slot) * 16.dp.toPx()
                                rotationZ = tilt; scaleX = scale; scaleY = scale
                                shadowElevation = if (slot == 0) 18.dp.toPx() else 8.dp.toPx()
                            }
                            .then(Modifier.then(if (slot == 0) Modifier else Modifier.graphicsLayer { alpha = .65f }))
                            .zIndex(if (slot == 0) 10f else 5f - abs(slot)).clip(RoundedCornerShape(6.dp)).then(action)) {
                            LibraryImage(media, device, generation, Modifier.fillMaxSize(), edge = 512)
                            if (media.isVideo) Text(durationLabel(media.durationMs),
                                modifier = Modifier.align(Alignment.BottomEnd).background(DriftInk.copy(alpha = .8f)).padding(8.dp),
                                style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
        Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(item.name, style = MaterialTheme.typography.headlineMedium)
            Text(day?.date?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)) ?: stringResource(R.string.unknown_date),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.drift_position, focused.index + 1, snapshot.itemCount),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = { if (currentPermission()) onOpen() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(openLabel) }
                TextButton(onClick = { if (currentPermission()) onFavorite() }, modifier = Modifier.heightIn(min = 48.dp)) {
                    MarkIcon(Mark.HEART, null, color = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    Text(stringResource(if (favorite) R.string.unfavorite else R.string.favorite), Modifier.padding(start = 8.dp))
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { if (currentPermission()) onMove(-1) }, enabled = focused.index > 0, modifier = Modifier.heightIn(min = 48.dp)) {
                    MarkIcon(Mark.BACK, null); Text(stringResource(R.string.drift_previous), Modifier.padding(start = 6.dp))
                }
                TextButton(onClick = { if (currentPermission()) onMove(1) }, enabled = focused.index + 1 < snapshot.itemCount, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.drift_next), Modifier.padding(end = 6.dp)); MarkIcon(Mark.NEXT, null)
                }
                TextButton(onClick = { if (currentPermission()) onGrid() }, modifier = Modifier.heightIn(min = 48.dp)) {
                    MarkIcon(Mark.GRID, null); Text(stringResource(R.string.drift_grid), Modifier.padding(start = 6.dp))
                }
            }
        }
        HorizontalDivider(Modifier.padding(top = 8.dp))
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.drift_dates), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                TextButton(onClick = { if (currentPermission()) jumpOpen = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                    MarkIcon(Mark.CALENDAR, null); Text(stringResource(R.string.drift_jump), Modifier.padding(start = 8.dp))
                }
            }
            if (rail != null) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(rail.days, key = { it.date?.toString() ?: "unknown" }) { target ->
                        val full = target.date?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)) ?: stringResource(R.string.unknown_date)
                        val label = target.date?.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())) ?: stringResource(R.string.unknown_date)
                        FilterChip(selected = day?.date == target.date,
                            onClick = { if (currentPermission()) onJump(target.date) },
                            label = { Text(label) },
                            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = full })
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(onClick = { if (currentPermission()) rail.previousStart?.let(onPage) }, enabled = rail.previousStart != null, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.drift_previous_dates)) }
                    TextButton(onClick = { if (currentPermission()) rail.nextStart?.let(onPage) }, enabled = rail.nextStart != null, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.drift_next_dates)) }
                }
            }
            Text(stringResource(R.string.drift_date_scope), Modifier.padding(bottom = 24.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (jumpOpen) {
        var input by remember(snapshot) { mutableStateOf("") }
        var dateError by remember(snapshot) { mutableStateOf<Int?>(null) }
        AlertDialog(onDismissRequest = { jumpOpen = false },
            title = { Text(stringResource(R.string.drift_jump)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.drift_date_format))
                    OutlinedTextField(value = input, onValueChange = { input = it; dateError = null }, singleLine = true,
                        label = { Text(stringResource(R.string.drift_date_label)) }, isError = dateError != null, modifier = Modifier.fillMaxWidth())
                    if (dateError != null) Text(stringResource(dateError!!), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    if (hasUnknownDate) TextButton(onClick = { if (currentPermission() && onJump(null)) jumpOpen = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.unknown_date)) }
                }
            },
            confirmButton = { TextButton(onClick = {
                if (currentPermission()) {
                    val date = try { LocalDate.parse(input.trim()) } catch (_: DateTimeParseException) { null }
                    if (date == null) dateError = R.string.drift_date_invalid
                    else if (onJump(date)) jumpOpen = false else dateError = R.string.drift_date_absent
                }
            }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.drift_jump)) } },
            dismissButton = { TextButton(onClick = { jumpOpen = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
