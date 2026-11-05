package dev.kinash.smartscales.core.data.repository

import androidx.room.withTransaction
import dev.kinash.smartscales.core.data.Measurement
import dev.kinash.smartscales.core.data.Profile
import dev.kinash.smartscales.core.data.db.SmartScalesDatabase
import dev.kinash.smartscales.core.data.db.entity.ImportedValueEntity
import dev.kinash.smartscales.core.data.db.entity.ManualValueEntity
import dev.kinash.smartscales.core.data.db.entity.MeasurementTagEntity
import dev.kinash.smartscales.core.data.db.entity.TagEntity
import dev.kinash.smartscales.core.data.normalizeTagName
import dev.kinash.smartscales.core.data.toEntity
import dev.kinash.smartscales.core.data.toMeasurement
import dev.kinash.smartscales.core.data.toProfile
import dev.kinash.smartscales.core.model.ManualFields
import dev.kinash.smartscales.core.model.MeasurementSource
import dev.kinash.smartscales.core.model.PersonSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MeasurementRepository @Inject constructor(
    private val database: SmartScalesDatabase,
) {
    private val dao = database.measurementDao()

    /** Measurements of the profile, newest first. */
    fun observeHistory(profileId: Long): Flow<List<Measurement>> =
        dao.observeForProfile(profileId).map { list -> list.map { it.toMeasurement() } }

    /** Weighings from the scale history that are not assigned to a profile yet, newest first. */
    fun observeInbox(): Flow<List<Measurement>> = dao.observeInbox().map { list -> list.map { it.toMeasurement() } }

    fun observeInboxCount(): Flow<Int> = dao.observeInboxCount()

    /**
     * Placeholder impedance values by scale (requirements §2.2): a foot-to-foot value is a placeholder when the
     * same scale sent exactly that value in at least [PLACEHOLDER_REPEATS] weighings. A body does not give the
     * same impedance to a tenth of an ohm time after time, while a scale that does not measure sends its constant
     * (600.0 Ω on the author's scale) every time. Each value is judged on its own: a rare other value does not
     * make the constant real, and two equal values in a row on a real scale do not hide its impedance.
     */
    fun observePlaceholderImpedances(): Flow<Map<Long, Set<Double>>> =
        dao.observeRepeatedImpedances(PLACEHOLDER_REPEATS).map { rows ->
            rows.groupBy({ it.scaleBindingId }, { it.ohms }).mapValues { it.value.toSet() }
        }

    fun observeTags(): Flow<List<String>> = dao.observeTags().map { tags -> tags.map { it.name } }

    suspend fun get(id: Long): Measurement? = dao.get(id)?.toMeasurement()

    /** Weight of each profile's latest measurement, by profile id; profiles without measurements are absent. */
    suspend fun latestWeights(): Map<Long, Double> = dao.latestWeights().associate { it.profileId to it.weightKg }

    /** The newest weighing from this scale, in any profile or the inbox. */
    suspend fun latestFromScale(bindingId: Long): Measurement? = dao.latestFromScale(bindingId)?.toMeasurement()

    /**
     * Whether this scale's weighing taken at [takenAt] is stored already, in any profile or the inbox: a scale
     * may send the records it kept again on the next connection.
     */
    suspend fun existsFromScale(bindingId: Long, takenAt: Instant): Boolean = dao.existsFromScale(bindingId, takenAt)

    /** Takes a weighing away from its profile ("not me") and leaves it in the inbox without a snapshot. */
    suspend fun moveToInbox(id: Long) = dao.moveToInbox(id)

    /**
     * Saves a new measurement with its impedances, fields and tags; unknown tags are created. A weighing from the
     * scale for a profile takes the profile's circumferences; a manual entry has them filled in its form already,
     * where the person may have cleared one on purpose.
     */
    suspend fun add(measurement: Measurement): Long = database.withTransaction {
        requireAfterBirth(measurement)
        val profileId = measurement.profileId
        val saved = if (measurement.source == MeasurementSource.SCALE && profileId != null) {
            measurement.copy(manualValues = profileCircumferences(profileId) + measurement.manualValues)
        } else {
            measurement
        }
        val id = dao.insert(saved.copy(id = 0).toEntity())
        insertDetails(id, saved)
        id
    }

    /** Replaces the stored measurement and everything attached to it. */
    suspend fun update(measurement: Measurement) {
        require(measurement.id != 0L) { "Measurement is not saved yet" }
        requireAfterBirth(measurement)
        database.withTransaction {
            dao.update(measurement.toEntity())
            dao.deleteImpedances(measurement.id)
            dao.deleteManualValues(measurement.id)
            dao.deleteImportedValues(measurement.id)
            dao.deleteTagLinks(measurement.id)
            insertDetails(measurement.id, measurement)
        }
    }

    suspend fun delete(id: Long) = dao.delete(id)

    /**
     * Moves a measurement to another profile ("not me", manual transfer, sorting the inbox).
     * The snapshot is taken from the target profile: sex and birth date from the profile, height from its
     * measurement closest in time, or from the profile when it has none, unless [heightCm] is given.
     * The target person must be born by the measurement date (see [Profile.isBornBy]).
     * A weighing from the scale takes the target profile's circumferences too: it got those of the person it
     * was taken for by mistake, or none in the inbox. A manual entry keeps the circumferences typed for it.
     */
    suspend fun transfer(measurementId: Long, targetProfileId: Long, heightCm: Double? = null) {
        database.withTransaction {
            val source = requireNotNull(dao.get(measurementId)) { "No measurement $measurementId" }.measurement.source
            move(measurementId, targetProfileId, heightCm, takeCircumferences = source == MeasurementSource.SCALE)
        }
    }

    private suspend fun move(measurementId: Long, targetProfileId: Long, heightCm: Double?, takeCircumferences: Boolean) {
        database.withTransaction {
            val details = requireNotNull(dao.get(measurementId)) { "No measurement $measurementId" }
            val profile = requireNotNull(database.profileDao().get(targetProfileId)) { "No profile $targetProfileId" }
            val m = details.measurement
            require(profile.toProfile().isBornBy(m.takenAt.atZone(m.zone).toLocalDate())) {
                "Measurement $measurementId is dated before the birth date of profile $targetProfileId"
            }
            val height = heightCm
                ?: dao.nearestHeight(targetProfileId, m.takenAt, excludeId = measurementId)
                ?: profile.heightCm
            val person = PersonSnapshot(height, profile.sex, profile.birthDate)
            dao.update(
                m.copy(
                    profileId = targetProfileId,
                    heightCm = person.heightCm,
                    sex = person.sex,
                    birthDate = person.birthDate,
                ),
            )
            if (takeCircumferences) {
                val others = details.manualValues.filter { it.fieldId !in ManualFields.CIRCUMFERENCES }
                val taken = profileCircumferences(targetProfileId).map { (field, value) -> ManualValueEntity(measurementId, field, value) }
                dao.deleteManualValues(measurementId)
                dao.insertManualValues(others + taken)
            }
        }
    }

    private suspend fun profileCircumferences(profileId: Long): Map<String, Double> =
        database.profileDao().getManualValues(profileId)
            .filter { it.fieldId in ManualFields.CIRCUMFERENCES }
            .associate { it.fieldId to it.value }

    /**
     * Creates a profile with [createProfile] and moves the inbox weighing [measurementId] into it, in one
     * transaction. A weighing sorted or deleted meanwhile stays where it is. Returns the new profile id.
     */
    suspend fun claimForNewProfile(measurementId: Long, createProfile: suspend () -> Long): Long =
        database.withTransaction {
            val profileId = createProfile()
            val weighing = dao.get(measurementId)?.measurement
            if (weighing != null && weighing.profileId == null) transfer(measurementId, profileId)
            profileId
        }

    /**
     * Saves the edited [measurement] and moves it to [targetProfileId] in one transaction. Circumferences in the
     * form stay as edited, since the person saw them while choosing where the measurement goes; a form without
     * any, such as a weighing from the inbox, gets the target profile's, as a weighing sorted with "it's me" does.
     */
    suspend fun updateAndTransfer(measurement: Measurement, targetProfileId: Long) {
        database.withTransaction {
            update(measurement)
            val hasCircumferences = measurement.manualValues.keys.any { it in ManualFields.CIRCUMFERENCES }
            move(measurement.id, targetProfileId, heightCm = null, takeCircumferences = !hasCircumferences)
        }
    }

    private fun requireAfterBirth(measurement: Measurement) {
        require(measurement.isAfterBirth) { "Measurement is dated before the birth date in its snapshot" }
    }

    private suspend fun insertDetails(id: Long, measurement: Measurement) {
        dao.insertImpedances(measurement.impedances.map { it.toEntity(id) })
        dao.insertManualValues(measurement.manualValues.map { (field, value) -> ManualValueEntity(id, field, value) })
        dao.insertImportedValues(
            measurement.importedValues.map { (indicator, value) -> ImportedValueEntity(id, indicator, value) },
        )
        val tagIds = measurement.tags
            .map(String::trim)
            .filter(String::isNotEmpty)
            .map { name -> dao.getTag(normalizeTagName(name))?.id ?: dao.insertTag(TagEntity(name = name)) }
        dao.insertTagLinks(tagIds.map { MeasurementTagEntity(id, it) })
    }

    companion object {
        /** Weighings with the same impedance that make it a placeholder: the first two still count as measured. */
        const val PLACEHOLDER_REPEATS = 3
    }
}
