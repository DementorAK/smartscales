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

/** The growth charts a value was placed on. */
enum class GrowthReference {
    /** CDC 2000, built in: 2 to 20 years. */
    CDC,

    /** The WHO tables the person imported: the Child Growth Standards to 5 years and the Growth Reference to 19. */
    WHO,
}

/** A value on a growth chart: its percentile, 0 to 100, and z-score. */
data class GrowthPosition(val percentile: Double, val z: Double, val reference: GrowthReference)

/**
 * A child's weight status by BMI-for-age, by the cut-offs of the charts the BMI was placed on, and its zone level.
 * CDC: below the 5th percentile, to the 85th, to the 95th, and above. WHO from 5 years (Growth Reference 2007):
 * z-score below -3, below -2, above +1 and above +2. WHO under 5 (Child Growth Standards 2006): below -3, below -2,
 * above +1, above +2 and above +3.
 */
enum class BmiStatus(val level: ZoneLevel) {
    SEVERE_WASTING(ZoneLevel.VERY_LOW),
    WASTING(ZoneLevel.LOW),
    SEVERE_THINNESS(ZoneLevel.VERY_LOW),
    THINNESS(ZoneLevel.LOW),
    UNDERWEIGHT(ZoneLevel.LOW),
    NORMAL(ZoneLevel.NORMAL),
    RISK_OF_OVERWEIGHT(ZoneLevel.ELEVATED),
    OVERWEIGHT(ZoneLevel.ELEVATED),

    /** Under 5 overweight is above +2, past the risk of it. */
    OVERWEIGHT_UNDER_5(ZoneLevel.HIGH),
    OBESITY(ZoneLevel.HIGH),
    OBESITY_UNDER_5(ZoneLevel.VERY_HIGH),
    ;

    companion object {
        fun cdc(percentile: Double): BmiStatus = when {
            percentile < 5 -> UNDERWEIGHT
            percentile < 85 -> NORMAL
            percentile < 95 -> OVERWEIGHT
            else -> OBESITY
        }

        fun who(z: Double, ageMonths: Double): BmiStatus = if (ageMonths <= WHO_STANDARDS_MONTHS) {
            when {
                z < -3 -> SEVERE_WASTING
                z < -2 -> WASTING
                z > 3 -> OBESITY_UNDER_5
                z > 2 -> OVERWEIGHT_UNDER_5
                z > 1 -> RISK_OF_OVERWEIGHT
                else -> NORMAL
            }
        } else {
            when {
                z < -3 -> SEVERE_THINNESS
                z < -2 -> THINNESS
                z > 2 -> OBESITY
                z > 1 -> OVERWEIGHT
                else -> NORMAL
            }
        }

        /** The WHO Child Growth Standards go to 60 months; the Growth Reference starts at 61. */
        const val WHO_STANDARDS_MONTHS = 60.0
    }
}

/**
 * A child's measurement against the growth charts (requirements §3): percentiles and z-scores of BMI, weight and
 * height. On the CDC charts, from 2 years of age; younger children get weight and height without them. On the WHO
 * tables, where they cover the age, from birth; where they do not (weight after 10 years), on the CDC charts. From 18
 * years the adult indicators apply.
 */
data class ChildGrowth(
    val ageMonths: Double,
    val bmi: GrowthPosition?,
    val weight: GrowthPosition?,
    val height: GrowthPosition?,
) {
    val bmiPercentile: Double? get() = bmi?.percentile
    val weightPercentile: Double? get() = weight?.percentile
    val heightPercentile: Double? get() = height?.percentile

    /** Weight status by BMI-for-age, by the cut-offs of the charts the BMI is on. */
    val bmiStatus: BmiStatus?
        get() = bmi?.let { if (it.reference == GrowthReference.WHO) BmiStatus.who(it.z, ageMonths) else BmiStatus.cdc(it.percentile) }

    val bmiLevel: ZoneLevel? get() = bmiStatus?.level

    companion object {
        /** Adult indicators apply from this age. */
        const val ADULT_MONTHS = 18 * 12.0

        /** CDC percentiles start here: its charts for younger children describe recumbent length, not stature. */
        const val PERCENTILES_FROM_MONTHS = 24.0

        /**
         * Growth of a child of [sex] at [ageMonths], or null for adults. [bmi] is the formula set's BMI indicator
         * (null when it has no value) and [bmiPrecision] its shown decimals: the chart takes the BMI the card shows,
         * so a set that changes the BMI formula or its precision changes the percentile with it. [who] are the WHO
         * tables when the person chose them; without them the CDC charts.
         */
        fun of(
            sex: Sex,
            ageMonths: Double,
            weightKg: Double,
            heightCm: Double,
            bmi: Double?,
            bmiPrecision: Int,
            who: WhoGrowthCharts? = null,
        ): ChildGrowth? {
            if (ageMonths >= ADULT_MONTHS) return null
            fun place(indicator: GrowthIndicator, value: Double, cdc: GrowthChart): GrowthPosition? {
                who?.chart(indicator)?.zScore(sex, ageMonths, value)?.let { z ->
                    return GrowthPosition(GrowthChart.normalCdf(z) * 100, z, GrowthReference.WHO)
                }
                if (ageMonths < PERCENTILES_FROM_MONTHS) return null
                return cdc.zScore(sex, ageMonths, value)?.let { z -> GrowthPosition(GrowthChart.normalCdf(z) * 100, z, GrowthReference.CDC) }
            }
            val shownBmi = bmi?.let { BigDecimal(it.toString()).setScale(bmiPrecision, RoundingMode.HALF_UP).toDouble() }
            return ChildGrowth(
                ageMonths = ageMonths,
                bmi = shownBmi?.let { place(GrowthIndicator.BMI, it, CdcGrowthCharts.bmiForAge) },
                weight = place(GrowthIndicator.WEIGHT, weightKg, CdcGrowthCharts.weightForAge),
                height = place(GrowthIndicator.HEIGHT, heightCm, CdcGrowthCharts.statureForAge),
            )
        }
    }
}
