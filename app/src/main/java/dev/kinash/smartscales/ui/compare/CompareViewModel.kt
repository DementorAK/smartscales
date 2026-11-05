package dev.kinash.smartscales.ui.compare

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kinash.smartscales.core.data.Measurement
import dev.kinash.smartscales.core.data.Profile
import dev.kinash.smartscales.core.data.repository.ProfileRepository
import dev.kinash.smartscales.indicators.Indicators
import dev.kinash.smartscales.ui.CompareRoute
import dev.kinash.smartscales.ui.history.cardsOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

sealed interface CompareState {
    data object Loading : CompareState

    /** The profile or one of the weighings is gone (deleted, moved to another profile). */
    data object Gone : CompareState

    data class Ready(
        val profile: Profile,
        /** The earlier of the two, whichever was picked first. */
        val before: Measurement,
        val after: Measurement,
        val rows: List<CompareRow>,
    ) : CompareState
}

@HiltViewModel
class CompareViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    profiles: ProfileRepository,
    indicators: Indicators,
) : ViewModel() {
    private val route = savedStateHandle.toRoute<CompareRoute>()

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<CompareState> = profiles.observeProfiles()
        .map { all -> all.firstOrNull { it.id == route.profileId } }
        .flatMapLatest { profile ->
            if (profile == null) return@flatMapLatest flowOf(CompareState.Gone)
            indicators.observeHistory(profile.id).map { history ->
                val pair = listOf(route.first, route.second).mapNotNull { id -> history.firstOrNull { it.measurement.id == id } }
                if (pair.size < 2) return@map CompareState.Gone
                val (before, after) = pair.sortedBy { it.measurement.takenAt }
                // A child's list only when both are a child's: one across the 18th birthday keeps the adult's values
                val child = before.growth != null && after.growth != null
                val ids = if (child) CHILD_COMPARE else compareIds(indicators.model, cardsOf(profile))
                CompareState.Ready(profile, before.measurement, after.measurement, compareRows(indicators.model, before, after, ids))
            }.flowOn(Dispatchers.Default)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CompareState.Loading)
}
