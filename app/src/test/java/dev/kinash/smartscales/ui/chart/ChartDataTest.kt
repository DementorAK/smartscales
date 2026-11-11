package dev.kinash.smartscales.ui.chart

import dev.kinash.smartscales.core.data.Profile
import dev.kinash.smartscales.core.formula.BuiltinFormulas
import dev.kinash.smartscales.core.formula.FormulaModel
import dev.kinash.smartscales.core.formula.IndicatorUnit
import dev.kinash.smartscales.core.formula.ZoneLevel
import dev.kinash.smartscales.core.model.Sex
import dev.kinash.smartscales.core.model.WeightUnit
import dev.kinash.smartscales.indicators.MeasuredIndicators
import dev.kinash.smartscales.core.data.Measurement
import dev.kinash.smartscales.core.model.MeasurementSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class ChartDataTest {
    private val now = Instant.parse("2026-11-04T08:00:00Z")
    private fun daysAgo(days: Long) = now - Duration.ofDays(days)

    @Test
    fun movingAverageTakesTheDaysUpToEachWeighingIncludingThoseBeforeThePeriod() {
        val points = listOf(
            ChartPoint(daysAgo(20), 82.0),
            ChartPoint(daysAgo(8), 80.0),
            ChartPoint(daysAgo(3), 79.0),
            ChartPoint(daysAgo(1), 78.0),
        )
        val average = movingAverage(points, 7)
        assertEquals(listOf(82.0, 80.0, 79.5, 78.5), average.map { it.value })
        // A weighing exactly 7 days before is out of the window
        assertEquals(80.0, movingAverage(listOf(ChartPoint(daysAgo(7), 70.0), ChartPoint(now, 80.0)), 7).last().value, 0.0)
        // Over a month, the first point counts for the ones after it
        assertEquals((82.0 + 80 + 79 + 78) / 4, movingAverage(points, 30).last().value, 1e-9)
    }

    @Test
    fun axisDoesNotStartAtZero() {
        val range = axisRange(listOf(80.0, 82.0))
        assertEquals(79.7, range.start, 1e-9)
        assertEquals(82.3, range.endInclusive, 1e-9)
        // One value still gets a span
        assertTrue(axisRange(listOf(80.0)).let { it.start < 80 && it.endInclusive > 80 })
    }

    @Test
    fun theShortestPeriodWithTwoWeighingsOpensFirst() {
        val recent = listOf(ChartPoint(daysAgo(40), 80.0), ChartPoint(daysAgo(3), 80.0), ChartPoint(daysAgo(1), 80.0))
        assertEquals(ChartPeriod.MONTH, ChartViewModel.defaultPeriod(recent, now))
        assertEquals(ChartPeriod.YEAR, ChartViewModel.defaultPeriod(recent.take(2), now))
        assertEquals(ChartPeriod.ALL, ChartViewModel.defaultPeriod(listOf(ChartPoint(daysAgo(400), 80.0), ChartPoint(daysAgo(1), 80.0)), now))
    }

    @Test
    fun ticksAreRoundValuesWithinTheRange() {
        assertEquals(listOf(80.0, 81.0, 82.0), niceTicks(79.7..82.3))
        assertEquals(listOf(20.0, 22.5, 25.0), niceTicks(19.0..26.0, count = 3))
        assertEquals(listOf(0.3), niceTicks(0.25..0.34, count = 1))
    }

    private val model = FormulaModel.build(listOf(BuiltinFormulas.layer))
    private val profile = Profile(
        id = 1, name = "Dmitry", sex = Sex.MALE, birthDate = LocalDate.parse("1990-11-02"), heightCm = 180.0,
        color = 0, createdAt = now,
    )

    private fun measured(weight: Double, at: Instant, impedance: Double? = null): MeasuredIndicators {
        val measurement = Measurement(
            profileId = 1, takenAt = at, zone = ZoneOffset.UTC, source = MeasurementSource.MANUAL,
            weightKg = weight, person = profile.snapshot(),
        )
        val inputs = mapOf<String, Any?>(
            "weight" to weight, "height" to 180.0, "age" to 35.0, "sex" to 1.0, "male" to true, "female" to false,
        ) + listOfNotNull(impedance?.let { "impedance" to it })
        return MeasuredIndicators(measurement, model.evaluate(inputs), inputs)
    }

    @Test
    fun seriesComesOldestFirstInTheProfilesUnits() {
        val history = listOf(measured(81.0, daysAgo(1)), measured(80.0, daysAgo(2)))
        val weight = chartSeries(model, history, "weight", profile.copy(weightUnit = WeightUnit.POUND))!!
        assertEquals(listOf(80.0, 81.0).map { WeightUnit.POUND.fromKilograms(it) }, weight.points.map { it.value })
        // A group by the variant that gave the value: without impedance, body fat by BMI
        val bf = chartSeries(model, history, "bf", profile)!!
        assertEquals(history.reversed().map { it.results!!.group("bf")!!.second }, bf.points.map { it.value })
        assertEquals(null, chartSeries(model, history, "gone", profile))
    }

    @Test
    fun aGroupIsComparedWithItsOtherVariants() {
        val history = listOf(measured(81.0, daysAgo(1)))
        // Without impedance the group goes by BMI, so that variant is its own line already
        val bf = chartSeries(model, history, "bf", profile)!!
        assertEquals("bf_gallagher", bf.variant)
        assertEquals(listOf("Body fat (Gallagher 2000, from BMI)"), bf.variantNames.map { it.getValue("en") })
        // The scale's own value too; the chart leaves out those with no value in the history
        assertEquals(listOf("bf_bia", "bf_deurenberg", "bf_navy", "bf_scale"), otherVariants(model, bf))
        // A variant is compared with the rest of its group
        val navy = chartSeries(model, history, "bf_navy", profile)!!
        assertEquals(null, navy.variant)
        assertEquals(listOf("bf_bia", "bf_gallagher", "bf_deurenberg", "bf_scale"), otherVariants(model, navy))
        assertEquals(emptyList<String>(), otherVariants(model, chartSeries(model, history, "bmi", profile)!!))

        // Impedance in the newest weighing only: the line mixes two formulas, so none of them is left out
        val mixed = chartSeries(model, listOf(measured(81.0, daysAgo(1), impedance = 500.0)) + history, "bf", profile)!!
        assertEquals(listOf("bf_bia", "bf_gallagher"), mixed.variants)
        assertEquals(null, mixed.variant)
        assertEquals(listOf("bf_bia", "bf_gallagher", "bf_deurenberg", "bf_navy", "bf_scale"), otherVariants(model, mixed))
    }

    @Test
    fun anAxisIsStretchedAroundItsMiddleAndMovedBySpans() {
        val range = 80.0..82.0
        assertEquals(80.5..81.5, range.viewed(AxisView(zoom = 2.0)))
        // Up by half a span: the values half a span higher, so the line goes down
        assertEquals(81.0..83.0, range.viewed(AxisView(shift = 0.5)))
        assertEquals(range, range.viewed(AxisView()))
        assertTrue(AxisViews().isDefault)
    }

    @Test
    fun sameUnitFarApartGoesOnTheRightAxis() {
        fun series(id: String, unit: IndicatorUnit, vararg values: Double) =
            ChartSeries(id, emptyMap(), unit, 1, values.mapIndexed { i, v -> ChartPoint(daysAgo(i.toLong()), v) })
        val fatMass = series("fat_mass", IndicatorUnit.KG, 16.0, 17.0)
        val leanMass = series("fat_free_mass", IndicatorUnit.KG, 63.0, 64.0)
        val weight = series("weight", IndicatorUnit.KG, 16.5, 80.0)
        val bmr = series("bmr", IndicatorUnit.KCAL, 1700.0)
        assertEquals(listOf(false, true), endAxes(listOf(fatMass, leanMass), profile))
        // Values that meet share the axis
        assertEquals(listOf(false, false), endAxes(listOf(fatMass, weight), profile))
        // Another unit has the right axis, so the same unit stays left however far
        assertEquals(listOf(false, false, true), endAxes(listOf(fatMass, leanMass, bmr), profile))
    }

    @Test
    fun zonesAreTheNewestWeighingsBands() {
        val bands = zoneBands(model, measured(80.0, now), "bmi", profile)
        assertEquals(ZoneBand(null, 18.5, ZoneLevel.LOW), bands.first())
        assertEquals(ZoneLevel.NORMAL, bands[1].level)
        assertEquals(null, bands.last().to)
        assertEquals(bands.dropLast(1).map { it.to }, bands.drop(1).map { it.from })
    }
}
