package dev.kinash.smartscales.ui.measurement

import dev.kinash.smartscales.core.formula.IndicatorUnit
import dev.kinash.smartscales.core.model.LengthUnit
import dev.kinash.smartscales.core.model.WeightUnit
import dev.kinash.smartscales.ui.common.formatNumber
import dev.kinash.smartscales.ui.common.lengthInput
import dev.kinash.smartscales.ui.common.parseLength
import dev.kinash.smartscales.ui.common.parseNumber
import java.util.Locale

/*
 * Fields that the person's formula sets declare (`inputs`, requirements §4.2), such as the wrist: typed in the
 * manual entry form, stored as manual values under their id and given to formulas in centimetres and kilograms.
 */

/**
 * The unit a set's weight field is typed in: the profile's, but pounds for stones, which a single field
 * cannot hold. These are small weights where pounds read naturally.
 */
fun setInputWeightUnit(unit: WeightUnit): WeightUnit = if (unit == WeightUnit.STONE) WeightUnit.POUND else unit

/** Prefilled text of a set's field for a stored [value] (centimetres or kilograms for those units). */
fun setInputText(
    value: Double?,
    unit: IndicatorUnit,
    weightUnit: WeightUnit,
    lengthUnit: LengthUnit,
    locale: Locale = Locale.getDefault(),
): String = when {
    value == null -> ""
    unit == IndicatorUnit.CM -> lengthInput(value, lengthUnit, locale)
    unit == IndicatorUnit.KG -> formatNumber(setInputWeightUnit(weightUnit).fromKilograms(value), 0, 2, locale)
    else -> formatNumber(value, 0, 3, locale)
}

/**
 * The value to store for [text]: centimetres or kilograms, which must be positive; any number for a field
 * without a unit. Null when it does not parse.
 */
fun parseSetInput(text: String, unit: IndicatorUnit, weightUnit: WeightUnit, lengthUnit: LengthUnit): Double? = when (unit) {
    IndicatorUnit.CM -> parseLength(text, lengthUnit)
    IndicatorUnit.KG -> parseNumber(text)?.let(setInputWeightUnit(weightUnit)::toKilograms)?.takeIf { it > 0 }
    else -> parseNumber(text)
}
