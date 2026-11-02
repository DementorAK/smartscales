package dev.kinash.smartscales.core.formula

import dev.kinash.smartscales.core.model.Impedance
import dev.kinash.smartscales.core.model.ManualFields
import dev.kinash.smartscales.core.model.RawMeasurement
import dev.kinash.smartscales.core.model.Sex

/** The values formulas see for one measurement, by input name ([StandardInputs] and inputs that sets declare). */
object MeasurementInputs {
    /** Stored manual field ids that formulas know under another name. */
    private val renamed = mapOf(ManualFields.HIP_CM to "hips")

    /**
     * Inputs of [raw]. The foot-to-foot impedance is multiplied by the scale's [impedanceFactor] and left out when
     * its value is a [placeholder]: a constant the scale sends instead of measuring. Age is in years with a fraction,
     * from the measurement's local date. Manual fields keep their ids, so inputs declared by formula sets find
     * their values.
     */
    fun of(raw: RawMeasurement, impedanceFactor: Double = 1.0, placeholder: Boolean = false): Map<String, Any?> {
        val values = HashMap<String, Any?>()
        for ((id, value) in raw.manualValues) values[renamed[id] ?: id] = value
        val male = raw.person.sex == Sex.MALE
        values["weight"] = raw.weightKg
        values["height"] = raw.person.heightCm
        // A birth date after the measurement (old or imported data) gives no age rather than an exception
        values["age"] = if (raw.localDate.isBefore(raw.person.birthDate)) null else raw.ageMonths / MONTHS_PER_YEAR
        values["sex"] = if (male) 1.0 else 0.0
        values["male"] = male
        values["female"] = !male
        values["impedance"] = if (placeholder) null else footToFoot(raw)?.let { it * impedanceFactor }
        values["heart_rate"] = raw.heartRateBpm?.toDouble()
        return values
    }

    /** The impedance the equations are made for: foot to foot, at the first (usually the only, 50 kHz) frequency. */
    private fun footToFoot(raw: RawMeasurement): Double? =
        raw.impedances.firstOrNull { it.channel == Impedance.FOOT_TO_FOOT && it.frequencyIndex == 0 }?.ohms

    private const val MONTHS_PER_YEAR = 12.0
}
