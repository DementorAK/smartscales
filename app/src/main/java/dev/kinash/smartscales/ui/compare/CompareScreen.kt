package dev.kinash.smartscales.ui.compare

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kinash.smartscales.R
import dev.kinash.smartscales.core.data.Profile
import dev.kinash.smartscales.core.formula.IndicatorUnit
import dev.kinash.smartscales.core.formula.text
import dev.kinash.smartscales.core.model.LengthUnit
import dev.kinash.smartscales.core.model.WeightUnit
import dev.kinash.smartscales.ui.chart.HEIGHT_SERIES
import dev.kinash.smartscales.ui.common.BackButton
import dev.kinash.smartscales.ui.common.ScreenPadding
import dev.kinash.smartscales.ui.common.formatNumber
import dev.kinash.smartscales.ui.common.weightText
import dev.kinash.smartscales.ui.history.WEIGHT_CARD
import dev.kinash.smartscales.ui.history.color
import dev.kinash.smartscales.ui.history.dateTimeText
import dev.kinash.smartscales.ui.history.indicatorValueText
import dev.kinash.smartscales.ui.segments.SegmentSeries
import dev.kinash.smartscales.ui.segments.title
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Two weighings side by side (requirements §6.3): each value at the earlier one and the later one, and the
 * change between them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompareScreen(onBack: () -> Unit, viewModel: CompareViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state) { if (state == CompareState.Gone) onBack() }
    val ready = state as? CompareState.Ready
    val language = LocalConfiguration.current.locales[0].language
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.compare_title)) }, navigationIcon = { BackButton(onBack) })
        },
    ) { padding ->
        if (ready == null) return@Scaffold
        LazyColumn(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            item(key = "header") { Header(ready) }
            item(key = "header-divider") { HorizontalDivider() }
            items(ready.rows, key = { it.id }) { row ->
                CompareItem(row, ready.profile, language)
                HorizontalDivider()
            }
        }
    }
}

/** The two dates and the time between them. */
@Composable
private fun Header(ready: CompareState.Ready) {
    val days = daysApart(ready.before, ready.after)
    Column(Modifier.padding(ScreenPadding), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(R.string.compare_dates, dateTimeText(ready.before), dateTimeText(ready.after)), style = MaterialTheme.typography.bodyLarge)
        Text(
            if (days == 0) stringResource(R.string.compare_same_day) else pluralStringResource(R.plurals.compare_days, days, days),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CompareItem(row: CompareRow, profile: Profile, language: String) {
    val title = when (row.id) {
        WEIGHT_CARD -> stringResource(R.string.card_weight)
        HEIGHT_SERIES -> stringResource(R.string.card_height)
        else -> SegmentSeries.of(row.id)?.title(LocalContext.current) ?: row.title.text(language)
    }
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Value(row.before, row, profile)
                    Text("→")
                    Value(row.after, row, profile)
                }
                // A change partly of method: water by impedance against water from BMI
                if (row.methodsDiffer) {
                    Text(
                        stringResource(R.string.compare_methods_differ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        trailingContent = if (row.change == null) null else {
            { Text(changeText(row, profile), style = MaterialTheme.typography.titleMedium) }
        },
    )
}

/** A value with its zone as a dot; a dash for none. */
@Composable
private fun Value(value: CompareValue?, row: CompareRow, profile: Profile) {
    if (value == null) {
        Text(NO_VALUE)
        return
    }
    value.zone?.let {
        Box(
            Modifier
                .size(8.dp)
                .background(it.color(), CircleShape),
        )
    }
    Text(
        // The weight as in the history list
        if (row.id == WEIGHT_CARD) weightText(value.value, profile.weightUnit) else indicatorValueText(value.value, row.unit, row.precision, profile),
    )
}

/**
 * The change with its sign, in the profile's units; a change of weight in stones in pounds, and of a percentage
 * in percentage points.
 */
@Composable
private fun changeText(row: CompareRow, profile: Profile): String {
    // Decimals as the values have them: the weight in kilograms with the scale's resolution, as in the history
    val decimals = when {
        row.unit == IndicatorUnit.KG && profile.weightUnit != WeightUnit.KILOGRAM -> 1..1
        row.unit == IndicatorUnit.KG && row.id == WEIGHT_CARD -> 1..2
        row.unit == IndicatorUnit.CM -> 0..1
        else -> row.precision..row.precision
    }
    fun shown(value: Double): Double = when (row.unit) {
        IndicatorUnit.KG -> if (profile.weightUnit == WeightUnit.KILOGRAM) value else WeightUnit.POUND.fromKilograms(value)
        IndicatorUnit.CM -> profile.lengthUnit.fromCentimetres(value)
        else -> value
    }
    // The change of the values as shown, so 55.6 to 55.7 is +0.1 and not the 0.0 the unrounded values may give
    fun rounded(value: Double) = BigDecimal.valueOf(shown(value)).setScale(decimals.last, RoundingMode.HALF_UP)
    val change = rounded(row.after!!.value) - rounded(row.before!!.value)
    val rounded = change.abs()
    // No sign for no change at this precision
    val sign = when {
        rounded.signum() == 0 -> ""
        change.signum() > 0 -> "+"
        else -> "−"
    }
    val number = sign + formatNumber(rounded.toDouble(), decimals.first, decimals.last)
    return when (row.unit) {
        IndicatorUnit.KG -> stringResource(if (profile.weightUnit == WeightUnit.KILOGRAM) R.string.unit_kg_value else R.string.unit_lb_value, number)
        IndicatorUnit.CM -> stringResource(if (profile.lengthUnit == LengthUnit.CENTIMETRE) R.string.unit_cm_value else R.string.unit_in_value, number)
        IndicatorUnit.PERCENT -> stringResource(R.string.unit_pp_value, number)
        IndicatorUnit.LITER -> stringResource(R.string.unit_liter_value, number)
        IndicatorUnit.KCAL -> stringResource(R.string.unit_kcal_value, number)
        IndicatorUnit.KG_M2 -> stringResource(R.string.unit_kg_m2_value, number)
        IndicatorUnit.OHM -> stringResource(R.string.unit_ohm_value, number)
        IndicatorUnit.NONE -> number
    }
}

private const val NO_VALUE = "—"
