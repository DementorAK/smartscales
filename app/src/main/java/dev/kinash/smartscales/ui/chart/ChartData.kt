package dev.kinash.smartscales.ui.chart

import dev.kinash.smartscales.core.data.Profile
import dev.kinash.smartscales.core.formula.FormulaModel
import dev.kinash.smartscales.core.formula.IndicatorUnit
import dev.kinash.smartscales.core.formula.LocalizedText
import dev.kinash.smartscales.core.formula.ZoneLevel
import dev.kinash.smartscales.core.model.WeightUnit
import dev.kinash.smartscales.indicators.MeasuredIndicators
import dev.kinash.smartscales.ui.history.CHILD_HEIGHT_CARD
import dev.kinash.smartscales.ui.history.CHILD_WEIGHT_CARD
import dev.kinash.smartscales.ui.history.WEIGHT_CARD
import dev.kinash.smartscales.ui.history.cardChoices
import dev.kinash.smartscales.ui.segments.SegmentSeries
import java.time.Duration
import java.time.Instant

/** What a chart shows: the last [days] up to now, or all the history. */
enum class ChartPeriod(val days: Long?) {
    WEEK(7),
    MONTH(30),
    YEAR(365),
    ALL(null),
    ;

    /**
     * The moving average takes the weighings of this many days up to each one: a week evens out the day-to-day
     * water swings; a month, over a year or more, leaves the slow trend.
     */
    val averageDays: Long get() = if (this == WEEK || this == MONTH) 7 else 30
}

/** Height, from the measurement's snapshot: a child's chart has it, as the child's card does. */
const val HEIGHT_SERIES = "height"

data class ChartPoint(val takenAt: Instant, val value: Double)

/**
 * The values of one indicator, a group or the weight over the history, oldest first, in the profile's units
 * (pounds, inches) so the axis reads as the cards do. [title] is empty for weight and height, whose names come
 * from the app.
 */
data class ChartSeries(
    val id: String,
    val title: LocalizedText,
    val unit: IndicatorUnit,
    val precision: Int,
    val points: List<ChartPoint>,
)

/** A zone of the norm as a band of values, open at the ends: below the first bound and above the last. */
data class ZoneBand(val from: Double?, val to: Double?, val level: ZoneLevel)

/** A card id as a series: children's weight and height cards are the weight and height. */
fun seriesId(cardId: String): String = when (cardId) {
    CHILD_WEIGHT_CARD -> WEIGHT_CARD
    CHILD_HEIGHT_CARD -> HEIGHT_SERIES
    else -> cardId
}

/**
 * The series [id] over [history] (newest first, as the history comes): weight, height, a segment's impedance, a
 * group by the profile's variant or one indicator. Measurements without a value are left out; null when the formula sets have
 * no such indicator or group.
 */
fun chartSeries(model: FormulaModel, history: List<MeasuredIndicators>, id: String, profile: Profile): ChartSeries? {
    val segment = SegmentSeries.of(id)
    val (unit, precision) = when {
        id == WEIGHT_CARD -> IndicatorUnit.KG to 2
        id == HEIGHT_SERIES -> IndicatorUnit.CM to 1
        segment != null -> IndicatorUnit.OHM to 1
        id in model.groups -> model.indicators.getValue(model.groups.getValue(id).first()).def.let { it.unit to it.precision }
        else -> model.indicators[id]?.def?.let { it.unit to it.precision } ?: return null
    }
    // Weight, height and segments are named by the app
    val title = when {
        id == WEIGHT_CARD || id == HEIGHT_SERIES || segment != null -> emptyMap()
        id in model.groups -> model.groupName(id, history.firstNotNullOfOrNull { it.results?.group(id)?.first } ?: model.groups.getValue(id).first())
        else -> model.indicators.getValue(id).def.name
    }
    val points = seriesPoints(model, history, id).map { ChartPoint(it.takenAt, inProfileUnits(it.value, unit, profile)) }
    return ChartSeries(id, title, unit, precision, points)
}

/**
 * The values of [id] over [history] (newest first), oldest first, in the indicator's own unit (kilograms,
 * centimetres, ohms): the weight, the height, a segment's impedance, a group by the profile's variant or one
 * indicator. Measurements without
 * a value are left out.
 */
fun seriesPoints(model: FormulaModel, history: List<MeasuredIndicators>, id: String): List<ChartPoint> =
    history.asReversed().mapNotNull { measured ->
        val segment = SegmentSeries.of(id)
        val value = when {
            id == WEIGHT_CARD -> measured.measurement.weightKg
            id == HEIGHT_SERIES -> measured.measurement.person?.heightCm
            segment != null -> segment.valueOf(measured)
            id in model.groups -> measured.results?.group(id)?.second
            else -> measured.results?.value(id)
        } ?: return@mapNotNull null
        ChartPoint(measured.measurement.takenAt, value)
    }

/** Kilograms and centimetres in the profile's units; stones as a fraction, so the axis is one number. */
fun inProfileUnits(value: Double, unit: IndicatorUnit, profile: Profile): Double = when (unit) {
    IndicatorUnit.KG -> profile.weightUnit.fromKilograms(value)
    IndicatorUnit.CM -> profile.lengthUnit.fromCentimetres(value)
    else -> value
}

/** A unit as the chart groups series by: weight in stones and in pounds go on different axes. */
data class ChartUnit(val unit: IndicatorUnit, val weightUnit: WeightUnit?)

fun ChartSeries.chartUnit(profile: Profile) = ChartUnit(unit, profile.weightUnit.takeIf { unit == IndicatorUnit.KG })

/** The points from [since] on; all of them when it is null. */
fun List<ChartPoint>.since(since: Instant?): List<ChartPoint> = if (since == null) this else filter { it.takenAt >= since }

/**
 * The trailing average at each point over [days] up to it, the weighings before the period included, so the
 * line starts as it would have gone on.
 */
fun movingAverage(points: List<ChartPoint>, days: Long): List<ChartPoint> {
    val window = Duration.ofDays(days)
    var start = 0
    var sum = 0.0
    return points.mapIndexed { i, point ->
        sum += point.value
        while (points[start].takenAt <= point.takenAt - window) sum -= points[start++].value
        ChartPoint(point.takenAt, sum / (i - start + 1))
    }
}

/**
 * The zones of [id] for the person at [newest], in the profile's units; empty without zones and for children,
 * whose norms are percentiles instead. A group takes the zones of the variant that gave the newest value.
 */
fun zoneBands(model: FormulaModel, newest: MeasuredIndicators?, id: String, profile: Profile): List<ZoneBand> {
    if (newest == null || newest.growth != null) return emptyList()
    val results = newest.results ?: return emptyList()
    val indicator = if (id in model.groups) results.group(id)?.first ?: model.groups.getValue(id).first() else id
    val unit = model.indicators[indicator]?.def?.unit ?: return emptyList()
    val rule = results.zoneRule(indicator) ?: return emptyList()
    val bounds = rule.bounds.map { inProfileUnits(it, unit, profile) }
    return rule.levels.mapIndexed { i, level -> ZoneBand(bounds.getOrNull(i - 1), bounds.getOrNull(i), level) }
}

/** What can be put on a chart: the weight first, then everything that can be a card, then the segments. */
fun chartChoices(model: FormulaModel): List<String> =
    listOf(WEIGHT_CARD) + cardChoices(model).map { it.id } + SegmentSeries.all.map { it.id }

/**
 * The values an axis spans: the lowest and highest with a margin, so the line does not touch the edges, and
 * never from zero, which would flatten a weight that changes by a kilogram into a straight line.
 */
fun axisRange(values: Collection<Double>): ClosedFloatingPointRange<Double> {
    val min = values.min()
    val max = values.max()
    val margin = if (max > min) (max - min) * 0.15 else maxOf(kotlin.math.abs(max) * 0.02, 1.0)
    return (min - margin)..(max + margin)
}

/** A time as the chart's x value: days since the epoch, with the time of day as a fraction. */
fun Instant.chartX(): Double = epochSecond / SECONDS_PER_DAY

fun chartInstant(x: Double): Instant = Instant.ofEpochSecond(Math.round(x * SECONDS_PER_DAY))

private const val SECONDS_PER_DAY = 86_400.0
