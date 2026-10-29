package dev.kinash.smartscales.ui.common

import dev.kinash.smartscales.core.model.LengthUnit
import dev.kinash.smartscales.core.model.StonesAndPounds
import dev.kinash.smartscales.core.model.WeightUnit
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/** Parses a number typed with either a dot or a comma as the decimal separator. */
fun parseNumber(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }

/** Formats [value] with [minDecimals] to [maxDecimals] fraction digits in the locale's notation. */
fun formatNumber(value: Double, minDecimals: Int, maxDecimals: Int, locale: Locale = Locale.getDefault()): String {
    val format = DecimalFormat("0", DecimalFormatSymbols.getInstance(locale))
    format.minimumFractionDigits = minDecimals
    format.maximumFractionDigits = maxDecimals
    return format.format(value)
}

/**
 * Text of a weight field in the profile's unit. Stones are typed as two numbers, whole stones in [main]
 * and remaining pounds in [pounds]; other units use [main] only.
 */
data class WeightInput(val main: String = "", val pounds: String = "") {
    val isBlank: Boolean get() = main.isBlank() && pounds.isBlank()

    /** Kilograms, or null when the text is blank, not a number or not positive. */
    fun toKilograms(unit: WeightUnit): Double? {
        if (isBlank) return null
        val kg = when (unit) {
            WeightUnit.STONE -> {
                val stones = if (main.isBlank()) 0 else main.trim().toIntOrNull() ?: return null
                val lb = if (pounds.isBlank()) 0.0 else parseNumber(pounds) ?: return null
                if (stones < 0 || lb < 0 || lb >= WeightUnit.POUNDS_PER_STONE) return null
                StonesAndPounds(stones, lb).toKilograms()
            }
            else -> unit.toKilograms(parseNumber(main) ?: return null)
        }
        return kg.takeIf { it > 0 }
    }

    companion object {
        /** Prefilled text for [kg], or empty fields for null. */
        fun of(kg: Double?, unit: WeightUnit, locale: Locale = Locale.getDefault()): WeightInput = when {
            kg == null -> WeightInput()
            unit == WeightUnit.STONE -> StonesAndPounds.fromKilograms(kg, poundDecimals = 1).let {
                WeightInput(it.stones.toString(), formatNumber(it.pounds, 0, 1, locale))
            }
            else -> WeightInput(formatNumber(unit.fromKilograms(kg), 0, 2, locale))
        }
    }
}

/** Centimetres from a length typed in [unit], or null when blank, not a number or not positive. */
fun parseLength(text: String, unit: LengthUnit): Double? =
    parseNumber(text)?.let(unit::toCentimetres)?.takeIf { it > 0 }

/** Prefilled text of a length field. */
fun lengthInput(cm: Double?, unit: LengthUnit, locale: Locale = Locale.getDefault()): String =
    cm?.let { formatNumber(unit.fromCentimetres(it), 0, 1, locale) } ?: ""

/**
 * A stored value and the rounded text it was shown as. While the field still holds [text] the stored value
 * is kept as is, so opening a form and saving it does not round raw data.
 */
data class Prefill<T>(val text: T, val value: Double)

/** The prefilled value while [current] is unchanged, otherwise [current] parsed. */
fun <T> Prefill<T>?.resolve(current: T, parse: (T) -> Double?): Double? =
    if (this != null && current == text) value else parse(current)
