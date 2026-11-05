package dev.kinash.smartscales.ui.chart

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kinash.smartscales.core.data.Goal
import dev.kinash.smartscales.core.data.Profile
import dev.kinash.smartscales.core.data.repository.ProfileRepository
import dev.kinash.smartscales.core.formula.LocalizedText
import dev.kinash.smartscales.indicators.Indicators
import dev.kinash.smartscales.indicators.MeasuredIndicators
import dev.kinash.smartscales.ui.ChartRoute
import dev.kinash.smartscales.ui.goal.GoalProgress
import dev.kinash.smartscales.ui.goal.goalProgress
import dev.kinash.smartscales.ui.history.TagFilters
import dev.kinash.smartscales.ui.history.WEIGHT_CARD
import dev.kinash.smartscales.ui.history.activeFilter
import dev.kinash.smartscales.ui.history.matches
import dev.kinash.smartscales.ui.history.profileTags
import dev.kinash.smartscales.ui.segments.SegmentSeries
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject

/** A series as drawn: its points in the period, their moving average, and the axis its unit goes on. */
data class ShownSeries(
    val series: ChartSeries,
    val points: List<ChartPoint>,
    /** Null when smoothing is off. */
    val average: List<ChartPoint>?,
    /** The second axis, on the right, for a unit other than the first series' one. */
    val endAxis: Boolean,
)

/** Something that can join the chart, and whether its unit fits on one of the two axes. */
data class ChartChoice(val id: String, val title: LocalizedText, val fits: Boolean)

sealed interface ChartState {
    data object Loading : ChartState

    /** The profile or the indicator is gone. */
    data object Gone : ChartState

    data class Ready(
        val profile: Profile,
        val period: ChartPeriod,
        val smoothing: Boolean,
        /** The start of the period, null for all the history; the period ends now. */
        val since: Instant?,
        val now: Instant,
        /** Weighings in the period, with a value of the first series or not. */
        val weighings: Int,
        /** The first is the indicator the chart was opened for; the others were added to compare. */
        val shown: List<ShownSeries>,
        /** The first series' zones of the norm. */
        val zones: List<ZoneBand>,
        val choices: List<ChartChoice>,
        /** The profile's goal for the first series, in its own unit (kilograms, centimetres); null without one. */
        val goal: Goal? = null,
        /** How far the goal has come; null without a goal or without values. */
        val progress: GoalProgress? = null,
        /** The first series' latest value in its own unit, which a new goal cannot be. */
        val latest: Double? = null,
        /** Goals are for adults: a child's weight is read by its percentile (requirements §6.3). */
        val goalsOffered: Boolean = true,
        /** The profile's tags and those on, as in the history: the series are of the weighings with all of them. */
        val tags: List<String> = emptyList(),
        val tagFilter: Set<String> = emptySet(),
        /** The filter leaves no weighing in the whole history, not only in the period. */
        val noneTagged: Boolean = false,
    ) : ChartState
}

@HiltViewModel
class ChartViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val profiles: ProfileRepository,
    private val indicators: Indicators,
    private val tagFilters: TagFilters,
    private val clock: Clock,
) : ViewModel() {
    private val route = savedStateHandle.toRoute<ChartRoute>()

    private data class Options(val period: ChartPeriod?, val smoothing: Boolean)

    /**
     * The [history] and the weighings with the filter's tags, [filtered]; the series of the chart come from those,
     * while the zones, the goal and what can be added are of the whole history.
     */
    private data class Series(
        val history: List<MeasuredIndicators>,
        val filtered: List<MeasuredIndicators>,
        val all: List<ChartSeries>?,
        val choices: List<ChartChoice>,
        val tags: List<String>,
        val filter: Set<String>,
    )

    /** No period until the person picks one: it is then chosen by what the history has, see [defaultPeriod]. */
    private val options = MutableStateFlow(Options(null, smoothing = true))

    /** The series on the chart; the first is the one it was opened for. */
    private val ids = MutableStateFlow(listOf(seriesId(route.indicator)))

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<ChartState> = profiles.observeProfiles()
        .map { all -> all.firstOrNull { it.id == route.profileId } }
        .flatMapLatest { profile ->
            if (profile == null) return@flatMapLatest flowOf(ChartState.Gone)
            val history = indicators.observeHistory(profile.id)
            // The whole history's series and what can be added change with the history, the series chosen and the
            // tags, not with the period or smoothing
            val series = combine(history, ids, tagFilters.of(profile.id)) { history, ids, filter ->
                val model = indicators.model
                val tags = profileTags(history.map { it.measurement })
                val active = activeFilter(filter, tags)
                val shown = history.filter { matches(it.measurement, active) }
                val all = ids.mapNotNull { chartSeries(model, shown, it, profile) }
                val child = history.firstOrNull()?.growth != null
                val units = all.map { it.chartUnit(profile) }.distinct()
                val choices = (if (child) CHILD_CHOICES else chartChoices(model))
                    .filter { it !in ids }
                    .mapNotNull { id -> chartSeries(model, history, id, profile) }
                    .filter { it.points.isNotEmpty() }
                    .map { ChartChoice(it.id, it.title, it.chartUnit(profile) in units || units.size < MAX_AXES) }
                Series(history, shown, all.takeIf { it.firstOrNull()?.id == ids.first() }, choices, tags, active)
            }.flowOn(Dispatchers.Default)
            val goal = profiles.observeGoals(profile.id).map { it[seriesId(route.indicator)] }
            combine(series, options, goal) { series, options, goal ->
                val (history, filtered, all, choices) = series
                val model = indicators.model
                val now = clock.instant()
                val main = all?.first() ?: return@combine ChartState.Gone
                val period = options.period ?: defaultPeriod(main.points, now)
                val since = period.days?.let { now - Duration.ofDays(it) }
                val units = all.map { it.chartUnit(profile) }.distinct()
                val values = seriesPoints(model, history, main.id)
                val child = history.firstOrNull()?.growth != null
                ChartState.Ready(
                    profile, period, options.smoothing, since, now,
                    weighings = filtered.count { since == null || it.measurement.takenAt >= since },
                    shown = all.map { series ->
                        ShownSeries(
                            series,
                            series.points.since(since),
                            if (options.smoothing) movingAverage(series.points, period.averageDays).since(since) else null,
                            endAxis = series.chartUnit(profile) != units.first(),
                        )
                    },
                    zones = zoneBands(model, history.firstOrNull(), main.id, profile),
                    choices = choices,
                    goal = goal?.takeIf { !child },
                    goalsOffered = !child,
                    progress = goal?.takeIf { !child }?.let { goalProgress(it, values, now) },
                    latest = values.lastOrNull()?.value,
                    tags = series.tags,
                    tagFilter = series.filter,
                    noneTagged = filtered.isEmpty(),
                )
            }.flowOn(Dispatchers.Default)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChartState.Loading)

    /** The same filter as the history's: one per profile, until it is turned off or the app restarts. */
    fun toggleTag(tag: String) {
        tagFilters.toggle(route.profileId, tag)
    }

    fun selectPeriod(period: ChartPeriod) = options.update { it.copy(period = period) }

    fun setSmoothing(on: Boolean) = options.update { it.copy(smoothing = on) }

    /** Sets the goal of the series the chart was opened for, in its own unit; null removes it. */
    fun setGoal(target: Double?) {
        viewModelScope.launch { profiles.setGoal(route.profileId, seriesId(route.indicator), target) }
    }

    fun add(id: String) = ids.update { if (id in it) it else it + id }

    /** Removes a series added to compare; the one the chart was opened for stays. */
    fun remove(id: String) = ids.update { listOf(it.first()) + (it.drop(1) - id) }

    companion object {
        /** Units on one chart: the left axis and the right one. */
        const val MAX_AXES = 2

        /** A child's cards, BMI, weight and height (requirements §3), and the segments of an 8-electrode scale. */
        val CHILD_CHOICES = listOf(WEIGHT_CARD, HEIGHT_SERIES, "bmi") + SegmentSeries.all.map { it.id }

        /**
         * The shortest period with at least two weighings, so the chart opens with a line; all the history when
         * even a year has fewer.
         */
        fun defaultPeriod(points: List<ChartPoint>, now: Instant): ChartPeriod =
            listOf(ChartPeriod.MONTH, ChartPeriod.YEAR).firstOrNull { period ->
                points.count { it.takenAt >= now - Duration.ofDays(period.days!!) } >= 2
            } ?: ChartPeriod.ALL
    }
}
