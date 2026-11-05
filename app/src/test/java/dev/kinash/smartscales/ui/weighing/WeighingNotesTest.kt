package dev.kinash.smartscales.ui.weighing

import dev.kinash.smartscales.core.data.Goal
import dev.kinash.smartscales.core.data.Measurement
import dev.kinash.smartscales.core.data.Profile
import dev.kinash.smartscales.core.formula.BuiltinFormulas
import dev.kinash.smartscales.core.formula.FormulaModel
import dev.kinash.smartscales.core.model.MeasurementSource
import dev.kinash.smartscales.core.model.Sex
import dev.kinash.smartscales.indicators.MeasuredIndicators
import dev.kinash.smartscales.ui.history.WEIGHT_CARD
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class WeighingNotesTest {
    private val now = Instant.parse("2026-11-05T08:00:00Z")
    private val model = FormulaModel.build(listOf(BuiltinFormulas.layer))
    private val profile = Profile(
        id = 1, name = "Dmitry", sex = Sex.MALE, birthDate = LocalDate.parse("1990-11-02"), heightCm = 180.0,
        color = 0, createdAt = now,
    )

    /** Newest first, as the history comes; ids count from 1 for the oldest. */
    private fun history(vararg weighings: Pair<Long, Double>): List<MeasuredIndicators> =
        weighings.mapIndexed { i, (daysAgo, kg) ->
            val measurement = Measurement(
                id = i + 1L, profileId = 1, takenAt = now - Duration.ofDays(daysAgo), zone = ZoneOffset.UTC,
                source = MeasurementSource.MANUAL, weightKg = kg, person = profile.snapshot(),
            )
            val inputs = mapOf<String, Any?>("weight" to kg, "height" to 180.0, "age" to 36.0, "sex" to 1.0, "male" to true, "female" to false)
            MeasuredIndicators(measurement, model.evaluate(inputs), inputs)
        }.reversed()

    private fun weightGoal(target: Double, setDaysAgo: Long) = mapOf(WEIGHT_CARD to Goal(WEIGHT_CARD, target, now - Duration.ofDays(setDaysAgo)))

    @Test
    fun aGoalIsSaidByTheWeighingThatReachedItOnly() {
        val reaching = history(10L to 82.0, 5L to 80.6, 0L to 79.9)
        val notes = weighingNotes(model, reaching, 3, weightGoal(80.0, 20), 3.0)!!
        assertEquals(listOf(WEIGHT_CARD), notes.reached.map { it.id })
        assertEquals(-0.7, notes.change!!, 1e-9)
        // The next weighing at the goal says nothing
        val after = history(10L to 82.0, 5L to 79.9, 0L to 79.7)
        assertTrue(weighingNotes(model, after, 3, weightGoal(80.0, 20), 3.0)!!.reached.isEmpty())
        // A goal of another indicator in its own unit: BMI from 24.9 to 24.7
        val bmi = mapOf("bmi" to Goal("bmi", 24.7, now - Duration.ofDays(20)))
        assertEquals(listOf("bmi"), weighingNotes(model, reaching, 3, bmi, 3.0)!!.reached.map { it.id })
    }

    @Test
    fun aWeightFarFromTheFortnightsMedianIsSaid() {
        val history = history(30L to 90.0, 10L to 76.0, 8L to 76.4, 1L to 79.5, 0L to 80.3)
        val notes = weighingNotes(model, history, 5, emptyMap(), 3.0)!!
        // 76.0, 76.4 and 79.5: the median is 76.4; the weighing a month ago is not usual any more
        assertEquals(3.9, notes.deviation!!, 1e-9)
        assertNull(weighingNotes(model, history, 5, emptyMap(), 4.0)!!.deviation)
        // Nothing in the fortnight before: nothing to compare with
        assertNull(weighingNotes(model, history(30L to 90.0, 0L to 80.0), 2, emptyMap(), 3.0)!!.deviation)
        assertNull(weighingNotes(model, history(0L to 80.0), 1, emptyMap(), 3.0)!!.change)
    }

    @Test
    fun aChangeSinceLongAgoHasItsDate() {
        // The weighing before was 30 days ago, on the 6th of October where it was taken
        val notes = weighingNotes(model, history(30L to 84.0, 0L to 80.0), 2, emptyMap(), 3.0)!!
        assertEquals(-4.0, notes.change!!, 1e-9)
        assertEquals(LocalDate.parse("2026-10-06"), notes.since)
        assertNull(weighingNotes(model, history(3L to 81.0, 0L to 80.0), 2, emptyMap(), 3.0)!!.since)
    }

    @Test
    fun usualWeightIsTheMedian() {
        assertEquals(77.0, usualWeight(listOf(now - Duration.ofDays(1) to 76.0, now - Duration.ofDays(2) to 78.0), now)!!, 1e-9)
        assertNull(usualWeight(listOf(now to 76.0), now))
    }
}
