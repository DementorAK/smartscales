package dev.kinash.smartscales.core.model

import kotlin.math.pow
import kotlin.math.roundToLong

/** Weight display unit. Weights are always stored in kilograms; conversion happens only for display and input. */
enum class WeightUnit {
    KILOGRAM,
    POUND,

    /** Shown as stones and pounds, e.g. 11 st 4.5 lb. */
    STONE,
    ;

    fun fromKilograms(kg: Double): Double = when (this) {
        KILOGRAM -> kg
        POUND -> kg / KG_PER_POUND
        STONE -> kg / KG_PER_POUND / POUNDS_PER_STONE
    }

    fun toKilograms(value: Double): Double = when (this) {
        KILOGRAM -> value
        POUND -> value * KG_PER_POUND
        STONE -> value * POUNDS_PER_STONE * KG_PER_POUND
    }

    companion object {
        /** International avoirdupois pound, exact by definition. */
        const val KG_PER_POUND = 0.45359237
        const val POUNDS_PER_STONE = 14
    }
}

/** Stones and remaining pounds, the usual way to show a weight in stones. */
data class StonesAndPounds(val stones: Int, val pounds: Double) {
    fun toKilograms(): Double = (stones * WeightUnit.POUNDS_PER_STONE + pounds) * WeightUnit.KG_PER_POUND

    companion object {
        /**
         * Splits [kg] into stones and pounds, with pounds rounded to [poundDecimals] places.
         * Rounding happens before the split, so 11 st 13.99 lb becomes 12 st 0 lb, never 11 st 14.0 lb.
         */
        fun fromKilograms(kg: Double, poundDecimals: Int = 1): StonesAndPounds {
            require(poundDecimals >= 0) { "Decimals must not be negative: $poundDecimals" }
            val scale = 10.0.pow(poundDecimals)
            val scaledPounds = (kg / WeightUnit.KG_PER_POUND * scale).roundToLong()
            val scaledPerStone = (WeightUnit.POUNDS_PER_STONE * scale).roundToLong()
            val stones = (scaledPounds / scaledPerStone).toInt()
            return StonesAndPounds(stones, (scaledPounds % scaledPerStone) / scale)
        }
    }
}

/** Length display unit for height and circumferences. Lengths are always stored in centimetres. */
enum class LengthUnit {
    CENTIMETRE,
    INCH,
    ;

    fun fromCentimetres(cm: Double): Double = when (this) {
        CENTIMETRE -> cm
        INCH -> cm / CM_PER_INCH
    }

    fun toCentimetres(value: Double): Double = when (this) {
        CENTIMETRE -> value
        INCH -> value * CM_PER_INCH
    }

    companion object {
        /** Exact by definition. */
        const val CM_PER_INCH = 2.54
    }
}
