package dev.kinash.smartscales.ui.segments

import dev.kinash.smartscales.core.data.Measurement
import dev.kinash.smartscales.core.data.Profile
import dev.kinash.smartscales.core.formula.BuiltinFormulas
import dev.kinash.smartscales.core.formula.FormulaModel
import dev.kinash.smartscales.core.formula.IndicatorUnit
import dev.kinash.smartscales.core.formula.MeasurementInputs
import dev.kinash.smartscales.core.model.Impedance
import dev.kinash.smartscales.core.model.MeasurementSource
import dev.kinash.smartscales.core.model.Segment
import dev.kinash.smartscales.core.model.Sex
import dev.kinash.smartscales.indicators.MeasuredIndicators
import dev.kinash.smartscales.ui.chart.chartChoices
import dev.kinash.smartscales.ui.chart.chartSeries
import dev.kinash.smartscales.ui.compare.compareIds
import dev.kinash.smartscales.ui.compare.compareRows
import dev.kinash.smartscales.ui.history.DEFAULT_CARDS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class SegmentDataTest {
    private val now = Instant.parse("2026-11-05T08:00:00Z")
    private val model = FormulaModel.build(listOf(BuiltinFormulas.layer))
    private val profile = Profile(
        id = 1, name = "Dmitry", sex = Sex.MALE, birthDate = LocalDate.parse("1990-11-02"), heightCm = 180.0,
        color = 0, createdAt = now,
    )

    /** A CB weighing: each segment at 300 Ω plus its channel times ten, plus [offset], and 1 Ω more at frequency 2. */
    private fun measured(daysAgo: Long, offset: Double = 0.0, segments: Boolean = true): MeasuredIndicators {
        val impedances = if (!segments) {
            listOf(Impedance(Impedance.FOOT_TO_FOOT, 500.0))
        } else {
            (0..1).flatMap { f -> (1..5).map { Impedance(it, 300.0 + 10 * it + f + offset, frequencyIndex = f) } }
        }
        val measurement = Measurement(
            profileId = 1, takenAt = now - Duration.ofDays(daysAgo), zone = ZoneOffset.UTC, source = MeasurementSource.SCALE,
            weightKg = 80.0, person = profile.snapshot(), impedances = impedances,
        )
        val inputs = MeasurementInputs.of(measurement.toRawMeasurement()!!)
        return MeasuredIndicators(measurement, model.evaluate(inputs), inputs)
    }

    @Test
    fun seriesAreTheFormulaInputsOfEachSegmentAndFrequency() {
        val rightArm = SegmentSeries(Segment.RIGHT_ARM, 0)
        assertEquals("impedance_right_arm", rightArm.id)
        assertEquals("impedance_trunk_2", SegmentSeries(Segment.TRUNK, 1).id)
        assertEquals(rightArm, SegmentSeries.of("impedance_right_arm"))
        assertNull(SegmentSeries.of("bmi"))
        assertEquals(310.0, rightArm.valueOf(measured(0))!!, 1e-9)
        assertEquals(listOf(0, 1), segmentFrequencies(measured(0)))
        assertFalse(hasSegments(measured(0, segments = false)))
    }

    @Test
    fun segmentsGoOnChartsInOhmsAndIntoComparisons() {
        val history = listOf(measured(0, offset = -5.0), measured(7))
        val series = chartSeries(model, history, "impedance_left_leg_2", profile)!!
        assertEquals(IndicatorUnit.OHM, series.unit)
        assertEquals(listOf(341.0, 336.0), series.points.map { it.value })
        assertTrue("impedance_trunk" in chartChoices(model))

        val rows = compareRows(model, history[1], history[0], compareIds(model, DEFAULT_CARDS))
        assertEquals(-5.0, rows.first { it.id == "impedance_right_arm" }.change!!, 1e-9)
        // A 4-electrode weighing has no segment rows
        assertTrue(compareRows(model, measured(7, segments = false), measured(0, segments = false), compareIds(model, DEFAULT_CARDS)).none { SegmentSeries.of(it.id) != null })
    }
}
