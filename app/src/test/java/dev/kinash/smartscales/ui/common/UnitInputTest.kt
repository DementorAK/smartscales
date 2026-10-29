package dev.kinash.smartscales.ui.common

import dev.kinash.smartscales.core.model.LengthUnit
import dev.kinash.smartscales.core.model.WeightUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class UnitInputTest {

    private val ru = Locale.forLanguageTag("ru")

    @Test
    fun parsesDotAndComma() {
        assertEquals(80.5, parseNumber("80.5")!!, 0.0)
        assertEquals(80.5, parseNumber(" 80,5 ")!!, 0.0)
        assertNull(parseNumber("80.5.1"))
        assertNull(parseNumber("NaN"))
    }

    @Test
    fun kilogramsAndPounds() {
        assertEquals(80.5, WeightInput("80,5").toKilograms(WeightUnit.KILOGRAM)!!, 1e-9)
        assertEquals(100.0, WeightInput("220.462262").toKilograms(WeightUnit.POUND)!!, 1e-6)
        assertNull(WeightInput("0").toKilograms(WeightUnit.KILOGRAM))
        assertNull(WeightInput("").toKilograms(WeightUnit.KILOGRAM))
    }

    @Test
    fun stonesTakeTwoFields() {
        // 11 st 4.73 lb = 72 kg
        assertEquals(72.0, WeightInput("11", "4.73").toKilograms(WeightUnit.STONE)!!, 0.01)
        assertEquals(69.85, WeightInput("11", "").toKilograms(WeightUnit.STONE)!!, 0.01)
        assertNull(WeightInput("11.5", "1").toKilograms(WeightUnit.STONE))
        assertNull(WeightInput("11", "14").toKilograms(WeightUnit.STONE))
    }

    @Test
    fun prefillUsesLocaleSeparator() {
        assertEquals(WeightInput("80,25"), WeightInput.of(80.25, WeightUnit.KILOGRAM, ru))
        assertEquals(WeightInput("80"), WeightInput.of(80.0, WeightUnit.KILOGRAM, Locale.US))
        assertEquals(WeightInput("11", "4.7"), WeightInput.of(72.0, WeightUnit.STONE, Locale.US))
        assertEquals(WeightInput(), WeightInput.of(null, WeightUnit.POUND))
    }

    @Test
    fun prefillRoundTripsInEveryUnit() {
        for (unit in WeightUnit.entries) {
            val kg = WeightInput.of(81.3, unit, Locale.US).toKilograms(unit)!!
            assertEquals("$unit", 81.3, kg, 0.03)
        }
    }

    @Test
    fun prefilledValueIsKeptUntilTextIsEdited() {
        val text = lengthInput(180.0, LengthUnit.INCH, Locale.US)
        val prefill = Prefill(text, 180.0)
        val parse = { s: String -> parseLength(s, LengthUnit.INCH) }

        assertEquals(180.0, prefill.resolve(text, parse)!!, 0.0)
        assertEquals(177.8, prefill.resolve("70", parse)!!, 1e-9)
        assertNull(prefill.resolve("", parse))
        assertNull(null.resolve("", parse))
    }

    @Test
    fun lengths() {
        assertEquals(180.0, parseLength("180", LengthUnit.CENTIMETRE)!!, 0.0)
        assertEquals(177.8, parseLength("70", LengthUnit.INCH)!!, 1e-9)
        assertNull(parseLength("-1", LengthUnit.CENTIMETRE))
        assertEquals("70,9", lengthInput(180.0, LengthUnit.INCH, ru))
        assertEquals("", lengthInput(null, LengthUnit.INCH))
    }
}
