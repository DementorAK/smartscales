package dev.kinash.smartscales.ui.common

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.kinash.smartscales.R
import dev.kinash.smartscales.core.model.LengthUnit
import dev.kinash.smartscales.core.model.StonesAndPounds
import dev.kinash.smartscales.core.model.WeightUnit

/** Weight for display, for example "80.5 kg" or "12 st 9.5 lb". */
@Composable
fun weightText(kg: Double, unit: WeightUnit): String = when (unit) {
    WeightUnit.KILOGRAM -> stringResource(R.string.unit_kg_value, formatNumber(kg, 1, 2))
    WeightUnit.POUND -> stringResource(R.string.unit_lb_value, formatNumber(unit.fromKilograms(kg), 1, 1))
    WeightUnit.STONE -> StonesAndPounds.fromKilograms(kg, poundDecimals = 1).let {
        stringResource(R.string.unit_st_lb_value, it.stones.toString(), formatNumber(it.pounds, 1, 1))
    }
}

@Composable
fun lengthText(cm: Double, unit: LengthUnit): String = when (unit) {
    LengthUnit.CENTIMETRE -> stringResource(R.string.unit_cm_value, formatNumber(cm, 0, 1))
    LengthUnit.INCH -> stringResource(R.string.unit_in_value, formatNumber(unit.fromCentimetres(cm), 0, 1))
}

/** Short unit symbol shown as a text field suffix. */
@StringRes
fun WeightUnit.symbol(): Int = when (this) {
    WeightUnit.KILOGRAM -> R.string.unit_kg
    WeightUnit.POUND -> R.string.unit_lb
    WeightUnit.STONE -> R.string.unit_st
}

@StringRes
fun LengthUnit.symbol(): Int = when (this) {
    LengthUnit.CENTIMETRE -> R.string.unit_cm
    LengthUnit.INCH -> R.string.unit_in
}

@StringRes
fun WeightUnit.title(): Int = when (this) {
    WeightUnit.KILOGRAM -> R.string.unit_kilograms
    WeightUnit.POUND -> R.string.unit_pounds
    WeightUnit.STONE -> R.string.unit_stones
}

@StringRes
fun LengthUnit.title(): Int = when (this) {
    LengthUnit.CENTIMETRE -> R.string.unit_centimetres
    LengthUnit.INCH -> R.string.unit_inches
}
