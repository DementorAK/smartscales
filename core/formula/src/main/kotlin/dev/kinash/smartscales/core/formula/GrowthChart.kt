package dev.kinash.smartscales.core.formula

import dev.kinash.smartscales.core.model.Sex
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/** One row of an LMS growth chart: the Box-Cox power [l], median [m] and coefficient of variation [s] at an age. */
data class LmsRow(val ageMonths: Double, val l: Double, val m: Double, val s: Double)

/**
 * A growth reference by the LMS method (Cole 1990), as the CDC 2000 growth charts publish it: for each sex, rows of
 * L, M and S by age in months. A measurement's z-score is `((x/M)^L − 1) / (L·S)`, or `ln(x/M) / S` when L is 0;
 * L, M and S between two tabulated ages are interpolated linearly.
 */
class GrowthChart(private val rows: Map<Sex, List<LmsRow>>) {
    init {
        rows.values.forEach { list ->
            require(list.size >= 2) { "A growth chart needs at least two ages" }
            require(list.zipWithNext().all { (a, b) -> a.ageMonths < b.ageMonths }) { "Ages must go up" }
        }
    }

    /** The ages the chart covers for [sex], in months. */
    fun ageRange(sex: Sex): ClosedFloatingPointRange<Double>? =
        rows[sex]?.let { it.first().ageMonths..it.last().ageMonths }

    /** L, M and S at [ageMonths], or null outside the chart's ages. */
    fun lmsAt(sex: Sex, ageMonths: Double): LmsRow? {
        val list = rows[sex] ?: return null
        if (ageMonths < list.first().ageMonths || ageMonths > list.last().ageMonths) return null
        val upper = list.indexOfFirst { it.ageMonths >= ageMonths }
        val b = list[upper]
        if (b.ageMonths == ageMonths || upper == 0) return b.copy(ageMonths = ageMonths)
        val a = list[upper - 1]
        val t = (ageMonths - a.ageMonths) / (b.ageMonths - a.ageMonths)
        return LmsRow(ageMonths, a.l + (b.l - a.l) * t, a.m + (b.m - a.m) * t, a.s + (b.s - a.s) * t)
    }

    /** The z-score of [value] at [ageMonths], or null outside the chart's ages or for a value that is not positive. */
    fun zScore(sex: Sex, ageMonths: Double, value: Double): Double? {
        if (value <= 0) return null
        val (_, l, m, s) = lmsAt(sex, ageMonths) ?: return null
        return if (abs(l) < 1e-12) ln(value / m) / s else ((value / m).pow(l) - 1) / (l * s)
    }

    /** The percentile of [value], 0 to 100, or null where [zScore] is. */
    fun percentile(sex: Sex, ageMonths: Double, value: Double): Double? =
        zScore(sex, ageMonths, value)?.let { normalCdf(it) * 100 }

    /** The value at z-score [z]: the inverse of [zScore], for drawing percentile curves. */
    fun valueAt(sex: Sex, ageMonths: Double, z: Double): Double? {
        val (_, l, m, s) = lmsAt(sex, ageMonths) ?: return null
        return if (abs(l) < 1e-12) m * exp(s * z) else m * (1 + l * s * z).pow(1 / l)
    }

    companion object {
        /**
         * Reads a CDC 2000 LMS file (`bmiagerev.csv`, `wtage.csv`, `statage.csv`): columns `Sex` (1 male, 2 female),
         * `Agemos`, `L`, `M`, `S`, then percentile columns that are not needed. Lines that are not data, such as a
         * repeated header, are skipped.
         */
        fun parseCdc(text: String): GrowthChart {
            val rows = HashMap<Sex, MutableList<LmsRow>>()
            for (line in text.lineSequence()) {
                val cells = line.split(',').map { it.trim() }
                if (cells.size < 5) continue
                val sex = when (cells[0].toDoubleOrNull()?.toInt()) {
                    1 -> Sex.MALE
                    2 -> Sex.FEMALE
                    else -> continue
                }
                val numbers = cells.subList(1, 5).map { it.toDoubleOrNull() ?: Double.NaN }
                if (numbers.any { it.isNaN() }) continue
                rows.getOrPut(sex) { ArrayList() } += LmsRow(numbers[0], numbers[1], numbers[2], numbers[3])
            }
            return GrowthChart(rows.mapValues { (_, list) -> list.sortedBy { it.ageMonths } })
        }

        /** The standard normal distribution function, to 1e-7 (Abramowitz & Stegun 26.2.17 by way of erf 7.1.26). */
        fun normalCdf(z: Double): Double {
            val x = abs(z) / sqrt(2.0)
            val t = 1 / (1 + 0.3275911 * x)
            val poly = t * (0.254829592 + t * (-0.284496736 + t * (1.421413741 + t * (-1.453152027 + t * 1.061405429))))
            val erf = 1 - poly * exp(-x * x)
            return if (z >= 0) (1 + erf) / 2 else (1 - erf) / 2
        }
    }
}
