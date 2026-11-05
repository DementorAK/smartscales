package dev.kinash.smartscales.ui.chart

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kinash.smartscales.R
import dev.kinash.smartscales.core.data.Profile
import dev.kinash.smartscales.core.formula.IndicatorUnit
import dev.kinash.smartscales.core.formula.LocalizedText
import dev.kinash.smartscales.core.formula.text
import dev.kinash.smartscales.core.model.LengthUnit
import dev.kinash.smartscales.core.model.StonesAndPounds
import dev.kinash.smartscales.core.model.WeightUnit
import dev.kinash.smartscales.ui.common.BackButton
import dev.kinash.smartscales.ui.common.ChoiceRow
import dev.kinash.smartscales.ui.common.FieldSpacing
import dev.kinash.smartscales.ui.common.ScreenPadding
import dev.kinash.smartscales.ui.common.SwitchRow
import dev.kinash.smartscales.ui.common.formatNumber
import dev.kinash.smartscales.ui.common.symbol
import dev.kinash.smartscales.ui.goal.GoalSection
import dev.kinash.smartscales.ui.history.TagFilterRow
import dev.kinash.smartscales.ui.history.WEIGHT_CARD
import dev.kinash.smartscales.ui.history.color
import java.time.Duration
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The chart of one indicator over a period (requirements §6.3): its values with the moving average and the
 * zones of the norm, and other indicators added to compare, on a second axis when their unit differs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChartScreen(onBack: () -> Unit, viewModel: ChartViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state) { if (state == ChartState.Gone) onBack() }
    val ready = state as? ChartState.Ready
    val language = LocalConfiguration.current.locales[0].language
    Scaffold(
        topBar = {
            TopAppBar(
                title = { ready?.shown?.firstOrNull()?.let { Text(seriesTitle(it.series, language)) } },
                navigationIcon = { BackButton(onBack) },
            )
        },
    ) { padding ->
        if (ready == null) return@Scaffold
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(FieldSpacing),
        ) {
            ChoiceRow(
                options = ChartPeriod.entries,
                selected = ready.period,
                onSelect = viewModel::selectPeriod,
                label = { stringResource(it.title()) },
            )
            if (ready.tags.isNotEmpty()) TagFilterRow(ready.tags, ready.tagFilter, viewModel::toggleTag)
            if (ready.shown.first().points.isEmpty()) {
                Box(Modifier.fillMaxWidth().height(ChartHeight), contentAlignment = Alignment.Center) {
                    // Weighings without this value (water without impedance) are not "no weighings"
                    val empty = when {
                        ready.weighings > 0 -> R.string.chart_no_values
                        ready.noneTagged -> R.string.tag_filter_none
                        else -> R.string.chart_empty
                    }
                    Text(stringResource(empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Chart(ready, language)
            }
            Legend(ready, language, onRemove = viewModel::remove)
            if (ready.zones.isNotEmpty()) Hint(stringResource(R.string.chart_zones_hint))
            SwitchRow(
                title = stringResource(R.string.chart_smoothing),
                hint = pluralStringResource(R.plurals.chart_smoothing_hint, ready.period.averageDays.toInt(), ready.period.averageDays),
                checked = ready.smoothing,
                enabled = true,
                onChange = viewModel::setSmoothing,
            )
            val main = ready.shown.first().series
            // Height is not something to aim at, nor a child's weight
            if (ready.goalsOffered && main.id != HEIGHT_SERIES) {
                GoalSection(
                    target = ready.goal?.target,
                    goal = ready.progress,
                    unit = main.unit,
                    precision = main.precision,
                    unitSymbol = unitSymbol(LocalContext.current, main.unit, ready.profile),
                    profile = ready.profile,
                    latest = ready.latest,
                    onSet = viewModel::setGoal,
                )
            }
            AddSeries(ready.choices, language, onAdd = viewModel::add)
        }
    }
}

/** Lines of the series in order, so the second series has the second color wherever it is drawn. */
@Composable
private fun seriesColors(): List<Color> = listOf(
    MaterialTheme.colorScheme.primary,
    Color(0xFFC2185B),
    Color(0xFF00897B),
    Color(0xFF8E24AA),
)

@Composable
private fun Chart(ready: ChartState.Ready, language: String) {
    val context = LocalContext.current
    val colors = seriesColors()
    val profile = ready.profile
    val color = { shown: ShownSeries -> colors[ready.shown.indexOf(shown) % colors.size] }
    val lines = ready.shown.flatMap { shown ->
        val average = shown.average
        if (average == null) {
            listOf(ChartLine(shown.points, color(shown), 2.dp, dots = true, endAxis = shown.endAxis))
        } else {
            // The weighings faint behind their average
            listOf(
                ChartLine(shown.points, color(shown).copy(alpha = 0.35f), 1.dp, dots = true, endAxis = shown.endAxis),
                ChartLine(average, color(shown), 3.dp, dots = false, endAxis = shown.endAxis),
            )
        }
    }.filter { it.points.isNotEmpty() }
    val zone = ZoneId.systemDefault()
    val all = ready.shown.flatMap { it.points }.map { it.takenAt }
    // Months with the year only for a long history across years; within one year the day and month read better
    val first = (ready.since ?: all.min()).atZone(zone)
    val last = ready.now.atZone(zone)
    val years = Duration.between(first, last).toDays() > 300 && first.year != last.year
    val dateFormat = remember(years) { DateTimeFormatter.ofPattern(if (years) "LLL yyyy" else "d MMM") }
    val tooltipFormat = remember { DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm") }
    val main = ready.shown.first()
    val summary = pluralStringResource(
        R.plurals.chart_summary, main.points.size,
        titleOf(context, main.series.id, main.series.title, language), main.points.size,
        valueText(context, main.points.last().value, main.series, profile),
    )
    LineChart(
        lines = lines,
        bands = ready.zones.map { it to it.level.color().copy(alpha = 0.14f) },
        from = ready.since,
        to = ready.since?.let { ready.now },
        axisLabel = { value, endAxis ->
            val unit = ready.shown.firstOrNull { it.endAxis == endAxis }?.series?.unit
            formatNumber(value, 0, if (unit == IndicatorUnit.KCAL) 0 else 2)
        },
        dateLabel = { it.atZone(zone).format(dateFormat) },
        tooltip = { time ->
            listOf(TooltipLine(null, time.atZone(zone).format(tooltipFormat))) + ready.shown.mapNotNull { shown ->
                shown.points.firstOrNull { it.takenAt == time }?.let { point ->
                    TooltipLine(
                        color(shown),
                        titleOf(context, shown.series.id, shown.series.title, language) + ": " +
                            valueText(context, point.value, shown.series, profile),
                    )
                }
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(ChartHeight)
            .semantics { contentDescription = summary },
        goal = ready.goal?.let { inProfileUnits(it.target, main.series.unit, profile) to MaterialTheme.colorScheme.tertiary },
    )
}

@Composable
private fun Legend(ready: ChartState.Ready, language: String, onRemove: (String) -> Unit) {
    val colors = seriesColors()
    val context = LocalContext.current
    ready.shown.forEachIndexed { index, shown ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FieldSpacing)) {
            Box(
                Modifier
                    .size(12.dp)
                    .background(colors[index % colors.size], CircleShape),
            )
            Column(Modifier.weight(1f)) {
                Text(seriesText(context, shown.series, language, ready.profile), style = MaterialTheme.typography.bodyLarge)
                val notes = listOfNotNull(
                    stringResource(if (shown.endAxis) R.string.chart_axis_right else R.string.chart_axis_left).takeIf { ready.shown.any { it.endAxis } },
                    // The first series' empty chart says it already
                    stringResource(R.string.chart_no_values).takeIf { index > 0 && shown.points.isEmpty() },
                    // The axis has stones as one number, which is not how stones are said
                    stringResource(R.string.chart_stones_hint).takeIf {
                        shown.series.unit == IndicatorUnit.KG && ready.profile.weightUnit == WeightUnit.STONE
                    },
                )
                if (notes.isNotEmpty()) Hint(notes.joinToString(" · "))
            }
            if (index > 0) {
                val title = seriesTitle(shown.series, language)
                IconButton(onClick = { onRemove(shown.series.id) }) {
                    Icon(painterResource(R.drawable.ic_close), stringResource(R.string.chart_remove, title))
                }
            }
        }
    }
}

@Composable
private fun AddSeries(choices: List<ChartChoice>, language: String, onAdd: (String) -> Unit) {
    var adding by rememberSaveable { mutableStateOf(false) }
    if (choices.isEmpty()) return
    OutlinedButton(onClick = { adding = true }) { Text(stringResource(R.string.chart_add)) }
    if (!adding) return
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = { adding = false },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { adding = false }) { Text(stringResource(android.R.string.cancel)) } },
        title = { Text(stringResource(R.string.chart_add)) },
        text = {
            LazyColumn {
                items(choices, key = { it.id }) { choice ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = choice.fits) {
                                onAdd(choice.id)
                                adding = false
                            }
                            .padding(vertical = FieldSpacing),
                    ) {
                        val title = choiceTitle(context, choice, language)
                        Text(
                            title,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (choice.fits) Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (!choice.fits) Hint(stringResource(R.string.chart_add_unfit))
                    }
                }
            }
        },
    )
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** The name of a series; weight and height are named by the app. */
@Composable
private fun seriesTitle(series: ChartSeries, language: String): String = titleOf(LocalContext.current, series.id, series.title, language)

private fun titleOf(context: Context, id: String, title: LocalizedText, language: String): String = when (id) {
    WEIGHT_CARD -> context.getString(R.string.card_weight)
    HEIGHT_SERIES -> context.getString(R.string.card_height)
    else -> title.text(language)
}

private fun choiceTitle(context: Context, choice: ChartChoice, language: String) = titleOf(context, choice.id, choice.title, language)

/** The name with the unit, "Weight, kg", for the legend, where the axes have numbers only. */
private fun seriesText(context: Context, series: ChartSeries, language: String, profile: Profile): String {
    val title = titleOf(context, series.id, series.title, language)
    val unit = unitSymbol(context, series.unit, profile)
    return if (unit == null) title else context.getString(R.string.chart_series_unit, title, unit)
}

/** The unit of the axis; weight in stones is a fraction of a stone there. */
private fun unitSymbol(context: Context, unit: IndicatorUnit, profile: Profile): String? = when (unit) {
    IndicatorUnit.KG -> context.getString(profile.weightUnit.symbol())
    IndicatorUnit.CM -> context.getString(profile.lengthUnit.symbol())
    IndicatorUnit.PERCENT -> context.getString(R.string.unit_percent)
    IndicatorUnit.LITER -> context.getString(R.string.unit_liter)
    IndicatorUnit.KCAL -> context.getString(R.string.unit_kcal)
    IndicatorUnit.KG_M2 -> context.getString(R.string.unit_kg_m2)
    IndicatorUnit.NONE -> null
}

/** A value already in the profile's units, as the cards show it; stones as stones and pounds. */
private fun valueText(context: Context, value: Double, series: ChartSeries, profile: Profile): String {
    val number = formatNumber(value, series.precision, series.precision)
    return when (series.unit) {
        // As in the history list: the scale's resolution in kilograms
        IndicatorUnit.KG -> when (profile.weightUnit) {
            WeightUnit.KILOGRAM -> context.getString(R.string.unit_kg_value, formatNumber(value, 1, series.precision))
            WeightUnit.POUND -> context.getString(R.string.unit_lb_value, formatNumber(value, 1, 1))
            WeightUnit.STONE -> StonesAndPounds.fromKilograms(WeightUnit.STONE.toKilograms(value), poundDecimals = 1).let {
                context.getString(R.string.unit_st_lb_value, it.stones.toString(), formatNumber(it.pounds, 1, 1))
            }
        }
        IndicatorUnit.CM -> when (profile.lengthUnit) {
            LengthUnit.CENTIMETRE -> context.getString(R.string.unit_cm_value, formatNumber(value, 0, 1))
            LengthUnit.INCH -> context.getString(R.string.unit_in_value, formatNumber(value, 0, 1))
        }
        IndicatorUnit.PERCENT -> context.getString(R.string.unit_percent_value, number)
        IndicatorUnit.LITER -> context.getString(R.string.unit_liter_value, number)
        IndicatorUnit.KCAL -> context.getString(R.string.unit_kcal_value, number)
        IndicatorUnit.KG_M2 -> context.getString(R.string.unit_kg_m2_value, number)
        IndicatorUnit.NONE -> number
    }
}

private fun ChartPeriod.title(): Int = when (this) {
    ChartPeriod.WEEK -> R.string.chart_period_week
    ChartPeriod.MONTH -> R.string.chart_period_month
    ChartPeriod.YEAR -> R.string.chart_period_year
    ChartPeriod.ALL -> R.string.chart_period_all
}

private val ChartHeight = 280.dp
