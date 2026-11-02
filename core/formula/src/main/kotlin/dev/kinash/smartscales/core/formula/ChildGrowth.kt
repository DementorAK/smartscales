package dev.kinash.smartscales.core.formula

import dev.kinash.smartscales.core.model.Sex
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The CDC 2000 growth charts for ages 2 to 20 (BMI, weight and stature for age), a work of the U.S. government in
 * the public domain, kept in this module unchanged as CDC publishes them.
 */
object CdcGrowthCharts {
    val bmiForAge: GrowthChart by lazy { load("bmiagerev.csv") }
    val weightForAge: GrowthChart by lazy { load("wtage.csv") }
    val statureForAge: GrowthChart by lazy { load("statage.csv") }

    private fun load(name: String): GrowthChart {
        val text = CdcGrowthCharts::class.java.getResourceAsStream("cdc/$name")?.use { it.readBytes().decodeToString() }
            ?: error("CDC growth chart $name is missing")
        return GrowthChart.parseCdc(text)
    }
}

/**
 * A child's measurement against the CDC charts (requirements §3): percentiles of BMI, weight and height from 2 years
 * of age; younger children get weight and height without percentiles. From 18 years the adult indicators apply.
 */
data class ChildGrowth(
    val ageMonths: Double,
    val bmiPercentile: Double?,
    val weightPercentile: Double?,
    val heightPercentile: Double?,
) {
    /** CDC weight status by BMI-for-age: below the 5th percentile, to the 85th, to the 95th, and above. */
    val bmiLevel: ZoneLevel?
        get() = bmiPercentile?.let {
            when {
                it < UNDERWEIGHT -> ZoneLevel.LOW
                it < OVERWEIGHT -> ZoneLevel.NORMAL
                it < OBESITY -> ZoneLevel.ELEVATED
                else -> ZoneLevel.HIGH
            }
        }

    companion object {
        /** Adult indicators apply from this age. */
        const val ADULT_MONTHS = 18 * 12.0

        /** Percentiles start here: the charts for younger children describe recumbent length, not stature. */
        const val PERCENTILES_FROM_MONTHS = 24.0

        private const val UNDERWEIGHT = 5.0
        private const val OVERWEIGHT = 85.0
        private const val OBESITY = 95.0

        /**
         * Growth of a child of [sex] at [ageMonths], or null for adults. [bmi] is the formula set's BMI indicator
         * (null when it has no value) and [bmiPrecision] its shown decimals: the chart takes the BMI the card shows,
         * so a set that changes the BMI formula or its precision changes the percentile with it.
         */
        fun of(sex: Sex, ageMonths: Double, weightKg: Double, heightCm: Double, bmi: Double?, bmiPrecision: Int): ChildGrowth? {
            if (ageMonths >= ADULT_MONTHS) return null
            if (ageMonths < PERCENTILES_FROM_MONTHS) return ChildGrowth(ageMonths, null, null, null)
            val shownBmi = bmi?.let { BigDecimal(it.toString()).setScale(bmiPrecision, RoundingMode.HALF_UP).toDouble() }
            return ChildGrowth(
                ageMonths = ageMonths,
                bmiPercentile = shownBmi?.let { CdcGrowthCharts.bmiForAge.percentile(sex, ageMonths, it) },
                weightPercentile = CdcGrowthCharts.weightForAge.percentile(sex, ageMonths, weightKg),
                heightPercentile = CdcGrowthCharts.statureForAge.percentile(sex, ageMonths, heightCm),
            )
        }
    }
}
