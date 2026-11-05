package dev.kinash.smartscales.ui.segments

import android.content.Context
import dev.kinash.smartscales.R
import dev.kinash.smartscales.core.formula.SegmentInputs
import dev.kinash.smartscales.core.model.Segment
import dev.kinash.smartscales.indicators.MeasuredIndicators

/** A segment's impedance at one of the two frequencies an 8-electrode scale may send. */
data class SegmentSeries(val segment: Segment, val frequencyIndex: Int) {
    /** The series id on charts and in comparisons, and the formula input it comes from. */
    val id: String get() = SegmentInputs.name(segment.channel - 1, frequencyIndex)

    /** The value in ohms with the scale's calibration, as the formulas see it; null without one. */
    fun valueOf(measured: MeasuredIndicators): Double? = measured.inputs?.get(id) as? Double

    companion object {
        val all: List<SegmentSeries> = (0..1).flatMap { frequency -> Segment.entries.map { SegmentSeries(it, frequency) } }

        /** The series [id] is, or null for an indicator, a group or the weight. */
        fun of(id: String): SegmentSeries? = all.firstOrNull { it.id == id }
    }
}

/** Whether [measured] has the impedances of an 8-electrode scale. */
fun hasSegments(measured: MeasuredIndicators): Boolean = SegmentSeries.all.any { it.valueOf(measured) != null }

/** The frequencies [measured] has segments at, 0 for the first. */
fun segmentFrequencies(measured: MeasuredIndicators): List<Int> =
    SegmentSeries.all.filter { it.valueOf(measured) != null }.map { it.frequencyIndex }.distinct().sorted()

fun Segment.title(): Int = when (this) {
    Segment.RIGHT_ARM -> R.string.segment_right_arm
    Segment.LEFT_ARM -> R.string.segment_left_arm
    Segment.RIGHT_LEG -> R.string.segment_right_leg
    Segment.LEFT_LEG -> R.string.segment_left_leg
    Segment.TRUNK -> R.string.segment_trunk
}

/** "Right arm", and "Right arm, frequency 2" for the second frequency, whose kilohertz the scales do not say. */
fun SegmentSeries.title(context: Context): String {
    val name = context.getString(segment.title())
    return if (frequencyIndex == 0) name else context.getString(R.string.segment_frequency, name, frequencyIndex + 1)
}
