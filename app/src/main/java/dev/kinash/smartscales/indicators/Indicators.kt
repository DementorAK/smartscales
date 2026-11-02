package dev.kinash.smartscales.indicators

import dev.kinash.smartscales.core.data.Measurement
import dev.kinash.smartscales.core.data.repository.MeasurementRepository
import dev.kinash.smartscales.core.data.repository.ProfileRepository
import dev.kinash.smartscales.core.data.repository.ScaleBindingRepository
import dev.kinash.smartscales.core.formula.BuiltinFormulas
import dev.kinash.smartscales.core.formula.FormulaModel
import dev.kinash.smartscales.core.formula.MeasurementInputs
import dev.kinash.smartscales.core.model.Impedance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import javax.inject.Inject
import javax.inject.Singleton

/** A measurement with its indicators; [results] is null in the inbox, where the person is not known. */
class MeasuredIndicators(val measurement: Measurement, val results: FormulaModel.Results?)

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
            val results = raw?.let {
                val scale = measurement.scaleBindingId
                // The same value the placeholder query and the formulas look at: foot to foot, first frequency
                val ohms = it.impedances.firstOrNull { i -> i.channel == Impedance.FOOT_TO_FOOT && i.frequencyIndex == 0 }?.ohms
                val inputs = MeasurementInputs.of(
                    it,
                    impedanceFactor = scale?.let(factors::get) ?: 1.0,
                    placeholder = scale != null && ohms != null && ohms in placeholders[scale].orEmpty(),
                )
                model.evaluate(inputs, primary)
            }
            MeasuredIndicators(measurement, results)
        }
    }.flowOn(Dispatchers.Default)
}
