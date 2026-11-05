package dev.kinash.smartscales.ui.history

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kinash.smartscales.R
import dev.kinash.smartscales.core.data.Profile
import dev.kinash.smartscales.ui.common.FieldSpacing
import dev.kinash.smartscales.ui.common.ProfileAvatar
import dev.kinash.smartscales.ui.common.ScreenPadding

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onAddProfile: () -> Unit,
    onAddMeasurement: (profileId: Long) -> Unit,
    onOpenMeasurement: (Long) -> Unit,
    onOpenInbox: () -> Unit,
    onWeigh: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenChart: (profileId: Long, indicator: String) -> Unit,
    onCompare: (profileId: Long, first: Long, second: Long) -> Unit,
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val ready = state as? HistoryState.Ready
    val current = ready?.current
    // Weighings picked to compare, by a long press; a third one replaces the first picked
    var selected by rememberSaveable(current?.id) { mutableStateOf(longArrayOf()) }
    BackHandler(enabled = selected.isNotEmpty()) { selected = longArrayOf() }
    val toggle = { id: Long ->
        if (selected.isEmpty()) viewModel.hideCompareHint()
        selected = if (id in selected) selected.filter { it != id }.toLongArray() else (selected + id).takeLast(2).toLongArray()
    }

    Scaffold(
        topBar = {
            if (current != null && selected.isNotEmpty()) {
                SelectionBar(
                    count = selected.size,
                    onCancel = { selected = longArrayOf() },
                    onCompare = {
                        onCompare(current.id, selected[0], selected[1])
                        selected = longArrayOf()
                    },
                )
            } else {
                MainBar(ready, current, viewModel::selectProfile, onAddProfile, onOpenInbox, onWeigh, onAddMeasurement, onOpenSettings)
            }
        },
        // Weighing on the scale is the main action: the big button, and the scale in the top bar beside manual entry
        // (the author's choice, requirements §6.1)
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onWeigh,
                icon = { Icon(painterResource(R.drawable.ic_scale), contentDescription = null) },
                text = { Text(stringResource(R.string.weighing_open)) },
            )
        },
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            when {
                ready == null -> Unit
                current == null -> EmptyState(
                    text = stringResource(R.string.history_no_profiles),
                    action = stringResource(R.string.profile_new),
                    onAction = onAddProfile,
                )
                ready.measurements.isEmpty() -> EmptyState(text = stringResource(R.string.history_empty))
                else -> LazyColumn {
                    if (ready.cards.isNotEmpty()) {
                        item(key = "cards") { IndicatorCards(ready.cards, current, onOpen = { onOpenChart(current.id, it) }) }
                        item(key = "cards-divider") { HorizontalDivider() }
                    }
                    if (ready.compareHint) item(key = "compare-hint") { CompareHint(onClose = viewModel::hideCompareHint) }
                    items(ready.measurements, key = { it.id }) { measurement ->
                        MeasurementRow(
                            measurement,
                            current.weightUnit,
                            onClick = { if (selected.isEmpty()) onOpenMeasurement(measurement.id) else toggle(measurement.id) },
                            onLongClick = { toggle(measurement.id) },
                            onLongClickLabel = stringResource(R.string.compare_action),
                            selected = (measurement.id in selected).takeIf { selected.isNotEmpty() },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

/** The top bar: the profile on the left; the inbox while it has weighings to sort, the weighing, manual entry and settings. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainBar(
    ready: HistoryState.Ready?,
    current: Profile?,
    onSelectProfile: (Long) -> Unit,
    onAddProfile: () -> Unit,
    onOpenInbox: () -> Unit,
    onWeigh: () -> Unit,
    onAddMeasurement: (profileId: Long) -> Unit,
    onOpenSettings: () -> Unit,
) {
    TopAppBar(
        title = {
            if (ready != null && current != null) {
                ProfileSwitcher(ready.profiles, current, onSelectProfile, onAddProfile)
            } else {
                Text(stringResource(R.string.app_name))
            }
        },
        // Profiles are edited and deleted in the settings; the bar keeps the weighing, manual entry and
        // settings, and the inbox while it has weighings to sort
        actions = {
            if (ready != null && ready.inboxCount > 0) {
                IconButton(onClick = onOpenInbox) {
                    BadgedBox(badge = { Badge { Text(ready.inboxCount.toString()) } }) {
                        Icon(painterResource(R.drawable.ic_inbox), stringResource(R.string.inbox_title))
                    }
                }
            }
            IconButton(onClick = onWeigh) {
                Icon(painterResource(R.drawable.ic_scale), stringResource(R.string.weighing_open))
            }
            if (current != null) {
                IconButton(onClick = { onAddMeasurement(current.id) }) {
                    Icon(painterResource(R.drawable.ic_manual_entry), stringResource(R.string.measurement_new))
                }
            }
            IconButton(onClick = onOpenSettings) {
                Icon(painterResource(R.drawable.ic_settings), stringResource(R.string.settings_title))
            }
        },
    )
}

/** A long press is found by chance only, so the history tells of it once. */
@Composable
private fun CompareHint(onClose: () -> Unit) {
    Row(Modifier.padding(start = ScreenPadding), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.compare_hint),
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        IconButton(onClick = onClose) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.compare_hint_close)) }
    }
    HorizontalDivider()
}

/** The top bar while weighings are picked to compare: two make the comparison. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionBar(count: Int, onCancel: () -> Unit, onCompare: () -> Unit) {
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onCancel) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.compare_cancel)) }
        },
        title = { Text(stringResource(if (count < 2) R.string.compare_pick_second else R.string.compare_picked)) },
        actions = {
            TextButton(onClick = onCompare, enabled = count == 2) { Text(stringResource(R.string.compare_action)) }
        },
    )
}

@Composable
private fun ProfileSwitcher(
    profiles: List<Profile>,
    current: Profile,
    onSelect: (Long) -> Unit,
    onAddProfile: () -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FieldSpacing)) {
                ProfileAvatar(current)
                Text(current.name, style = MaterialTheme.typography.titleLarge)
                Icon(painterResource(R.drawable.ic_arrow_drop_down), stringResource(R.string.profile_switch))
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            profiles.forEach { profile ->
                DropdownMenuItem(
                    text = { Text(profile.name) },
                    leadingIcon = { ProfileAvatar(profile) },
                    trailingIcon = if (profile.id == current.id) {
                        { Icon(painterResource(R.drawable.ic_check), null) }
                    } else {
                        null
                    },
                    onClick = {
                        open = false
                        onSelect(profile.id)
                    },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.profile_new)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_add), null) },
                onClick = {
                    open = false
                    onAddProfile()
                },
            )
        }
    }
}

@Composable
fun EmptyState(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(ScreenPadding, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
        if (action != null) {
            Button(onClick = onAction) { Text(action) }
        }
    }
}

