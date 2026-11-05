package dev.kinash.smartscales.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kinash.smartscales.core.data.Measurement
import dev.kinash.smartscales.core.data.Profile
import dev.kinash.smartscales.core.data.repository.MeasurementRepository
import dev.kinash.smartscales.core.data.repository.ProfileRepository
import dev.kinash.smartscales.core.data.repository.SettingsRepository
import dev.kinash.smartscales.indicators.IndicatorHistory
import dev.kinash.smartscales.indicators.Indicators
import dev.kinash.smartscales.ui.chart.seriesId
import dev.kinash.smartscales.ui.chart.seriesPoints
import dev.kinash.smartscales.ui.goal.goalProgress
import dev.kinash.smartscales.ui.segments.hasSegments
import dev.kinash.smartscales.ui.segments.segmentFrequencies
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import javax.inject.Inject

sealed interface HistoryState {
    data object Loading : HistoryState

    data class Ready(
        val profiles: List<Profile>,
        /** Selected profile, or the first one when the selection is gone; null when there are no profiles. */
        val current: Profile?,
        val measurements: List<Measurement>,
        val inboxCount: Int,
        /** Indicators of the newest measurement. */
        val cards: List<IndicatorCard> = emptyList(),
        /** How to compare two weighings, until the person has done it or closed the hint; with two or more only. */
        val compareHint: Boolean = false,
        /** The profile's tags to filter [measurements] by; the cards stay on the latest weighing. */
        val tags: List<String> = emptyList(),
        /** The tags on, normalized; [measurements] are those with all of them. */
        val tagFilter: Set<String> = emptySet(),
        /** All the profile's weighings, for "none with these tags" when the filter leaves none. */
        val total: Int = 0,
        /**
         * The latest weighing with the segments of an 8-electrode scale, which the segments screen shows: the card
         * stays after a manual entry or a 4-electrode weighing, and then says which weighing it is. Null without any.
         */
        val segments: Measurement? = null,
        /** [segments] is not the latest weighing. */
        val segmentsEarlier: Boolean = false,
        /** The frequencies [segments] has, 0 for the first. */
        val segmentFrequencies: List<Int> = emptyList(),
    ) : HistoryState
}

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val profiles: ProfileRepository,
    private val measurements: MeasurementRepository,
    private val settings: SettingsRepository,
    private val indicators: Indicators,
    private val clock: Clock,
    private val tagFilters: TagFilters,
) : ViewModel() {

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<HistoryState> = combine(profiles.observeProfiles(), settings.selectedProfileId) { all, id ->
        all to (all.firstOrNull { it.id == id } ?: all.firstOrNull())
    }.flatMapLatest { (all, current) ->
        val history = current?.let { indicators.observeHistory(it.id) } ?: flowOf(IndicatorHistory(indicators.model, emptyList()))
        val goals = current?.let { profiles.observeGoals(it.id) } ?: flowOf(emptyMap())
        val filter = current?.let { tagFilters.of(it.id) } ?: flowOf(emptySet())
        combine(history, measurements.observeInboxCount(), settings.compareHintSeen, goals, filter) { items, inboxCount, hintSeen, goals, filter ->
            val newest = items.firstOrNull()
            val cards = newest?.results?.let { results ->
                newest.growth?.let { childCards(items.model, results, it, newest.measurement) }
                    ?: (listOf(weightCard(newest)) + indicatorCards(items.model, results, newest.withImpedance, newest.impedance, current?.let(::cardsOf) ?: DEFAULT_CARDS))
            }.orEmpty().map { card ->
                // A goal of the card's indicator: children's weight card has the weight's goal
                // None for a child, whose weight is read by its percentile
                if (newest?.growth != null) return@map card
                val goal = goals[seriesId(card.id)] ?: return@map card
                card.copy(goal = goalProgress(goal, seriesPoints(items.model, items, seriesId(card.id)), clock.instant()))
            }
            val history = items.map { it.measurement }
            val withSegments = items.firstOrNull(::hasSegments)
            val tags = profileTags(history)
            val active = activeFilter(filter, tags)
            HistoryState.Ready(
                all, current, history.filter { matches(it, active) }, inboxCount, cards,
                compareHint = !hintSeen && items.size >= 2,
                tags = tags,
                tagFilter = active,
                total = history.size,
                segments = withSegments?.measurement,
                segmentsEarlier = withSegments != null && withSegments !== newest,
                segmentFrequencies = withSegments?.let(::segmentFrequencies).orEmpty(),
            )
        }.flowOn(Dispatchers.Default)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryState.Loading)

    fun toggleTag(tag: String) {
        val profile = (state.value as? HistoryState.Ready)?.current ?: return
        tagFilters.toggle(profile.id, tag)
    }

    fun hideCompareHint() {
        viewModelScope.launch { settings.setCompareHintSeen() }
    }

    fun selectProfile(id: Long) {
        viewModelScope.launch { settings.selectProfile(id) }
    }
}
