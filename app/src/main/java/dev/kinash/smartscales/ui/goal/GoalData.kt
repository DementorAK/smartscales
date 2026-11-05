package dev.kinash.smartscales.ui.goal

import dev.kinash.smartscales.core.data.Goal
import dev.kinash.smartscales.ui.chart.ChartPoint
import java.time.Duration
import java.time.Instant
import kotlin.math.roundToLong

/** When the goal will be reached at the current trend, or why that cannot be said. */
sealed interface Forecast {
    data class On(val date: Instant) : Forecast

    /** The trend goes away from the goal, or stays flat. */
    data object NotApproaching : Forecast

    /** At this pace, later than [FORECAST_LIMIT_DAYS] from now: a date so far off would claim too much. */
    data object TooFar : Forecast

    /**
     * Fewer than [TREND_MIN_POINTS] weighings over less than [TREND_MIN_DAYS] days in the last [TREND_DAYS] up to
     * now: a trend of weighings months ago is not the current one.
     */
    data object TooFewWeighings : Forecast
}

/**
 * How far a goal has come, in the indicator's own unit. [start] is the value when the goal was set: the last
 * weighing before it, or the first after it when there was none. [current] is the latest value.
 */
data class GoalProgress(
    val target: Double,
    val start: Double,
    val current: Double,
    val achieved: Boolean,
    /** From 0 at the start to 1 at the target; null when the goal was set at its own value. */
    val fraction: Double?,
    /** Null once the goal is achieved. */
    val forecast: Forecast?,
) {
    /** What is left to go, with its sign: negative to lose. */
    val remaining: Double get() = target - current
}

/**
 * The progress of [goal] over [points], the indicator's values oldest first in its own unit; null without any
 * value. The goal is reached once the latest value is at the target or past it, in the direction from the
 * start: a goal to lose is reached at the target or below.
 */
fun goalProgress(goal: Goal, points: List<ChartPoint>, now: Instant): GoalProgress? {
    if (points.isEmpty()) return null
    val start = (points.lastOrNull { it.takenAt <= goal.setAt } ?: points.first()).value
    val current = points.last().value
    val direction = goal.target - start
    // A goal set at its own value (the dialog refuses one) has no way to go: reached only while there
    val achieved = when {
        direction > 0 -> current >= goal.target
        direction < 0 -> current <= goal.target
        else -> current == goal.target
    }
    val fraction = if (direction == 0.0) null else ((current - start) / direction).coerceIn(0.0, 1.0)
    val forecast = if (achieved) null else forecast(points, goal.target, now)
    return GoalProgress(goal.target, start, current, achieved, fraction, forecast)
}

/**
 * The date the trend line reaches [target]: a least-squares line through the weighings of the last [TREND_DAYS]
 * days up to [now], which a single heavy morning moves little, followed on from where it is at the latest weighing.
 */
fun forecast(points: List<ChartPoint>, target: Double, now: Instant): Forecast {
    val recent = points.filter { it.takenAt > now - Duration.ofDays(TREND_DAYS) }
    if (recent.size < TREND_MIN_POINTS) return Forecast.TooFewWeighings
    val last = recent.last().takenAt
    if (Duration.between(recent.first().takenAt, last) < Duration.ofDays(TREND_MIN_DAYS)) return Forecast.TooFewWeighings
    val xs = recent.map { Duration.between(last, it.takenAt).toMillis() / DAY_MILLIS }
    val ys = recent.map { it.value }
    val meanX = xs.average()
    val meanY = ys.average()
    val slope = xs.indices.sumOf { (xs[it] - meanX) * (ys[it] - meanY) } / xs.sumOf { (it - meanX) * (it - meanX) }
    // The line at the latest weighing, where x is 0
    val atLast = meanY - slope * meanX
    // A flat line never gets there, nor one going the other way
    if (slope * (target - recent.last().value) <= 0) return Forecast.NotApproaching
    // The line may be past the target already while the latest weighing is not: any day now
    val days = ((target - atLast) / slope).coerceAtLeast(0.0)
    val date = last + Duration.ofMillis((days * DAY_MILLIS).roundToLong())
    return if (date > now + Duration.ofDays(FORECAST_LIMIT_DAYS)) Forecast.TooFar else Forecast.On(maxOf(date, now))
}

/** The trend is of the last month: long enough to even out the water, short enough to follow a change of pace. */
const val TREND_DAYS = 30L
const val TREND_MIN_POINTS = 3
const val TREND_MIN_DAYS = 7L

/** Two years. */
const val FORECAST_LIMIT_DAYS = 730L

private const val DAY_MILLIS = 86_400_000.0
