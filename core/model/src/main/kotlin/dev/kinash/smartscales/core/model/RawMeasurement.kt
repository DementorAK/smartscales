package dev.kinash.smartscales.core.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class MeasurementSource {
    SCALE,
    MANUAL,
    IMPORT,
}

/** Identifiers of built-in manual input fields; formula sets may declare more. */
object ManualFields {
    const val WAIST_CM = "waist"
    const val HIP_CM = "hip"
    const val NECK_CM = "neck"
}

/**
 * Raw data of one weighing: everything body composition indicators are computed from.
 * Indicators themselves are never stored, so changing formulas recalculates the whole history.
 *
 * Storage details (profile, scale binding, raw scale property byte, note, tags) belong to the
 * database entities in :core:data, not to this model.
 *
 * @property zone time zone where the measurement was taken; it fixes the local date, and with it the age,
 *   so a weighing near midnight or on a trip keeps the same age after the phone's zone changes.
 * @property manualValues manual input fields by identifier (see [ManualFields]); lengths in centimetres.
 */
data class RawMeasurement(
    val takenAt: Instant,
    val zone: ZoneId,
    val source: MeasurementSource,
    val weightKg: Double,
    val person: PersonSnapshot,
    val impedances: List<Impedance> = emptyList(),
    val heartRateBpm: Int? = null,
    val manualValues: Map<String, Double> = emptyMap(),
) {
    init {
        require(weightKg > 0) { "Weight must be positive: $weightKg" }
        require(impedances.size <= Impedance.MAX_PER_MEASUREMENT) { "Too many impedances: ${impedances.size}" }
        require(impedances.distinctBy { it.channel to it.frequencyIndex }.size == impedances.size) {
            "Duplicate impedance channel and frequency index"
        }
        require(heartRateBpm == null || heartRateBpm > 0) { "Heart rate must be positive: $heartRateBpm" }
    }

    /** Local date of the measurement in its own time zone. */
    val localDate: LocalDate
        get() = takenAt.atZone(zone).toLocalDate()

    /** Completed years on the measurement date. */
    val ageYears: Int
        get() = person.ageYearsAt(localDate)

    /** Age in fractional months on the measurement date. */
    val ageMonths: Double
        get() = person.ageMonthsAt(localDate)

    /** Foot-to-foot impedance of a 4-electrode scale, if any. */
    val footToFootImpedance: Impedance?
        get() = impedances.firstOrNull { it.channel == Impedance.FOOT_TO_FOOT }
}
