package dev.kinash.smartscales.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class UnitsTest {

    @Test
    fun poundsRoundTrip() {
        assertEquals(220.462, WeightUnit.POUND.fromKilograms(100.0), 0.001)
        assertEquals(100.0, WeightUnit.POUND.toKilograms(WeightUnit.POUND.fromKilograms(100.0)), 1e-9)
    }

    @Test
    fun stones() {
        assertEquals(1.0, WeightUnit.STONE.fromKilograms(6.35029318), 1e-9)
        assertEquals(6.35029318, WeightUnit.STONE.toKilograms(1.0), 1e-9)
    }

    @Test
    fun stonesAndPounds() {
        // 72 kg = 158.73 lb = 11 st 4.73 lb
        assertEquals(StonesAndPounds(11, 4.7), StonesAndPounds.fromKilograms(72.0))
        assertEquals(StonesAndPounds(11, 4.73), StonesAndPounds.fromKilograms(72.0, poundDecimals = 2))
        assertEquals(StonesAndPounds(11, 5.0), StonesAndPounds.fromKilograms(72.0, poundDecimals = 0))
        assertEquals(72.0, StonesAndPounds(11, 4.733).toKilograms(), 0.001)
    }

    @Test
    fun poundsRoundingCarriesIntoStones() {
        // 167.99 lb = 11 st 13.99 lb, which must show as 12 st 0 lb rather than 11 st 14.0 lb
        val kg = 167.99 * WeightUnit.KG_PER_POUND
        assertEquals(StonesAndPounds(12, 0.0), StonesAndPounds.fromKilograms(kg))
        assertEquals(StonesAndPounds(11, 13.99), StonesAndPounds.fromKilograms(kg, poundDecimals = 2))
    }

    @Test
    fun inches() {
        assertEquals(70.866, LengthUnit.INCH.fromCentimetres(180.0), 0.001)
        assertEquals(180.0, LengthUnit.INCH.toCentimetres(LengthUnit.INCH.fromCentimetres(180.0)), 1e-9)
    }

    @Test
    fun kilogramsAndCentimetresAreIdentity() {
        assertEquals(80.5, WeightUnit.KILOGRAM.fromKilograms(80.5), 0.0)
        assertEquals(175.3, LengthUnit.CENTIMETRE.toCentimetres(175.3), 0.0)
    }
}
