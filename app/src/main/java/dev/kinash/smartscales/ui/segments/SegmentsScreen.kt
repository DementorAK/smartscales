package dev.kinash.smartscales.ui.segments

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kinash.smartscales.R
import dev.kinash.smartscales.core.model.Segment
import dev.kinash.smartscales.ui.common.BackButton
import dev.kinash.smartscales.ui.common.ChoiceRow
import dev.kinash.smartscales.ui.common.FieldSpacing
import dev.kinash.smartscales.ui.common.ScreenPadding
import dev.kinash.smartscales.ui.common.formatNumber
import dev.kinash.smartscales.ui.history.dateTimeText
import kotlin.math.abs

/**
 * The segments of an 8-electrode scale (requirements §6.3a): the latest weighing's impedances on a body, each
 * with its change since the weighing before, and a tap on one opens its chart.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SegmentsScreen(onBack: () -> Unit, onOpenChart: (profileId: Long, series: String) -> Unit, viewModel: SegmentsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state) { if (state == SegmentsState.Gone) onBack() }
    val ready = state as? SegmentsState.Ready
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.segments_title)) }, navigationIcon = { BackButton(onBack) }) },
    ) { padding ->
        if (ready == null) return@Scaffold
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Column(Modifier.padding(ScreenPadding), verticalArrangement = Arrangement.spacedBy(FieldSpacing)) {
                Text(dateTimeText(ready.latest), style = MaterialTheme.typography.bodyLarge)
                if (ready.frequencies.size > 1) {
                    ChoiceRow(
                        options = ready.frequencies,
                        selected = ready.frequency,
                        onSelect = viewModel::selectFrequency,
                        label = { stringResource(R.string.segment_frequency_choice, it + 1) },
                    )
                }
                Body(ready.values)
                Text(
                    stringResource(R.string.segments_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider()
            val context = LocalContext.current
            ready.values.forEach { value ->
                ListItem(
                    headlineContent = { Text(value.series.title(context)) },
                    supportingContent = value.change?.let { { Text(stringResource(R.string.segments_change, changeText(it))) } },
                    trailingContent = { Text(ohmsText(value.ohms), style = MaterialTheme.typography.titleMedium) },
                    modifier = Modifier.clickable { onOpenChart(ready.profileId, value.series.id) },
                )
                HorizontalDivider()
            }
        }
    }
}

/**
 * A figure from the front, so the person's right side is on the left, with each segment's value beside it and the
 * trunk's on it. The list below says the same in words, so the figure is left out for TalkBack.
 */
@Composable
private fun Body(values: List<SegmentValue>) {
    val measurer = rememberTextMeasurer()
    val fill = MaterialTheme.colorScheme.secondaryContainer
    val nameStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val valueStyle = MaterialTheme.typography.labelLarge.copy(color = MaterialTheme.colorScheme.onSurface)
    val labels = values.associate { it.series.segment to (stringResource(it.series.segment.title()) to ohmsText(it.ohms)) }
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(320.dp),
    ) {
        val w = size.width
        val h = size.height
        // The figure is as wide as tall allows, centred
        val unit = minOf(w / 1.0f, h * 0.62f)
        val left = (w - unit) / 2
        fun x(f: Float) = left + f * unit
        fun y(f: Float) = f * h
        val corner = CornerRadius(unit * 0.03f)
        fun part(x0: Float, y0: Float, x1: Float, y1: Float) =
            drawRoundRect(fill, Offset(x(x0), y(y0)), Size(x(x1) - x(x0), y(y1) - y(y0)), corner)
        drawCircle(fill, unit * 0.07f, Offset(x(0.5f), y(0.09f)))
        part(0.39f, 0.18f, 0.61f, 0.55f)
        part(0.29f, 0.19f, 0.37f, 0.52f)
        part(0.63f, 0.19f, 0.71f, 0.52f)
        part(0.40f, 0.56f, 0.49f, 0.97f)
        part(0.51f, 0.56f, 0.60f, 0.97f)

        fun label(segment: Segment, cx: Float, cy: Float, align: TextAlign) {
            val (name, value) = labels[segment] ?: return
            val nameLayout = measurer.measure(name, nameStyle)
            val valueLayout = measurer.measure(value, valueStyle)
            val height = nameLayout.size.height + valueLayout.size.height
            var top = y(cy) - height / 2f
            listOf(nameLayout, valueLayout).forEach { layout ->
                val textLeft = when (align) {
                    TextAlign.Right -> x(cx) - layout.size.width
                    TextAlign.Left -> x(cx)
                    else -> x(cx) - layout.size.width / 2f
                }.coerceIn(0f, (w - layout.size.width).coerceAtLeast(0f))
                drawText(layout, topLeft = Offset(textLeft, top))
                top += layout.size.height
            }
        }
        label(Segment.RIGHT_ARM, 0.27f, 0.33f, TextAlign.Right)
        label(Segment.LEFT_ARM, 0.73f, 0.33f, TextAlign.Left)
        label(Segment.TRUNK, 0.5f, 0.36f, TextAlign.Center)
        label(Segment.RIGHT_LEG, 0.38f, 0.78f, TextAlign.Right)
        label(Segment.LEFT_LEG, 0.62f, 0.78f, TextAlign.Left)
    }
}

@Composable
private fun ohmsText(ohms: Double?): String = ohms?.let { stringResource(R.string.unit_ohm_value, formatNumber(it, 1, 1)) } ?: NO_VALUE

@Composable
private fun changeText(change: Double): String {
    val number = formatNumber(abs(change), 1, 1)
    val sign = when {
        number == formatNumber(0.0, 1, 1) -> ""
        change > 0 -> "+"
        else -> "−"
    }
    return stringResource(R.string.unit_ohm_value, sign + number)
}

private const val NO_VALUE = "—"
