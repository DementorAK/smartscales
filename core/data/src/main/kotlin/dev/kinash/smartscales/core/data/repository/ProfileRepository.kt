package dev.kinash.smartscales.core.data.repository

import androidx.room.withTransaction
import dev.kinash.smartscales.core.data.Profile
import dev.kinash.smartscales.core.data.db.SmartScalesDatabase
import dev.kinash.smartscales.core.data.db.entity.ProfileGoalEntity
import dev.kinash.smartscales.core.data.toEntity
import dev.kinash.smartscales.core.data.toProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProfileRepository @Inject constructor(
    private val database: SmartScalesDatabase,
    private val clock: Clock,
) {
    private val dao = database.profileDao()

    fun observeProfiles(): Flow<List<Profile>> = dao.observeAll().map { list -> list.map { it.toProfile() } }

    suspend fun get(id: Long): Profile? = dao.get(id)?.toProfile()

    suspend fun add(profile: Profile): Long = dao.insert(profile.copy(id = 0).toEntity())

    /**
     * Saves the profile. With [applyToPastMeasurements] its sex and birth date also replace the snapshot
     * in all its past measurements; heights in past measurements are never changed.
     * Ask the user first when [changesPastSnapshots] is true. Past measurements cannot take a birth date
     * later than their own date, so apply only when [countMeasurementsBefore] the new birth date is zero.
     */
    suspend fun update(profile: Profile, applyToPastMeasurements: Boolean) {
        database.withTransaction {
            dao.update(profile.toEntity())
            if (applyToPastMeasurements) {
                require(countMeasurementsBefore(profile.id, profile.birthDate) == 0) {
                    "Profile ${profile.id} has measurements dated before ${profile.birthDate}"
                }
                database.measurementDao().updatePersonForProfile(profile.id, profile.sex, profile.birthDate)
            }
        }
    }

    /** Target value of [indicatorId] (for example [WEIGHT_GOAL] in kilograms), or null when no goal is set. */
    suspend fun getGoal(profileId: Long, indicatorId: String): Double? =
        dao.getGoal(profileId, indicatorId)?.targetValue

    /** Sets the goal, or removes it when [target] is null. */
    suspend fun setGoal(profileId: Long, indicatorId: String, target: Double?) {
        if (target == null) {
            dao.deleteGoal(profileId, indicatorId)
        } else if (dao.getGoal(profileId, indicatorId)?.targetValue != target) {
            dao.upsertGoal(ProfileGoalEntity(profileId, indicatorId, target, clock.instant()))
        }
    }

    /** Number of measurements [delete] would remove, for the confirmation dialog. */
    suspend fun countMeasurements(id: Long): Int = database.measurementDao().countForProfile(id)

    /** Number of the profile's measurements dated before [date] where they were taken. */
    suspend fun countMeasurementsBefore(id: Long, date: LocalDate): Int {
        // No zone starts the day later than the most negative offset, so the query returns every candidate
        val bound = date.atStartOfDay(ZoneOffset.MIN).toInstant()
        return database.measurementDao().takenBefore(id, bound).count { it.takenAt.atZone(it.zone).toLocalDate() < date }
    }

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
        /** Goal identifier for body weight; other goals use indicator identifiers from formula sets. */
        const val WEIGHT_GOAL = "weight"

        /** Whether saving [edited] over [stored] should ask "apply to past measurements?". */
        fun changesPastSnapshots(stored: Profile, edited: Profile): Boolean =
            stored.sex != edited.sex || stored.birthDate != edited.birthDate
    }
}
