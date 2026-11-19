package app.still.gallery.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.still.gallery.R

@Composable
fun DriftHeader(
    title: String,
    subtitle: String?,
    selecting: Boolean,
    canBrowse: Boolean,
    showBack: Boolean,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onOptions: () -> Unit,
    onSettings: () -> Unit,
    onCancelSelection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        if (showBack && !selecting) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) {
                    MarkIcon(Mark.BACK, stringResource(R.string.back))
                }
                DriftTitle(title, subtitle, Modifier.weight(1f))
            }
        } else {
            DriftTitle(title, subtitle)
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            if (selecting) {
                TextButton(
                    onClick = onCancelSelection,
                    modifier = Modifier.heightIn(min = 48.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                ) {
                    MarkIcon(Mark.CLOSE, null, color = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.cancel), Modifier.padding(start = 6.dp))
                }
            } else if (canBrowse) {
                val colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(
                    onClick = onSearch,
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = colors,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                ) {
                    Text(stringResource(R.string.drift_search))
                }
                TextButton(
                    onClick = onOptions,
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = colors,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                ) {
                    Text(stringResource(R.string.drift_options))
                }
            }
            TextButton(
                onClick = onSettings,
                modifier = Modifier.heightIn(min = 48.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
            ) {
                MarkIcon(Mark.SETTINGS, null)
                Text(stringResource(R.string.settings), Modifier.padding(start = 6.dp))
            }
        }
    }
}

@Composable
private fun DriftTitle(title: String, subtitle: String?, modifier: Modifier = Modifier) {
    Column(modifier.heightIn(min = 48.dp), verticalArrangement = Arrangement.Center) {
        Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        if (!subtitle.isNullOrBlank()) {
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

data class DriftDockEntry(val key: String, val label: String, val mark: Mark, val selected: Boolean)

@Composable
fun DriftDock(entries: List<DriftDockEntry>, onNavigate: (String) -> Unit, modifier: Modifier = Modifier) {
    if (entries.isEmpty()) return
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
        BoxWithConstraints(Modifier.fillMaxWidth().navigationBarsPadding()) {
            val columns = if (LocalDensity.current.fontScale > 1.3f || maxWidth < 330.dp) 2 else 4
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                entries.chunked(columns).forEach { row ->
                    Row(
                        Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        row.forEach { entry ->
                            key(entry.key) {
                                DriftDockItem(entry, { onNavigate(entry.key) }, Modifier.weight(1f).fillMaxHeight())
                            }
                        }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun DriftDockItem(entry: DriftDockEntry, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val background = if (entry.selected) colors.primary else colors.surface
    val foreground = if (entry.selected) colors.onPrimary else colors.onSurfaceVariant
    val shape = RoundedCornerShape(18.dp)
    Surface(
        modifier = modifier.heightIn(min = 64.dp).clip(shape)
            .selectable(selected = entry.selected, role = Role.Tab, onClick = onClick),
        shape = shape,
        color = background,
        contentColor = foreground,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
        ) {
            MarkIcon(entry.mark, null, color = foreground)
            Text(
                entry.label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (entry.selected) FontWeight.SemiBold else FontWeight.Medium,
                textAlign = TextAlign.Center,
            )
        }
    }
}
