package dev.kinash.smartscales.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kinash.smartscales.core.data.Measurement
import dev.kinash.smartscales.core.data.Profile
import dev.kinash.smartscales.core.data.repository.MeasurementRepository
import dev.kinash.smartscales.core.data.repository.ProfileRepository
import dev.kinash.smartscales.core.data.repository.SettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface HistoryState {
    data object Loading : HistoryState

    data class Ready(
        val profiles: List<Profile>,
        /** Selected profile, or the first one when the selection is gone; null when there are no profiles. */
        val current: Profile?,
        val measurements: List<Measurement>,
        val inboxCount: Int,
    ) : HistoryState
}

@HiltViewModel
class HistoryViewModel @Inject constructor(
    profiles: ProfileRepository,
    private val measurements: MeasurementRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<HistoryState> = combine(profiles.observeProfiles(), settings.selectedProfileId) { all, id ->
        all to (all.firstOrNull { it.id == id } ?: all.firstOrNull())
    }.flatMapLatest { (all, current) ->
        val history = current?.let { measurements.observeHistory(it.id) } ?: flowOf(emptyList())
        combine(history, measurements.observeInboxCount()) { items, inboxCount ->
            HistoryState.Ready(all, current, items, inboxCount)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryState.Loading)

    fun selectProfile(id: Long) {
        viewModelScope.launch { settings.selectProfile(id) }
    }
}
