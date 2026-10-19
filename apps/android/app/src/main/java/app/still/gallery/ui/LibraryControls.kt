package app.still.gallery.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.still.gallery.R
import app.still.gallery.media.MediaFilter
import app.still.gallery.media.MediaSort

@Composable
fun LibraryControls(
    filter: MediaFilter,
    sort: MediaSort,
    onFilter: (MediaFilter) -> Unit,
    onSort: (MediaSort) -> Unit,
    modifier: Modifier = Modifier,
) {
    var sortExpanded by remember { mutableStateOf(false) }
    val sortScroll = remember { ScrollState(0) }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.filter_label),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth().selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            MediaFilter.entries.forEach { option ->
                val isSelected = option == filter
                FilterChip(
                    selected = isSelected,
                    onClick = { onFilter(option) },
                    label = { Text(stringResource(option.labelResource())) },
                    leadingIcon = if (isSelected) ({
                        RadioButton(selected = true, onClick = null, modifier = Modifier.clearAndSetSemantics {})
                    }) else null,
                    modifier = Modifier.heightIn(min = 48.dp).semantics {
                        role = Role.RadioButton
                        selected = isSelected
                    },
                )
            }
        }
        Box(Modifier.align(Alignment.End)) {
            TextButton(onClick = { sortExpanded = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.sort_label, stringResource(sort.labelResource())))
            }
            DropdownMenu(
                expanded = sortExpanded,
                onDismissRequest = { sortExpanded = false },
                modifier = Modifier.selectableGroup(),
                scrollState = sortScroll,
            ) {
                MediaSort.entries.forEach { option ->
                    val isSelected = option == sort
                    DropdownMenuItem(
                        text = { Text(stringResource(option.labelResource())) },
                        onClick = {
                            sortExpanded = false
                            onSort(option)
                        },
                        trailingIcon = {
                            RadioButton(selected = isSelected, onClick = null, modifier = Modifier.clearAndSetSemantics {})
                        },
                        modifier = Modifier.heightIn(min = 48.dp).semantics {
                            role = Role.RadioButton
                            selected = isSelected
                        },
                    )
                }
            }
        }
    }
}

@StringRes
private fun MediaFilter.labelResource(): Int = when (this) {
    MediaFilter.ALL -> R.string.filter_all
    MediaFilter.PHOTOS -> R.string.filter_photos
    MediaFilter.VIDEOS -> R.string.filter_videos
}

@StringRes
private fun MediaSort.labelResource(): Int = when (this) {
    MediaSort.NEWEST -> R.string.sort_newest
    MediaSort.OLDEST -> R.string.sort_oldest
    MediaSort.NAME -> R.string.sort_name
}
