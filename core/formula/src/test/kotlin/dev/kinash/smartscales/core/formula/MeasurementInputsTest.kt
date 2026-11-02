package dev.kinash.smartscales.core.formula

import dev.kinash.smartscales.core.model.Impedance
import dev.kinash.smartscales.core.model.MeasurementSource
import dev.kinash.smartscales.core.model.PersonSnapshot
import dev.kinash.smartscales.core.model.RawMeasurement
import dev.kinash.smartscales.core.model.Sex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class MeasurementInputsTest {
    private val raw = RawMeasurement(
        takenAt = Instant.parse("2026-11-02T08:00:00Z"),
        zone = ZoneOffset.UTC,
        source = MeasurementSource.SCALE,
        weightKg = 80.0,
        person = PersonSnapshot(180.0, Sex.MALE, LocalDate.parse("1991-05-02")),
        impedances = listOf(Impedance(Impedance.FOOT_TO_FOOT, 450.0)),
        heartRateBpm = 64,
        manualValues = mapOf("waist" to 90.0, "hip" to 100.0, "neck" to 40.0, "wrist" to 18.0),
    )

    @Test
    fun standardInputs() {
        val inputs = MeasurementInputs.of(raw)
        assertEquals(80.0, inputs["weight"])
        assertEquals(180.0, inputs["height"])
        // Born 2 May 1991, measured 2 November 2026: 35 and a half years
        assertEquals(35.5, inputs["age"] as Double, 0.001)
        assertEquals(1.0, inputs["sex"])
        assertEquals(true, inputs["male"])
        assertEquals(false, inputs["female"])
        assertEquals(450.0, inputs["impedance"])
        assertEquals(64.0, inputs["heart_rate"])
        assertEquals(90.0, inputs["waist"])
        assertEquals(100.0, inputs["hips"])
        assertEquals(40.0, inputs["neck"])
        // Inputs declared by formula sets keep their ids
        assertEquals(18.0, inputs["wrist"])
    }

    @Test
    fun impedanceUsesTheScaleCalibrationAndSkipsPlaceholders() {
        assertEquals(495.0, MeasurementInputs.of(raw, impedanceFactor = 1.1)["impedance"] as Double, 1e-9)
        assertNull(MeasurementInputs.of(raw, placeholder = true)["impedance"])
        assertNull(MeasurementInputs.of(raw.copy(impedances = emptyList()))["impedance"])
        // A segment channel is not foot-to-foot impedance
        assertNull(MeasurementInputs.of(raw.copy(impedances = listOf(Impedance(1, 300.0))))["impedance"])
    }

    @Test
    fun birthDateAfterTheMeasurementGivesNoAge() {
        val unborn = raw.copy(person = raw.person.copy(birthDate = LocalDate.parse("2027-01-01")))
        val inputs = MeasurementInputs.of(unborn)
        assertNull(inputs["age"])
        // Indicators that need age have no value instead of failing
        val results = FormulaModel.build(listOf(BuiltinFormulas.layer)).evaluate(inputs)
        assertNull(results.group("bf"))
        assertEquals(80.0 / 1.8 / 1.8, results.value("bmi")!!, 1e-9)
    }

    @Test
    fun builtInSetOnTheseInputs() {
        val model = FormulaModel.build(listOf(BuiltinFormulas.layer))
        val results = model.evaluate(MeasurementInputs.of(raw))
        assertEquals("bf_bia", results.group("bf")!!.first)
        assertEquals("bf_navy", model.evaluate(MeasurementInputs.of(raw), primary = mapOf("bf" to "bf_navy")).group("bf")!!.first)
        val woman = raw.copy(person = PersonSnapshot(165.0, Sex.FEMALE, LocalDate.parse("1991-05-02")))
        assertEquals(true, MeasurementInputs.of(woman)["female"])
    }
}
