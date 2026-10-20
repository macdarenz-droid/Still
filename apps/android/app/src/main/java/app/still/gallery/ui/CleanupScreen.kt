package app.still.gallery.ui

import android.text.format.Formatter
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.still.gallery.R

/** Display only: the owner revalidates authority, scan identity and protection revision. */
@Composable
fun CleanupScreen(
    state: CleanupUiState,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onKeeperChange: (groupId: Int, memberId: Int, keep: Boolean) -> Unit,
    onProtectionChange: (groupId: Int, protect: Boolean) -> Unit,
    onOpenMember: (Int, Int) -> Unit,
    onChangeAccess: () -> Unit,
    onRefresh: () -> Unit,
    onRetryProtection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val confirmations = remember(
        state.groups, state.phase, state.hasAccess, state.protectionState, state.protectionWriteFailed, state.interactionIdentity,
    ) { CleanupConfirmationState(state.interactionIdentity) }
    val currentConfirmations = rememberUpdatedState(confirmations)
    DisposableEffect(confirmations) {
        onDispose { confirmations.invalidate() }
    }
    val groups = if (state.phase == CleanupPhase.COMPLETE && state.hasAccess) state.groups else emptyList()
    val protectionReady = state.protectionState == CleanupProtectionState.READY

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "cleanup-status", contentType = "status") {
            CleanupStatus(state, onStart, onCancel, onChangeAccess, onRefresh, onRetryProtection)
        }
        if (state.phase == CleanupPhase.COMPLETE && state.hasAccess &&
            !state.sourceLimitExceeded && groups.isEmpty()
        ) {
            item(key = "cleanup-empty", contentType = "empty") {
                Text(
                    stringResource(if (state.coverage.compared == 0) R.string.cleanup_no_compared else R.string.cleanup_no_matches),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
        groups.forEachIndexed { index, group ->
            item(key = "group-${group.id}", contentType = "group-heading") {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            pluralStringResource(R.plurals.cleanup_group_title, group.members.size, index + 1, group.members.size),
                            Modifier.semantics { heading() },
                            style = MaterialTheme.typography.titleMedium,
                        )
                        CleanupReviewStatus(state, group)
                    }
                }
            }
            // Members remain individual lazy items even when one group is very large.
            items(group.members, key = { "group-${group.id}-member-${it.id}" }, contentType = { "member" }) { member ->
                CleanupMember(
                    member = member,
                    showProtection = protectionReady,
                    onKeeperChange = { keep -> onKeeperChange(group.id, member.id, keep) },
                    onOpen = { onOpenMember(group.id, member.id) },
                )
            }
            item(key = "group-${group.id}-protection", contentType = "group-action") {
                TextButton(
                    onClick = { confirmations.open(group.id, !group.protected) },
                    enabled = protectionReady,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text(
                        stringResource(if (group.protected) R.string.cleanup_unprotect_group else R.string.cleanup_protect_group),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }

    val request = confirmations.current
    val group = groups.firstOrNull { it.id == request?.groupId }
    if (request != null && group != null && protectionReady && group.protected != request.protect) {
        val scroll = remember(request) { ScrollState(0) }
        AlertDialog(
            onDismissRequest = { confirmations.dismiss(request) },
            title = {
                Text(stringResource(if (request.protect) R.string.cleanup_confirm_protect_title else R.string.cleanup_confirm_unprotect_title))
            },
            text = {
                Column(Modifier.verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(pluralStringResource(R.plurals.cleanup_group_title, group.members.size, groups.indexOf(group) + 1, group.members.size))
                    Text(stringResource(if (request.protect) R.string.cleanup_confirm_protect_body else R.string.cleanup_confirm_unprotect_body))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (currentConfirmations.value === confirmations) {
                            confirmations.confirm(request, state.interactionIdentity) {
                                onProtectionChange(request.groupId, request.protect)
                            }
                        }
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(if (request.protect) R.string.cleanup_confirm_protect else R.string.cleanup_confirm_unprotect))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmations.dismiss(request) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun CleanupStatus(
    state: CleanupUiState,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onChangeAccess: () -> Unit,
    onRefresh: () -> Unit,
    onRetryProtection: () -> Unit,
) {
    val running = state.phase == CleanupPhase.SCANNING || state.phase == CleanupPhase.STOPPING
    val phaseMessage = when (state.phase) {
        CleanupPhase.IDLE -> null
        CleanupPhase.SCANNING -> R.string.cleanup_scanning
        CleanupPhase.STOPPING -> R.string.cleanup_stopping
        CleanupPhase.COMPLETE -> R.string.cleanup_complete
        CleanupPhase.CANCELLED -> R.string.cleanup_cancelled
        CleanupPhase.INVALIDATED -> R.string.cleanup_invalidated
        CleanupPhase.BUSY -> R.string.cleanup_busy
        CleanupPhase.FAILED -> R.string.cleanup_failed
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.cleanup_scope), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!state.hasAccess) CleanupNotice(stringResource(R.string.cleanup_no_access))
        else if (state.limitedAccess) Text(stringResource(R.string.cleanup_limited_access), style = MaterialTheme.typography.bodyMedium)
        if (phaseMessage != null) {
            CleanupNotice(stringResource(phaseMessage), error = state.phase == CleanupPhase.FAILED)
        }
        if (running) {
            val progressLabel = stringResource(if (state.phase == CleanupPhase.STOPPING) R.string.cleanup_stopping else R.string.cleanup_scanning)
            LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = progressLabel })
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (running) {
                Button(onClick = onCancel, enabled = state.phase == CleanupPhase.SCANNING, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.cleanup_cancel_scan))
                }
            } else {
                Button(
                    onClick = onStart,
                    enabled = state.canStart && state.hasAccess && state.protectionState != CleanupProtectionState.SAVING,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(if (state.phase == CleanupPhase.COMPLETE) R.string.cleanup_scan_again else R.string.cleanup_start))
                }
            }
            if (!state.hasAccess || state.limitedAccess) {
                TextButton(onClick = onChangeAccess, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.change_access))
                }
            }
        }
        if (state.hasAccess && !state.canStart && !running) {
            CleanupNotice(stringResource(R.string.cleanup_gallery_unready))
            TextButton(onClick = onRefresh, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.refresh))
            }
        }
        if (state.sourceLimitExceeded) CleanupNotice(stringResource(R.string.cleanup_source_limit), error = true)
        if (state.hasAccess && state.phase != CleanupPhase.INVALIDATED && state.coverage.scopeKnown) {
            val coverage = state.coverage
            Text(pluralStringResource(R.plurals.cleanup_considered, coverage.considered, coverage.considered), style = MaterialTheme.typography.bodyMedium)
            Text(pluralStringResource(R.plurals.cleanup_candidates, coverage.candidates, coverage.candidates), style = MaterialTheme.typography.bodyMedium)
            Text(pluralStringResource(R.plurals.cleanup_checked, coverage.checked, coverage.checked), style = MaterialTheme.typography.bodyMedium)
            Text(pluralStringResource(R.plurals.cleanup_compared, coverage.compared, coverage.compared), style = MaterialTheme.typography.bodyMedium)
            Text(pluralStringResource(R.plurals.cleanup_empty, coverage.empty, coverage.empty), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(pluralStringResource(R.plurals.cleanup_unavailable, coverage.unavailable, coverage.unavailable), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(pluralStringResource(R.plurals.cleanup_changed, coverage.changed, coverage.changed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(pluralStringResource(R.plurals.cleanup_omitted, coverage.omitted, coverage.omitted), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(pluralStringResource(R.plurals.cleanup_aliases, coverage.aliases, coverage.aliases), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (running && !state.coverage.scopeKnown) {
            Text(stringResource(R.string.cleanup_coverage_unknown), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        CleanupNotice(stringResource(protectionMessage(state.protectionState)))
        if (state.protectionState == CleanupProtectionState.LOADING || state.protectionState == CleanupProtectionState.SAVING) {
            val progressLabel = stringResource(protectionMessage(state.protectionState))
            LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = progressLabel })
        }
        if (state.protectionWriteFailed) CleanupNotice(stringResource(R.string.cleanup_protection_write_failed), error = true)
        if (state.protectionState == CleanupProtectionState.UNKNOWN || state.protectionWriteFailed) {
            TextButton(
                onClick = onRetryProtection,
                enabled = state.protectionState == CleanupProtectionState.UNKNOWN || state.protectionState == CleanupProtectionState.READY,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.cleanup_retry_protection))
            }
        }
    }
}

@Composable
private fun CleanupReviewStatus(state: CleanupUiState, group: CleanupGroupUi) {
    when {
        state.protectionState != CleanupProtectionState.READY -> Text(stringResource(protectionMessage(state.protectionState)), style = MaterialTheme.typography.bodyMedium)
        group.reviewReason == CleanupReviewReason.PROTECTION_UNKNOWN -> Text(stringResource(R.string.cleanup_protection_unknown), style = MaterialTheme.typography.bodyMedium)
        group.reviewReason == CleanupReviewReason.FAVORITE_UNKNOWN -> Text(stringResource(R.string.cleanup_favorite_unknown), style = MaterialTheme.typography.bodyMedium)
        group.reviewReason == CleanupReviewReason.NEEDS_KEEPER -> Text(stringResource(R.string.cleanup_review_needs_keeper), style = MaterialTheme.typography.bodyMedium)
        else -> Text(pluralStringResource(R.plurals.cleanup_review_extras, group.extras, group.extras), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun CleanupMember(member: CleanupMemberUi, showProtection: Boolean, onKeeperChange: (Boolean) -> Unit, onOpen: () -> Unit) {
    val name = member.name.ifBlank { stringResource(R.string.cleanup_unnamed) }
    val readableSize = Formatter.formatFileSize(LocalContext.current, member.byteCount)
    OutlinedCard(Modifier.fillMaxWidth().padding(start = 12.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(name, style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.cleanup_member_details, stringResource(if (member.isVideo) R.string.video else R.string.photo), readableSize),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (member.favorite == CleanupFavoriteState.FAVORITE) Text(stringResource(R.string.cleanup_favorite), style = MaterialTheme.typography.bodyMedium)
            if (member.favorite == CleanupFavoriteState.UNKNOWN) Text(stringResource(R.string.cleanup_favorite_unknown), style = MaterialTheme.typography.bodyMedium)
            if (showProtection && member.protected) Text(stringResource(R.string.cleanup_protected), style = MaterialTheme.typography.bodyMedium)
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .toggleable(value = member.manualKeeper, role = Role.Checkbox, onValueChange = onKeeperChange),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = member.manualKeeper, onCheckedChange = null, modifier = Modifier.clearAndSetSemantics {})
                Text(stringResource(R.string.cleanup_keep_member, name), Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
            }
            TextButton(onClick = onOpen, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.cleanup_open_member, name), textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun CleanupNotice(message: String, error: Boolean = false) {
    Text(
        message,
        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        style = MaterialTheme.typography.bodyMedium,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@androidx.annotation.StringRes
private fun protectionMessage(state: CleanupProtectionState): Int = when (state) {
    CleanupProtectionState.LOADING -> R.string.cleanup_protection_loading
    CleanupProtectionState.READY -> R.string.cleanup_protection_ready
    CleanupProtectionState.UNKNOWN -> R.string.cleanup_protection_unknown
    CleanupProtectionState.SAVING -> R.string.cleanup_protection_saving
}
