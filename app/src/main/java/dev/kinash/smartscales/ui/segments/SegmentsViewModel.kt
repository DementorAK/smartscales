package dev.kinash.smartscales.ui.segments

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kinash.smartscales.core.data.Measurement
import dev.kinash.smartscales.core.data.repository.ProfileRepository
import dev.kinash.smartscales.core.model.Segment
import dev.kinash.smartscales.indicators.Indicators
import dev.kinash.smartscales.ui.SegmentsRoute
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/** A segment's impedance at the latest weighing with segments, and at the one before it. */
data class SegmentValue(val series: SegmentSeries, val ohms: Double?, val before: Double?) {
    val change: Double? get() = if (ohms != null && before != null) ohms - before else null
}

sealed interface SegmentsState {
    data object Loading : SegmentsState

    /** The profile is gone, or has no weighing with segments any more. */
    data object Gone : SegmentsState

    data class Ready(
        val profileId: Long,
        val latest: Measurement,
        /** The weighing with segments before [latest], for the change; null for the first. */
        val previous: Measurement?,
        /** The frequencies the latest weighing has, 0 for the first; a choice when there are two. */
        val frequencies: List<Int>,
        val frequency: Int,
        val values: List<SegmentValue>,
    ) : SegmentsState
}

@HiltViewModel
class SegmentsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    profiles: ProfileRepository,
    indicators: Indicators,
) : ViewModel() {
    private val route = savedStateHandle.toRoute<SegmentsRoute>()
    private val frequency = MutableStateFlow(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<SegmentsState> = profiles.observeProfiles()
        .map { all -> all.any { it.id == route.profileId } }
        .flatMapLatest { exists ->
            if (!exists) return@flatMapLatest flowOf(SegmentsState.Gone)
            combine(indicators.observeHistory(route.profileId), frequency) { history, chosen ->
                val withSegments = history.filter(::hasSegments)
                val latest = withSegments.firstOrNull() ?: return@combine SegmentsState.Gone
                val previous = withSegments.getOrNull(1)
                val frequencies = segmentFrequencies(latest)
                val shown = chosen.takeIf { it in frequencies } ?: frequencies.first()
                SegmentsState.Ready(
                    route.profileId, latest.measurement, previous?.measurement, frequencies, shown,
                    Segment.entries.map { segment ->
                        val series = SegmentSeries(segment, shown)
                        SegmentValue(series, series.valueOf(latest), previous?.let(series::valueOf))
                    },
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SegmentsState.Loading)

    fun selectFrequency(index: Int) = frequency.update { index }
}
