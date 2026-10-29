package dev.kinash.smartscales.ui.history

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
import androidx.compose.material3.FloatingActionButton
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
    onEditProfile: (Long) -> Unit,
    onAddMeasurement: (profileId: Long) -> Unit,
    onOpenMeasurement: (Long) -> Unit,
    onOpenInbox: () -> Unit,
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val ready = state as? HistoryState.Ready
    val current = ready?.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (ready != null && current != null) {
                        ProfileSwitcher(ready.profiles, current, viewModel::selectProfile, onAddProfile)
                    } else {
                        Text(stringResource(R.string.app_name))
                    }
                },
                actions = {
                    if (ready != null && ready.inboxCount > 0) {
                        IconButton(onClick = onOpenInbox) {
                            BadgedBox(badge = { Badge { Text(ready.inboxCount.toString()) } }) {
                                Icon(painterResource(R.drawable.ic_inbox), stringResource(R.string.inbox_title))
                            }
                        }
                    }
                    if (current != null) {
                        IconButton(onClick = { onEditProfile(current.id) }) {
                            Icon(painterResource(R.drawable.ic_edit), stringResource(R.string.profile_edit))
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (current != null) {
                FloatingActionButton(onClick = { onAddMeasurement(current.id) }) {
                    Icon(painterResource(R.drawable.ic_add), stringResource(R.string.measurement_new))
                }
            }
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
                    items(ready.measurements, key = { it.id }) { measurement ->
                        MeasurementRow(measurement, current.weightUnit, onClick = { onOpenMeasurement(measurement.id) })
                        HorizontalDivider()
                    }
                }
            }
        }
    }
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
