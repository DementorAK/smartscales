package dev.kinash.smartscales.ui.compare

import dev.kinash.smartscales.core.data.Measurement
import dev.kinash.smartscales.core.data.Profile
import dev.kinash.smartscales.core.formula.BuiltinFormulas
import dev.kinash.smartscales.core.formula.FormulaModel
import dev.kinash.smartscales.core.formula.ZoneLevel
import dev.kinash.smartscales.core.model.MeasurementSource
import dev.kinash.smartscales.core.model.Sex
import dev.kinash.smartscales.indicators.MeasuredIndicators
import dev.kinash.smartscales.ui.history.DEFAULT_CARDS
import dev.kinash.smartscales.ui.history.WEIGHT_CARD
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class CompareDataTest {
    private val model = FormulaModel.build(listOf(BuiltinFormulas.layer))
    private val profile = Profile(
        id = 1, name = "Dmitry", sex = Sex.MALE, birthDate = LocalDate.parse("1990-11-02"), heightCm = 180.0,
        color = 0, createdAt = Instant.parse("2026-01-01T00:00:00Z"),
    )

    private fun measured(weight: Double, impedance: Double? = null): MeasuredIndicators {
        val measurement = Measurement(
            profileId = 1, takenAt = Instant.parse("2026-11-04T08:00:00Z"), zone = ZoneOffset.UTC,
            source = MeasurementSource.MANUAL, weightKg = weight, person = profile.snapshot(),
        )
        val inputs = mapOf<String, Any?>(
            "weight" to weight, "height" to 180.0, "age" to 36.0, "sex" to 1.0, "male" to true, "female" to false,
            "impedance" to impedance,
        )
        return MeasuredIndicators(measurement, model.evaluate(inputs), inputs)
    }

    @Test
    fun weightAndTheProfilesCardsComeFirstThenTheRestWithoutVariants() {
        val ids = compareIds(model, listOf("bmr", "bf_gallagher"))
        assertEquals(listOf(WEIGHT_CARD, "bmr", "bf_gallagher"), ids.take(3))
        assertEquals(ids.distinct(), ids)
        // Groups stand for their variants, except one the profile has as a card
        assertTrue("bf" in ids && "tbw" in ids)
        val variants = model.groups.getValue("tbw").toSet()
        assertTrue(ids.none { it in variants })
        assertTrue(DEFAULT_CARDS.all { it in compareIds(model, DEFAULT_CARDS) })
    }

    @Test
    fun rowsHaveBothValuesTheChangeAndTheZones() {
        val rows = compareRows(model, measured(82.0), measured(80.0), compareIds(model, DEFAULT_CARDS))
        val weight = rows.first()
        assertEquals(WEIGHT_CARD, weight.id)
        assertEquals(-2.0, weight.change!!, 1e-9)
        val bmi = rows.first { it.id == "bmi" }
        assertEquals(80.0 / 1.8 / 1.8 - 82.0 / 1.8 / 1.8, bmi.change!!, 0.1)
        assertEquals(ZoneLevel.ELEVATED, bmi.before!!.zone)
        assertEquals(ZoneLevel.NORMAL, bmi.after!!.zone)
        // Without impedance on both, muscle has no value at either and is left out
        assertTrue(rows.none { it.id == "smm_janssen" })
    }

    @Test
    fun daysAreCalendarDatesWhereEachWasTaken() {
        fun at(time: String, zone: String = "Z") = measured(80.0).measurement.copy(takenAt = Instant.parse(time), zone = ZoneOffset.of(zone))
        assertEquals(1, daysApart(at("2026-11-04T22:00:00Z"), at("2026-11-05T07:00:00Z")))
        assertEquals(7, daysApart(at("2026-11-01T08:00:00Z"), at("2026-11-08T07:00:00Z")))
        assertEquals(0, daysApart(at("2026-11-04T07:00:00Z"), at("2026-11-04T22:00:00Z")))
        // 23:30 UTC is already the 5th in Moscow
        assertEquals(0, daysApart(at("2026-11-04T23:30:00Z", "+03:00"), at("2026-11-05T09:00:00Z", "+03:00")))
    }

    @Test
    fun aGroupByDifferentMethodsSaysSo() {
        val rows = compareRows(model, measured(82.0), measured(80.0, impedance = 500.0), compareIds(model, DEFAULT_CARDS))
        val bf = rows.first { it.id == "bf" }
        assertTrue(bf.methodsDiffer)
        // So do the indicators that follow a group: water in percent, fat mass
        assertTrue(rows.first { it.id == "tbw_percent" }.methodsDiffer)
        assertTrue(rows.first { it.id == "fat_mass" }.methodsDiffer)
        // Muscle only at the later weighing: no change, but the row stays
        val muscle = rows.first { it.id == "smm_janssen" }
        assertNull(muscle.before)
        assertNull(muscle.change)
        assertFalse(rows.first { it.id == "bmi" }.methodsDiffer)
    }
}
