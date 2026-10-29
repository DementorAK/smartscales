package dev.kinash.smartscales.ui.inbox

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kinash.smartscales.R
import dev.kinash.smartscales.core.data.Measurement
import dev.kinash.smartscales.core.data.Profile
import dev.kinash.smartscales.core.data.repository.MeasurementRepository
import dev.kinash.smartscales.core.data.repository.ProfileRepository
import dev.kinash.smartscales.core.model.WeightUnit
import dev.kinash.smartscales.ui.common.BackButton
import dev.kinash.smartscales.ui.common.ProfileChoiceList
import dev.kinash.smartscales.ui.history.EmptyState
import dev.kinash.smartscales.ui.history.MeasurementRow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class InboxState(val measurements: List<Measurement> = emptyList(), val profiles: List<Profile> = emptyList())

@HiltViewModel
class InboxViewModel @Inject constructor(
    private val measurements: MeasurementRepository,
    profiles: ProfileRepository,
) : ViewModel() {
    val state: StateFlow<InboxState> = combine(measurements.observeInbox(), profiles.observeProfiles(), ::InboxState)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InboxState())

    /** Assigns the weighing to [profileId]; the snapshot comes from that profile. */
    fun assign(measurementId: Long, profileId: Long) {
        viewModelScope.launch { measurements.transfer(measurementId, profileId) }
    }
}

/** Weighings from the scale history that no profile claimed yet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(onBack: () -> Unit, onOpenMeasurement: (Long) -> Unit, viewModel: InboxViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var assigning by rememberSaveable { mutableStateOf<Long?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.inbox_title)) },
                navigationIcon = { BackButton(onBack) },
            )
        },
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            if (state.measurements.isEmpty()) {
                EmptyState(stringResource(R.string.inbox_empty))
            } else {
                LazyColumn {
                    items(state.measurements, key = { it.id }) { measurement ->
                        MeasurementRow(
                            measurement = measurement,
                            weightUnit = WeightUnit.KILOGRAM,
                            onClick = { onOpenMeasurement(measurement.id) },
                            trailing = {
                                TextButton(onClick = { assigning = measurement.id }) {
                                    Text(stringResource(R.string.inbox_assign))
                                }
                            },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    assigning?.let { measurementId ->
        val date = state.measurements.firstOrNull { it.id == measurementId }?.localDate
        AlertDialog(
            onDismissRequest = { assigning = null },
            title = { Text(stringResource(R.string.inbox_assign_title)) },
            text = {
                ProfileChoiceList(
                    profiles = state.profiles,
                    onSelect = { profile ->
                        viewModel.assign(measurementId, profile.id)
                        assigning = null
                    },
                    isEnabled = { date != null && it.isBornBy(date) },
                )
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { assigning = null }) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }
}
