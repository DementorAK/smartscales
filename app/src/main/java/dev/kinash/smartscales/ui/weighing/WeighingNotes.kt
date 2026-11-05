package dev.kinash.smartscales.ui.weighing

import dev.kinash.smartscales.core.data.Goal
import dev.kinash.smartscales.core.formula.FormulaModel
import dev.kinash.smartscales.core.formula.IndicatorUnit
import dev.kinash.smartscales.core.formula.LocalizedText
import dev.kinash.smartscales.indicators.MeasuredIndicators
import dev.kinash.smartscales.ui.chart.seriesPoints
import dev.kinash.smartscales.ui.goal.goalProgress
import dev.kinash.smartscales.ui.history.WEIGHT_CARD
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlin.math.abs

/** A goal this weighing reached: [title] is empty for the weight, whose name comes from the app. */
data class ReachedGoal(val id: String, val title: LocalizedText, val target: Double, val unit: IndicatorUnit, val precision: Int)

/**
 * What the weighing screen says about a weighing just saved for a profile (requirements §6.2): the change since
 * the weighing before, the goals it reached, and how far it is from the usual weight when that is too far.
 */
data class WeighingNotes(
    /** Kilograms since the profile's weighing before this one; null for the first. */
    val change: Double?,
    /**
     * The date of that weighing where it was taken, when it is more than [USUAL_DAYS] days before this one: a
     * change since half a year ago is told with its date, so it does not read as a sudden one.
     */
    val since: LocalDate?,
    val reached: List<ReachedGoal>,
    /**
     * Kilograms from the usual weight of the last [USUAL_DAYS] days, when more than the match threshold: a
     * weighing of someone else, or of the scale on a carpet.
     */
    val deviation: Double?,
)

/**
 * The notes for [measurementId] in [history] (the profile's, newest first, the weighing in it). A goal counts as
 * reached by this weighing when it is reached with it and was not with the weighings before: the next weighing at
 * the goal says nothing, so the message is not repeated each morning. Children have no goals (requirements §6.3).
 */
fun weighingNotes(
    model: FormulaModel,
    history: List<MeasuredIndicators>,
    measurementId: Long,
    goals: Map<String, Goal>,
    thresholdKg: Double,
): WeighingNotes? {
    val index = history.indexOfFirst { it.measurement.id == measurementId }
    if (index < 0) return null
    val measurement = history[index].measurement
    val before = history.drop(index + 1)
    val upTo = history.drop(index)
    val now = measurement.takenAt
    val reached = if (history[index].growth != null) {
        emptyList()
    } else {
        goals.values.mapNotNull { goal ->
            val reachedNow = goalProgress(goal, seriesPoints(model, upTo, goal.indicatorId), now)?.achieved == true
            val reachedBefore = goalProgress(goal, seriesPoints(model, before, goal.indicatorId), now)?.achieved == true
            if (!reachedNow || reachedBefore) return@mapNotNull null
            reachedGoal(model, goal, history[index])
        }
    }
    val usual = usualWeight(before.map { it.measurement.takenAt to it.measurement.weightKg }, now)
    val deviation = usual?.let { measurement.weightKg - it }?.takeIf { abs(it) > thresholdKg }
    val previous = before.firstOrNull()?.measurement
    val since = previous?.takeIf { it.takenAt < now - Duration.ofDays(USUAL_DAYS) }?.let { it.takenAt.atZone(it.zone).toLocalDate() }
    return WeighingNotes(previous?.let { measurement.weightKg - it.weightKg }, since, reached, deviation)
}

/** A group is named by the variant that gave this weighing its value, as the card on the main screen is. */
private fun reachedGoal(model: FormulaModel, goal: Goal, measured: MeasuredIndicators): ReachedGoal? {
    val id = goal.indicatorId
    return when (id) {
        WEIGHT_CARD -> ReachedGoal(id, emptyMap(), goal.target, IndicatorUnit.KG, 1)
        in model.groups -> (measured.results?.group(id)?.first ?: model.groups.getValue(id).first()).let { variant ->
            val def = model.indicators.getValue(variant).def
            ReachedGoal(id, model.groupName(id, variant), goal.target, def.unit, def.precision)
        }
        else -> model.indicators[id]?.def?.let { ReachedGoal(id, it.name, goal.target, it.unit, it.precision) }
    }
}

/**
 * The median weight of the weighings in the [USUAL_DAYS] days before [at]: one odd morning moves a median
 * little, and a weighing months ago says little of today. Null without any.
 */
fun usualWeight(weighings: List<Pair<Instant, Double>>, at: Instant): Double? {
    val recent = weighings.filter { (time, _) -> time < at && time >= at - Duration.ofDays(USUAL_DAYS) }.map { it.second }.sorted()
    if (recent.isEmpty()) return null
    val middle = recent.size / 2
    return if (recent.size % 2 == 1) recent[middle] else (recent[middle - 1] + recent[middle]) / 2
}

const val USUAL_DAYS = 14L
