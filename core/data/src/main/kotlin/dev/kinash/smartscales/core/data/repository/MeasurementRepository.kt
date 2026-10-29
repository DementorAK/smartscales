package dev.kinash.smartscales.core.data.repository

import androidx.room.withTransaction
import dev.kinash.smartscales.core.data.Measurement
import dev.kinash.smartscales.core.data.Profile
import dev.kinash.smartscales.core.data.db.SmartScalesDatabase
import dev.kinash.smartscales.core.data.db.entity.ImportedValueEntity
import dev.kinash.smartscales.core.data.db.entity.ManualValueEntity
import dev.kinash.smartscales.core.data.db.entity.MeasurementTagEntity
import dev.kinash.smartscales.core.data.db.entity.TagEntity
import dev.kinash.smartscales.core.data.db.entity.normalizeTagName
import dev.kinash.smartscales.core.data.toEntity
import dev.kinash.smartscales.core.data.toMeasurement
import dev.kinash.smartscales.core.data.toProfile
import dev.kinash.smartscales.core.model.PersonSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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

    fun observeTags(): Flow<List<String>> = dao.observeTags().map { tags -> tags.map { it.name } }

    suspend fun get(id: Long): Measurement? = dao.get(id)?.toMeasurement()

    /** Saves a new measurement with its impedances, fields and tags; unknown tags are created. */
    suspend fun add(measurement: Measurement): Long = database.withTransaction {
        requireAfterBirth(measurement)
        val id = dao.insert(measurement.copy(id = 0).toEntity())
        insertDetails(id, measurement)
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
     */
    suspend fun transfer(measurementId: Long, targetProfileId: Long, heightCm: Double? = null) {
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
        }
    }

    /** Saves the edited [measurement] and moves it to [targetProfileId] in one transaction. */
    suspend fun updateAndTransfer(measurement: Measurement, targetProfileId: Long) {
        database.withTransaction {
            update(measurement)
            transfer(measurement.id, targetProfileId)
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
}
