package dev.kinash.smartscales.core.protocol

import dev.kinash.smartscales.core.model.WeightUnit
import kotlin.math.pow

/** Unit the scale shows the weight in, from bits 4..3 of the property byte. */
enum class ScaleUnit(
    /** The code in bits 4..3 of the property byte and in unit commands; the entries must stay in this order. */
    val code: Int,
) {
    KILOGRAM(0),

    /** Chinese jin, half a kilogram. */
    JIN(1),
    POUND(2),

    /** Stones and pounds; the raw value holds stones in the high byte and pounds × 10 in the low byte. */
    STONE(3),
    ;

    companion object {
        fun of(code: Int): ScaleUnit = entries.first { it.code == code }
    }
}

/** Which bits of the property byte give the number of decimals; differs between protocol families. */
enum class DigitsLayout {
    /** OKOK advertisement and GATT: bits 2..1, `01` → 0, `10` → 2, otherwise 1. */
    OKOK,

    /** OKOKCloud: bits 2..1, `01` → 0, `10` → 1, otherwise 2. */
    CLOUD,

    /** OKOKCloudV4: bits 6..5, `01` → 0, `10` → 1, otherwise 2. */
    CLOUD_V4,
}

/** The property byte sent with every weight: lock flag, unit and precision. */
@JvmInline
value class ScaleProperty(val bits: Int) {
    /** The weight is final (the scale "locked" it). */
    val locked: Boolean get() = bits and 0x01 != 0

    val unit: ScaleUnit get() = ScaleUnit.of((bits shr 3) and 0x03)

    /** Bit 5 of a V2 (`C0`) property: the scale measures impedance, not only weight. */
    val measuresImpedance: Boolean get() = bits and 0x20 != 0

    fun digits(layout: DigitsLayout): Int {
        val code = when (layout) {
            DigitsLayout.OKOK, DigitsLayout.CLOUD -> (bits shr 1) and 0x03
            DigitsLayout.CLOUD_V4 -> (bits shr 5) and 0x03
        }
        return when (layout) {
            DigitsLayout.OKOK -> when (code) {
                0b01 -> 0
                0b10 -> 2
                else -> 1
            }
            DigitsLayout.CLOUD, DigitsLayout.CLOUD_V4 -> when (code) {
                0b01 -> 0
                0b10 -> 1
                else -> 2
            }
        }
    }
}

/**
 * A weight as the scale sent it: [raw] in the scale's [unit] with [digits] decimals.
 * [kilograms] is converted without rounding, so nothing is lost before it is stored.
 */
data class ScaleWeight(val raw: Int, val unit: ScaleUnit, val digits: Int) {
    val kilograms: Double
        get() = when (unit) {
            ScaleUnit.KILOGRAM -> value
            ScaleUnit.JIN -> value * KG_PER_JIN
            ScaleUnit.POUND -> WeightUnit.POUND.toKilograms(value)
            ScaleUnit.STONE -> WeightUnit.POUND.toKilograms(stones * WeightUnit.POUNDS_PER_STONE + pounds)
        }

    /** The number shown on the scale; for stones use [stones] and [pounds]. */
    val value: Double get() = raw / 10.0.pow(digits)

    val stones: Int get() = raw shr 8

    /** Pounds after whole stones; the scale always sends them with one decimal. */
    val pounds: Double get() = (raw and 0xFF) / 10.0

    companion object {
        const val KG_PER_JIN = 0.5

        fun of(raw: Int, property: ScaleProperty, layout: DigitsLayout) =
            ScaleWeight(raw, property.unit, property.digits(layout))
    }
}
