package dev.kinash.smartscales.ui.goal

import dev.kinash.smartscales.core.data.Goal
import dev.kinash.smartscales.core.formula.IndicatorUnit
import dev.kinash.smartscales.ui.chart.ChartPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class GoalDataTest {
    private val now = Instant.parse("2026-11-05T08:00:00Z")
    private fun daysAgo(days: Long) = now - Duration.ofDays(days)
    private fun goal(target: Double, setDaysAgo: Long) = Goal("weight", target, daysAgo(setDaysAgo))

    /** Down by 0.1 kg a day over the last [days] days, to [last] today. */
    private fun losing(last: Double, days: Long) = (days downTo 0).map { ChartPoint(daysAgo(it), last + 0.1 * it) }

    @Test
    fun progressCountsFromTheLastWeighingBeforeTheGoal() {
        val points = listOf(ChartPoint(daysAgo(20), 90.0), ChartPoint(daysAgo(10), 85.0), ChartPoint(daysAgo(1), 82.0))
        val progress = goalProgress(goal(80.0, setDaysAgo = 15), points, now)!!
        assertEquals(90.0, progress.start, 0.0)
        assertEquals(0.8, progress.fraction!!, 1e-9)
        assertEquals(-2.0, progress.remaining, 1e-9)
        assertFalse(progress.achieved)
        // Set before the first weighing: from the first one
        assertEquals(90.0, goalProgress(goal(80.0, setDaysAgo = 100), points, now)!!.start, 0.0)
        assertNull(goalProgress(goal(80.0, 1), emptyList(), now))
    }

    @Test
    fun aGoalIsReachedAtTheTargetOrPastItInItsDirection() {
        val down = listOf(ChartPoint(daysAgo(10), 85.0), ChartPoint(daysAgo(1), 79.5))
        goalProgress(goal(80.0, 20), down, now)!!.let {
            assertTrue(it.achieved)
            assertEquals(1.0, it.fraction!!, 0.0)
            assertNull(it.forecast)
        }
        // A goal to gain: below the target is not there yet
        val up = listOf(ChartPoint(daysAgo(10), 60.0), ChartPoint(daysAgo(1), 63.0))
        assertFalse(goalProgress(goal(65.0, 20), up, now)!!.achieved)
        assertTrue(goalProgress(goal(63.0, 20), up, now)!!.achieved)
        // Going the wrong way is no progress, not a negative one
        assertEquals(0.0, goalProgress(goal(80.0, 20), listOf(ChartPoint(daysAgo(10), 85.0), ChartPoint(daysAgo(1), 86.0)), now)!!.fraction!!, 0.0)
    }

    @Test
    fun forecastFollowsTheLastMonthsTrend() {
        // 0.1 kg a day, 82 today: 80 in 20 days
        val forecast = forecast(losing(82.0, 14), 80.0, now) as Forecast.On
        assertEquals(20.0, Duration.between(now, forecast.date).toHours() / 24.0, 0.01)
        // Gaining while the goal is to lose
        assertEquals(Forecast.NotApproaching, forecast(losing(82.0, 14).map { it.copy(value = 164 - it.value) }, 80.0, now))
        assertEquals(Forecast.NotApproaching, forecast(losing(82.0, 14).map { it.copy(value = 82.0) }, 80.0, now))
        // 0.1 kg a day to lose 100 kg
        assertEquals(Forecast.TooFar, forecast(losing(182.0, 14), 82.0, now))
    }

    @Test
    fun forecastNeedsThreeWeighingsOverAWeek() {
        assertEquals(Forecast.TooFewWeighings, forecast(losing(82.0, 1), 80.0, now))
        assertEquals(Forecast.TooFewWeighings, forecast(losing(82.0, 5), 80.0, now))
        // Weighings before the month do not count
        val old = listOf(ChartPoint(daysAgo(60), 90.0), ChartPoint(daysAgo(50), 89.0)) + losing(82.0, 1)
        assertEquals(Forecast.TooFewWeighings, forecast(old, 80.0, now))
        // Losing in August and no weighing since: not a trend of today
        val august = losing(82.0, 14).map { it.copy(takenAt = it.takenAt - Duration.ofDays(90)) }
        assertEquals(Forecast.TooFewWeighings, forecast(august, 80.0, now))
        assertEquals(Forecast.TooFewWeighings, goalProgress(goal(80.0, 120), august, now)!!.forecast)
    }

    @Test
    fun theTrendPastTheTargetIsAnyDayNow() {
        // The line is below 80 at the last weighing, which is still above it
        val points = losing(79.0, 14).dropLast(1) + ChartPoint(now, 80.3)
        assertEquals(Forecast.On(now), forecast(points, 80.0, now))
    }

    @Test
    fun targetsAreAboveZeroAndPercentagesBelowAHundred() {
        assertTrue(isPlausible(18.0, IndicatorUnit.PERCENT))
        assertFalse(isPlausible(100.0, IndicatorUnit.PERCENT))
        assertFalse(isPlausible(0.0, IndicatorUnit.KG))
        assertTrue(isPlausible(-1.0, IndicatorUnit.NONE))
    }
}
