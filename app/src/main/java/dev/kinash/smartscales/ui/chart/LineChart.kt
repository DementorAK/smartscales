package dev.kinash.smartscales.ui.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** One line of a chart: its points, oldest first, and how it is drawn. */
data class ChartLine(
    val points: List<ChartPoint>,
    val color: Color,
    val width: Dp,
    /** Points are drawn as dots, and a tap on the chart picks among them. */
    val dots: Boolean,
    /** On the right axis instead of the left one. */
    val endAxis: Boolean = false,
)

/** A line of the tooltip: the color of its series and the text. */
data class TooltipLine(val color: Color?, val text: String)

/**
 * A line chart over time, drawn on a canvas: the [bands] of the left axis behind the lines, a left axis and,
 * when a line goes on it, a right one, dates below. A tap shows what [tooltip] says about the nearest weighing.
 * The time spans [from] to [to], or the points' own times when they are null.
 */
@Composable
fun LineChart(
    lines: List<ChartLine>,
    bands: List<Pair<ZoneBand, Color>>,
    from: Instant?,
    to: Instant?,
    axisLabel: (value: Double, endAxis: Boolean) -> String,
    dateLabel: (Instant) -> String,
    tooltip: (Instant) -> List<TooltipLine>,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val tooltipStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface)
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val tooltipBackground = MaterialTheme.colorScheme.surfaceContainerHighest
    val times = lines.filter { it.dots }.flatMap { it.points }.map { it.takenAt }.distinct().sorted()
    var selected by remember(lines) { mutableStateOf<Instant?>(null) }
    var width by remember { mutableIntStateOf(0) }

    val all = lines.flatMap { it.points }
    val start = (from ?: all.minOf { it.takenAt }).epochSecond.toDouble()
    val end = (to ?: all.maxOf { it.takenAt }).epochSecond.toDouble().let { if (it > start) it else start + DAY_SECONDS }
    val left = lines.filter { !it.endAxis }.flatMap { it.points }.map { it.value }
    val right = lines.filter { it.endAxis }.flatMap { it.points }.map { it.value }
    val leftRange = left.takeIf { it.isNotEmpty() }?.let(::axisRange)
    val rightRange = right.takeIf { it.isNotEmpty() }?.let(::axisRange)

    // The plot's sides come from the axis labels and the width, worked out here so drawing and taps share them
    val gap = with(LocalDensity.current) { 4.dp.toPx() }
    fun labels(range: ClosedFloatingPointRange<Double>?, endAxis: Boolean) =
        range?.let { niceTicks(it) }.orEmpty().map { it to measurer.measure(axisLabel(it, endAxis), labelStyle) }
    val leftLabels = labels(leftRange, false)
    val rightLabels = labels(rightRange, true)
    val plotLeft = (leftLabels.maxOfOrNull { it.second.size.width } ?: 0) + gap
    val plotRight = width - ((rightLabels.maxOfOrNull { it.second.size.width }?.plus(gap)) ?: gap)

    Canvas(
        modifier
            .onSizeChanged { width = it.width }
            .pointerInput(times, start, end, plotLeft, plotRight) {
                detectTapGestures { tap ->
                    if (times.isEmpty() || plotRight <= plotLeft) return@detectTapGestures
                    val x = start + ((tap.x - plotLeft) / (plotRight - plotLeft)) * (end - start)
                    val nearest = times.minBy { abs(it.epochSecond - x) }
                    selected = if (selected == nearest) null else nearest
                }
            },
    ) {
        val dateHeight = measurer.measure("0", labelStyle).size.height
        val plotTop = dateHeight / 2f
        val plotBottom = size.height - dateHeight - gap
        fun xOf(time: Instant) = plotLeft + ((time.epochSecond - start) / (end - start)).toFloat() * (plotRight - plotLeft)
        fun yOf(value: Double, range: ClosedFloatingPointRange<Double>) =
            plotBottom - ((value - range.start) / (range.endInclusive - range.start)).toFloat() * (plotBottom - plotTop)

        clipRect(plotLeft, plotTop, plotRight, plotBottom) {
            leftRange?.let { range ->
                bands.forEach { (band, color) ->
                    val top = band.to?.let { yOf(it, range) } ?: plotTop
                    val bottom = band.from?.let { yOf(it, range) } ?: plotBottom
                    if (bottom > top) drawRect(color, Offset(plotLeft, top), Size(plotRight - plotLeft, bottom - top))
                }
            }
        }
        leftLabels.forEach { (value, layout) ->
            val y = yOf(value, leftRange!!)
            drawLine(gridColor, Offset(plotLeft, y), Offset(plotRight, y), 1.dp.toPx())
            drawText(layout, topLeft = Offset(plotLeft - gap - layout.size.width, y - layout.size.height / 2f))
        }
        rightLabels.forEach { (value, layout) ->
            val y = yOf(value, rightRange!!)
            drawText(layout, topLeft = Offset(plotRight + gap, y - layout.size.height / 2f))
        }
        // About four dates, from the first to the last, kept inside the canvas
        (0..DATE_LABELS).forEach { i ->
            val time = Instant.ofEpochSecond((start + (end - start) * i / DATE_LABELS).toLong())
            val layout = measurer.measure(dateLabel(time), labelStyle)
            val x = (xOf(time) - layout.size.width / 2f).coerceIn(0f, size.width - layout.size.width)
            drawText(layout, topLeft = Offset(x, plotBottom + gap))
        }

        clipRect(plotLeft, plotTop, plotRight, plotBottom) {
            lines.forEach { line ->
                val range = (if (line.endAxis) rightRange else leftRange) ?: return@forEach
                val offsets = line.points.map { Offset(xOf(it.takenAt), yOf(it.value, range)) }
                if (offsets.size > 1) {
                    val path = Path().apply {
                        moveTo(offsets.first().x, offsets.first().y)
                        offsets.drop(1).forEach { lineTo(it.x, it.y) }
                    }
                    drawPath(path, line.color, style = Stroke(line.width.toPx(), join = StrokeJoin.Round))
                }
                if (line.dots || offsets.size == 1) offsets.forEach { drawCircle(line.color, 3.dp.toPx(), it) }
            }
        }

        selected?.let { time ->
            val x = xOf(time)
            drawLine(gridColor, Offset(x, plotTop), Offset(x, plotBottom), 1.dp.toPx())
            val texts = tooltip(time).map { it to measurer.measure(it.text, tooltipStyle) }
            val swatch = 8.dp.toPx()
            val padding = 8.dp.toPx()
            val width = texts.maxOf { (line, layout) -> layout.size.width + if (line.color != null) swatch + gap else 0f } + 2 * padding
            val height = texts.sumOf { it.second.size.height }.toFloat() + 2 * padding
            val boxLeft = (x + gap).let { if (it + width > size.width) x - gap - width else it }.coerceAtLeast(0f)
            drawRoundRect(tooltipBackground, Offset(boxLeft, plotTop), Size(width, height), CornerRadius(padding / 2))
            var y = plotTop + padding
            texts.forEach { (line, layout) ->
                var textLeft = boxLeft + padding
                line.color?.let {
                    drawCircle(it, swatch / 2, Offset(textLeft + swatch / 2, y + layout.size.height / 2f))
                    textLeft += swatch + gap
                }
                drawText(layout, topLeft = Offset(textLeft, y))
                y += layout.size.height
            }
        }
    }
}

/**
 * Round values for an axis' labels within [range], about [count] of them: steps of 1, 2, 2.5 or 5 times a power
 * of ten.
 */
fun niceTicks(range: ClosedFloatingPointRange<Double>, count: Int = 4): List<Double> {
    val span = range.endInclusive - range.start
    if (span <= 0) return listOf(range.start)
    val raw = span / count
    val magnitude = 10.0.pow(floor(log10(raw)))
    val step = listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * magnitude }.first { it >= raw }
    val first = ceil(range.start / step) * step
    val decimals = maxOf(0, 1 - floor(log10(step)).toInt())
    return generateSequence(first) { it + step }.takeWhile { it <= range.endInclusive + step * 1e-9 }
        // Rounded to the step's decimals, so 0.1 + 0.2 is 0.3
        .map { BigDecimal.valueOf(it).setScale(decimals, RoundingMode.HALF_UP).toDouble() }
        .toList()
}

private const val DAY_SECONDS = 86_400.0

/** Date labels under the chart, after the first one. */
private const val DATE_LABELS = 3
