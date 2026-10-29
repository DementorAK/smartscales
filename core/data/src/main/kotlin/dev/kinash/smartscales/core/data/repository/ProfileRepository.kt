package dev.kinash.smartscales.core.data.repository

import androidx.room.withTransaction
import dev.kinash.smartscales.core.data.Profile
import dev.kinash.smartscales.core.data.db.SmartScalesDatabase
import dev.kinash.smartscales.core.data.toEntity
import dev.kinash.smartscales.core.data.toProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProfileRepository @Inject constructor(
    private val database: SmartScalesDatabase,
) {
    private val dao = database.profileDao()

    fun observeProfiles(): Flow<List<Profile>> = dao.observeAll().map { list -> list.map { it.toProfile() } }

    suspend fun get(id: Long): Profile? = dao.get(id)?.toProfile()

    suspend fun add(profile: Profile): Long = dao.insert(profile.copy(id = 0).toEntity())

    /**
     * Saves the profile. With [applyToPastMeasurements] its sex and birth date also replace the snapshot
     * in all its past measurements; heights in past measurements are never changed.
     * Ask the user first when [changesPastSnapshots] is true.
     */
    suspend fun update(profile: Profile, applyToPastMeasurements: Boolean) {
        database.withTransaction {
            dao.update(profile.toEntity())
            if (applyToPastMeasurements) {
                database.measurementDao().updatePersonForProfile(profile.id, profile.sex, profile.birthDate)
            }
        }
    }

    /** Number of measurements [delete] would remove, for the confirmation dialog. */
    suspend fun countMeasurements(id: Long): Int = database.measurementDao().countForProfile(id)

    /**
     * Deletes the profile with its whole history, goals and settings. This cannot be undone:
     * call it only after the user confirmed a warning that shows [countMeasurements].
     */
    suspend fun delete(id: Long) {
        database.withTransaction {
            database.measurementDao().deleteForProfile(id)
            dao.delete(id)
        }
    }

    companion object {
        /** Whether saving [edited] over [stored] should ask "apply to past measurements?". */
        fun changesPastSnapshots(stored: Profile, edited: Profile): Boolean =
            stored.sex != edited.sex || stored.birthDate != edited.birthDate
    }
}
