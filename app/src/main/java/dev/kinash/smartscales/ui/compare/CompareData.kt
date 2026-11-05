package dev.kinash.smartscales.ui.compare

import dev.kinash.smartscales.core.data.Measurement
import dev.kinash.smartscales.core.formula.FormulaModel
import dev.kinash.smartscales.core.formula.IndicatorUnit
import dev.kinash.smartscales.core.formula.LocalizedText
import dev.kinash.smartscales.core.formula.ZoneLevel
import dev.kinash.smartscales.indicators.MeasuredIndicators
import dev.kinash.smartscales.ui.chart.HEIGHT_SERIES
import dev.kinash.smartscales.ui.history.FOLLOWS_GROUP
import dev.kinash.smartscales.ui.history.WEIGHT_CARD
import dev.kinash.smartscales.ui.history.cardChoices
import java.time.temporal.ChronoUnit

/** One value at both weighings, as it was worked out for each of them. */
data class CompareValue(
    val value: Double,
    val zone: ZoneLevel?,
    /** For a group, or an indicator that follows one: the group's variant behind the value. */
    val variant: String?,
)

/**
 * An indicator, a group or the weight at the [before] and [after] weighings, in kilograms and centimetres as
 * stored; either is null when that weighing has no value (no impedance, say). [title] is empty for weight and
 * height, whose names come from the app.
 */
data class CompareRow(
    val id: String,
    val title: LocalizedText,
    val unit: IndicatorUnit,
    val precision: Int,
    val before: CompareValue?,
    val after: CompareValue?,
) {
    /** The change, when both have a value. */
    val change: Double? get() = if (before != null && after != null) after.value - before.value else null

    /**
     * A group, or an indicator that follows one, whose two values come from different variants (one weighing with
     * an impedance and one without): the change is partly the change of method.
     */
    val methodsDiffer: Boolean get() = before?.variant != null && after?.variant != null && before.variant != after.variant
}

/**
 * Calendar days from [before] to [after], each on its own date where it was taken, as the history shows them:
 * 22:00 and 07:00 the next morning are a day apart, and a week less an hour is still a week.
 */
fun daysApart(before: Measurement, after: Measurement): Int =
    ChronoUnit.DAYS.between(before.takenAt.atZone(before.zone).toLocalDate(), after.takenAt.atZone(after.zone).toLocalDate()).toInt()

/** A child's values: weight, height and BMI, as on the child's cards; body composition is not for children. */
val CHILD_COMPARE = listOf(WEIGHT_CARD, HEIGHT_SERIES, "bmi")

/**
 * What a comparison shows, in order: the weight, the profile's [cards] as on the main screen, then every other
 * group and indicator of the formula sets that is not hidden. A group's variants are left out unless the profile
 * has one as a card: the group's value is the one the person chose.
 */
fun compareIds(model: FormulaModel, cards: List<String>): List<String> {
    val variants = model.groups.filterValues { it.size > 1 }.values.flatten().toSet()
    val rest = cardChoices(model).map { it.id }.filter { it !in variants }
    return (listOf(WEIGHT_CARD) + cards.filter { it !in model.hidden } + rest).distinct()
}

/**
 * The rows of [ids] for [before] and [after]; a row neither weighing has a value for, or that the formula sets no
 * longer have, is left out.
 */
fun compareRows(model: FormulaModel, before: MeasuredIndicators, after: MeasuredIndicators, ids: List<String>): List<CompareRow> =
    ids.mapNotNull { id ->
        val (unit, precision) = when (id) {
            WEIGHT_CARD -> IndicatorUnit.KG to 2
            HEIGHT_SERIES -> IndicatorUnit.CM to 1
            in model.groups -> model.indicators.getValue(model.groups.getValue(id).first()).def.let { it.unit to it.precision }
            else -> model.indicators[id]?.def?.let { it.unit to it.precision } ?: return@mapNotNull null
        }
        val a = valueOf(model, before, id)
        val b = valueOf(model, after, id)
        if (a == null && b == null) return@mapNotNull null
        val title = when (id) {
            WEIGHT_CARD, HEIGHT_SERIES -> emptyMap()
            in model.groups -> model.groupName(id, b?.variant ?: a?.variant ?: model.groups.getValue(id).first())
            else -> model.indicators.getValue(id).def.name
        }
        CompareRow(id, title, unit, precision, a, b)
    }

private fun valueOf(model: FormulaModel, measured: MeasuredIndicators, id: String): CompareValue? {
    val results = measured.results
    // Children's norms are percentiles, not the zones of the formula sets, and body composition is not for children
    val zones = measured.growth == null
    if (!zones && id !in CHILD_COMPARE) return null
    return when (id) {
        WEIGHT_CARD -> CompareValue(measured.measurement.weightKg, null, null)
        HEIGHT_SERIES -> measured.measurement.person?.heightCm?.let { CompareValue(it, null, null) }
        in model.groups -> results?.group(id)?.let { (variant, value) ->
            CompareValue(value, results.zone(variant)?.level?.takeIf { zones }, variant)
        }
        else -> results?.value(id)?.let {
            CompareValue(it, results.zone(id)?.level?.takeIf { zones }, FOLLOWS_GROUP[id]?.let { group -> results.group(group)?.first })
        }
    }
}
