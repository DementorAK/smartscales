package dev.kinash.smartscales.core.data

import dev.kinash.smartscales.core.data.db.entity.ImpedanceEntity
import dev.kinash.smartscales.core.data.db.entity.MeasurementEntity
import dev.kinash.smartscales.core.data.db.entity.MeasurementWithDetails
import dev.kinash.smartscales.core.model.Impedance
import dev.kinash.smartscales.core.model.MeasurementSource
import dev.kinash.smartscales.core.model.PersonSnapshot
import dev.kinash.smartscales.core.model.RawMeasurement
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * A stored measurement with everything attached to it.
 *
 * @property profileId null means the inbox; only inbox measurements may have no [person] snapshot.
 * @property importedValues indicator values from an import without impedance, shown as is.
 * @property tags tag names, sorted.
 */
data class Measurement(
    val id: Long = 0,
    val profileId: Long?,
    val takenAt: Instant,
    val zone: ZoneId,
    val source: MeasurementSource,
    val weightKg: Double,
    val person: PersonSnapshot?,
    val impedances: List<Impedance> = emptyList(),
    val heartRateBpm: Int? = null,
    val manualValues: Map<String, Double> = emptyMap(),
    val importedValues: Map<String, Double> = emptyMap(),
    val scaleBindingId: Long? = null,
    val scaleProperties: Int? = null,
    val note: String? = null,
    val tags: List<String> = emptyList(),
) {
    init {
        require(weightKg > 0) { "Weight must be positive: $weightKg" }
        require(profileId == null || person != null) { "A measurement assigned to a profile needs a person snapshot" }
    }

    /** Calendar date of the measurement where it was taken. */
    val localDate: LocalDate get() = takenAt.atZone(zone).toLocalDate()

    /** False when the snapshot birth date is after the measurement date; such a measurement must not be saved. */
    val isAfterBirth: Boolean get() = person == null || !localDate.isBefore(person.birthDate)

    /** Input for formulas, or null in the inbox where the person is not known yet. */
    fun toRawMeasurement(): RawMeasurement? = person?.let {
        RawMeasurement(takenAt, zone, source, weightKg, it, impedances, heartRateBpm, manualValues)
    }
}

internal fun MeasurementWithDetails.toMeasurement(): Measurement {
    val m = measurement
    val person = if (m.heightCm != null && m.sex != null && m.birthDate != null) {
        PersonSnapshot(m.heightCm, m.sex, m.birthDate)
    } else {
        null
    }
    return Measurement(
        id = m.id,
        profileId = m.profileId,
        takenAt = m.takenAt,
        zone = m.zone,
        source = m.source,
        weightKg = m.weightKg,
        person = person,
        impedances = impedances
            .sortedWith(compareBy({ it.channel }, { it.frequencyIndex }))
            .map { Impedance(it.channel, it.ohms, it.frequencyIndex, it.frequencyKhz) },
        heartRateBpm = m.heartRateBpm,
        manualValues = manualValues.associate { it.fieldId to it.value },
        importedValues = importedValues.associate { it.indicatorId to it.value },
        scaleBindingId = m.scaleBindingId,
        scaleProperties = m.scaleProperties,
        note = m.note,
        tags = tags.sortedBy { it.normalizedName }.map { it.name },
    )
}

internal fun Measurement.toEntity() = MeasurementEntity(
    id = id,
    profileId = profileId,
    takenAt = takenAt,
    zone = zone,
    source = source,
    weightKg = weightKg,
    heartRateBpm = heartRateBpm,
    heightCm = person?.heightCm,
    sex = person?.sex,
    birthDate = person?.birthDate,
    scaleBindingId = scaleBindingId,
    scaleProperties = scaleProperties,
    note = note,
)

internal fun Impedance.toEntity(measurementId: Long) =
    ImpedanceEntity(measurementId, channel, frequencyIndex, frequencyKhz, ohms)
