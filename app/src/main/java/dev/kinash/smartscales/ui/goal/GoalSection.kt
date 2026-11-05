package dev.kinash.smartscales.ui.goal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.kinash.smartscales.R
import dev.kinash.smartscales.core.data.Profile
import dev.kinash.smartscales.core.formula.IndicatorUnit
import dev.kinash.smartscales.ui.common.FieldSpacing
import dev.kinash.smartscales.ui.common.NumberField
import dev.kinash.smartscales.ui.common.Prefill
import dev.kinash.smartscales.ui.common.WeightField
import dev.kinash.smartscales.ui.common.WeightInput
import dev.kinash.smartscales.ui.common.formatNumber
import dev.kinash.smartscales.ui.common.lengthInput
import dev.kinash.smartscales.ui.common.parseLength
import dev.kinash.smartscales.ui.common.parseNumber
import dev.kinash.smartscales.ui.common.resolve
import dev.kinash.smartscales.ui.history.goalValueText
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The goal of a chart's indicator: how far it has come and when the trend gets there, or a button to set one.
 * [target] is the goal, null without one; [goal] its progress, null while the indicator has no value.
 * [unit] and [precision] are the indicator's; [unitSymbol] is its unit as the field shows it.
 */
@Composable
fun GoalSection(
    target: Double?,
    goal: GoalProgress?,
    unit: IndicatorUnit,
    precision: Int,
    unitSymbol: String?,
    profile: Profile,
    /** The indicator's latest value, which a goal cannot be: it would not say which way to go. */
    latest: Double?,
    onSet: (Double?) -> Unit,
) {
    var editing by rememberSaveable { mutableStateOf(false) }
    if (target == null) {
        OutlinedButton(onClick = { editing = true }) { Text(stringResource(R.string.goal_set)) }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.goal_title, goalValueText(target, unit, precision, profile)), style = MaterialTheme.typography.titleMedium)
            if (goal != null) {
                goal.fraction?.let { LinearProgressIndicator(progress = { it.toFloat() }, modifier = Modifier.fillMaxWidth()) }
                Hint(
                    if (goal.achieved) {
                        stringResource(R.string.goal_achieved)
                    } else {
                        val left = goalValueText(abs(goal.remaining), unit, precision, profile)
                        goal.fraction?.let { stringResource(R.string.goal_progress, (it * 100).roundToInt(), left) }
                            ?: stringResource(R.string.goal_left, left)
                    },
                )
                goal.forecast?.let { Hint(forecastText(it)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(FieldSpacing)) {
                OutlinedButton(onClick = { editing = true }) { Text(stringResource(R.string.goal_change)) }
                TextButton(onClick = { onSet(null) }) { Text(stringResource(R.string.goal_remove)) }
            }
        }
    }
    if (editing) {
        GoalDialog(target, unit, precision, unitSymbol, profile, latest, onDismiss = { editing = false }) {
            onSet(it)
            editing = false
        }
    }
}

@Composable
private fun forecastText(forecast: Forecast): String = when (forecast) {
    is Forecast.On -> stringResource(
        R.string.goal_forecast_on,
        forecast.date.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)),
    )
    Forecast.NotApproaching -> stringResource(R.string.goal_forecast_away)
    Forecast.TooFar -> stringResource(R.string.goal_forecast_far)
    Forecast.TooFewWeighings -> stringResource(R.string.goal_forecast_few)
}

/**
 * The target in the profile's units. A value left as it was shown keeps the stored one, so opening the dialog
 * and saving does not round it, and the goal keeps its start.
 */
@Composable
private fun GoalDialog(
    target: Double?,
    unit: IndicatorUnit,
    precision: Int,
    unitSymbol: String?,
    profile: Profile,
    latest: Double?,
    onDismiss: () -> Unit,
    onSave: (Double) -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    val weight = unit == IndicatorUnit.KG
    val shownWeight = remember(target) { WeightInput.of(target, profile.weightUnit, locale) }
    val shownText = remember(target) {
        when {
            target == null -> ""
            unit == IndicatorUnit.CM -> lengthInput(target, profile.lengthUnit, locale)
            else -> formatNumber(target, 0, precision, locale)
        }
    }
    // Two strings, which a saved state keeps as they are
    var stones by rememberSaveable { mutableStateOf(shownWeight.main) }
    var pounds by rememberSaveable { mutableStateOf(shownWeight.pounds) }
    val weightInput = WeightInput(stones, pounds)
    var text by rememberSaveable { mutableStateOf(shownText) }
    var error by rememberSaveable { mutableStateOf(false) }
    var atLatest by rememberSaveable { mutableStateOf(false) }
    val value = if (weight) {
        target?.let { Prefill(shownWeight, it) }.resolve(weightInput) { it.toKilograms(profile.weightUnit) }
    } else {
        target?.let { Prefill(shownText, it) }.resolve(text) { if (unit == IndicatorUnit.CM) parseLength(it, profile.lengthUnit) else parseNumber(it) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.goal_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(FieldSpacing)) {
                if (weight) {
                    WeightField(weightInput, {
                        stones = it.main
                        pounds = it.pounds
                        error = false
                        atLatest = false
                    }, profile.weightUnit, stringResource(R.string.goal_dialog_field), isError = error)
                } else {
                    NumberField(text, {
                        text = it
                        error = false
                        atLatest = false
                    }, stringResource(R.string.goal_dialog_field), unitSymbol.orEmpty(), isError = error)
                }
                if (atLatest) {
                    Text(stringResource(R.string.goal_at_latest), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                } else {
                    Hint(stringResource(R.string.goal_dialog_hint))
                }
            }
        },
        confirmButton = {
            // The latest value as shown: a goal to keep 80 kg would count gaining as reached and losing as not
            val latestText = latest?.let { goalValueText(it, unit, precision, profile) }
            val valueText = value?.let { goalValueText(it, unit, precision, profile) }
            TextButton(
                onClick = {
                    when {
                        value == null || !isPlausible(value, unit) -> error = true
                        value != target && valueText == latestText -> {
                            error = true
                            atLatest = true
                        }
                        else -> onSave(value)
                    }
                },
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

/** A target the indicator can have: above zero, and a percentage below a hundred. */
fun isPlausible(value: Double, unit: IndicatorUnit): Boolean = when (unit) {
    IndicatorUnit.NONE -> value.isFinite()
    IndicatorUnit.PERCENT -> value > 0 && value < 100
    else -> value > 0 && value.isFinite()
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
