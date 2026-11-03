package dev.kinash.smartscales.indicators

import dev.kinash.smartscales.core.data.Measurement
import dev.kinash.smartscales.core.data.repository.MeasurementRepository
import dev.kinash.smartscales.core.data.repository.ProfileRepository
import dev.kinash.smartscales.core.data.repository.ScaleBindingRepository
import dev.kinash.smartscales.core.formula.BuiltinFormulas
import dev.kinash.smartscales.core.formula.ChildGrowth
import dev.kinash.smartscales.core.formula.FormulaModel
import dev.kinash.smartscales.core.formula.MeasurementInputs
import dev.kinash.smartscales.core.model.Impedance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import javax.inject.Inject
import javax.inject.Singleton

/** Whether a measurement's impedance went into the formulas. */
enum class ImpedanceStatus {
    MEASURED,

    /** The scale sent none, or the measurement was entered by hand. */
    MISSING,

    /** The scale sent the value it sends when it measures nothing (requirements §2.2). */
    PLACEHOLDER,
}

/**
 * A measurement with its indicators; [results] is null in the inbox, where the person is not known. [growth] is
 * the child's position on the CDC charts, for a child under 18; like the formulas, it is worked out when first asked.
 * [inputs] are the formula inputs, for working out other choices of variants, and [impedance] tells whether they
 * have an impedance.
 */
class MeasuredIndicators(
    val measurement: Measurement,
    val results: FormulaModel.Results?,
    val inputs: Map<String, Any?>? = null,
    /** The profile's variants the results were worked out with. */
    val primary: Map<String, String> = emptyMap(),
    val impedance: ImpedanceStatus = ImpedanceStatus.MISSING,
    private val model: FormulaModel? = null,
    growthOf: () -> ChildGrowth? = { null },
) {
    val growth: ChildGrowth? by lazy(growthOf)

    /**
     * The indicators as they would be with an impedance, when this measurement has none: an indicator with no
     * value in [results] but one here is missing only the impedance. The probe value is never shown.
     */
    val withImpedance: FormulaModel.Results? by lazy {
        if (impedance == ImpedanceStatus.MEASURED) return@lazy null
        val values = inputs ?: return@lazy null
        model?.evaluate(values + ("impedance" to PROBE_OHMS), primary)
    }

    private companion object {
        /** A typical foot-to-foot impedance of an adult. */
        const val PROBE_OHMS = 500.0
    }
}

/**
 * Indicators of measurements, computed from their raw data each time the data, the profile's choice of variants
 * or a scale's calibration changes; nothing computed is stored (requirements §4.1). Values are computed when
 * first asked for and remembered per measurement until the history changes, which is the cache: it is never
 * out of date, because a change of any input brings a new list.
 */
@Singleton
class Indicators @Inject constructor(
    private val measurements: MeasurementRepository,
    private val profiles: ProfileRepository,
    private val scales: ScaleBindingRepository,
) {
    /**
     * The built-in set; the person's own sets join in 0.7. A broken set is left out by
     * [FormulaModel.buildUsable], and the built-in one is checked by tests.
     */
    val model: FormulaModel by lazy { FormulaModel.buildUsable(listOf(BuiltinFormulas.layer)).model }

    /** The profile's history, newest first, each measurement with its indicators. */
    fun observeHistory(profileId: Long): Flow<List<MeasuredIndicators>> = combine(
        measurements.observeHistory(profileId),
        profiles.observeMainVariants(profileId),
        scales.observeAll(),
        measurements.observePlaceholderImpedances(),
    ) { history, primary, bindings, placeholders ->
        val factors = bindings.associate { it.id to it.impedanceFactor }
        history.map { measurement ->
            // A snapshot born after the measurement (old or imported data) gets no indicators instead of failing
            val raw = measurement.takeIf { it.isAfterBirth }?.toRawMeasurement()
            if (raw == null) return@map MeasuredIndicators(measurement, null)
            val scale = measurement.scaleBindingId
            // The same value the placeholder query and the formulas look at: foot to foot, first frequency
            val ohms = raw.impedances.firstOrNull { i -> i.channel == Impedance.FOOT_TO_FOOT && i.frequencyIndex == 0 }?.ohms
            val impedance = when {
                ohms == null -> ImpedanceStatus.MISSING
                scale != null && ohms in placeholders[scale].orEmpty() -> ImpedanceStatus.PLACEHOLDER
                else -> ImpedanceStatus.MEASURED
            }
            val inputs = MeasurementInputs.of(
                raw,
                impedanceFactor = scale?.let(factors::get) ?: 1.0,
                placeholder = impedance == ImpedanceStatus.PLACEHOLDER,
            )
            val results = model.evaluate(inputs, primary)
            MeasuredIndicators(measurement, results, inputs, primary, impedance, model) {
                val age = raw.ageMonthsOrNull ?: return@MeasuredIndicators null
                ChildGrowth.of(
                    raw.person.sex, age, raw.weightKg, raw.person.heightCm,
                    bmi = results.value("bmi"), bmiPrecision = model.indicators["bmi"]?.def?.precision ?: 1,
                )
            }
        }
    }.flowOn(Dispatchers.Default)
}
