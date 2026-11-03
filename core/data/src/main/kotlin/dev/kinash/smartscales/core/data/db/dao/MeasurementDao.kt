package dev.kinash.smartscales.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import dev.kinash.smartscales.core.data.db.entity.ImpedanceEntity
import dev.kinash.smartscales.core.data.db.entity.ImportedValueEntity
import dev.kinash.smartscales.core.data.db.entity.ManualValueEntity
import dev.kinash.smartscales.core.data.db.entity.MeasurementEntity
import dev.kinash.smartscales.core.data.db.entity.MeasurementTagEntity
import dev.kinash.smartscales.core.data.db.entity.MeasurementWithDetails
import dev.kinash.smartscales.core.data.db.entity.TagEntity
import dev.kinash.smartscales.core.model.Sex
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Weight of a profile's latest measurement. */
data class ProfileWeight(val profileId: Long, val weightKg: Double)

/** A foot-to-foot impedance value that a scale sent in several weighings. */
data class RepeatedImpedance(val scaleBindingId: Long, val ohms: Double)

/** When and where a measurement was taken, for checks by local date. */
data class TakenAt(val takenAt: Instant, val zone: ZoneId)

@Dao
interface MeasurementDao {
    @Transaction
    @Query("SELECT * FROM measurement WHERE profileId = :profileId ORDER BY takenAt DESC, id DESC")
    fun observeForProfile(profileId: Long): Flow<List<MeasurementWithDetails>>

    @Transaction
    @Query("SELECT * FROM measurement WHERE profileId IS NULL ORDER BY takenAt DESC, id DESC")
    fun observeInbox(): Flow<List<MeasurementWithDetails>>

    @Transaction
    @Query("SELECT COUNT(*) FROM measurement WHERE profileId IS NULL")
    fun observeInboxCount(): Flow<Int>

    /** Foot-to-foot impedance values that a scale sent in at least [times] weighings. */
    @Query(
        """
        SELECT m.scaleBindingId AS scaleBindingId, i.ohms AS ohms
        FROM measurement m JOIN impedance i ON i.measurementId = m.id
        WHERE m.scaleBindingId IS NOT NULL AND i.channel = 0 AND i.frequencyIndex = 0
        GROUP BY m.scaleBindingId, i.ohms
        HAVING COUNT(*) >= :times
        """,
    )
    fun observeRepeatedImpedances(times: Int): Flow<List<RepeatedImpedance>>

    @Query(
        """
        SELECT profileId, weightKg FROM measurement AS m
        WHERE profileId IS NOT NULL AND id = (
            SELECT id FROM measurement WHERE profileId = m.profileId ORDER BY takenAt DESC, id DESC LIMIT 1
        )
        """,
    )
    suspend fun latestWeights(): List<ProfileWeight>

    @Query("UPDATE measurement SET profileId = NULL, heightCm = NULL, sex = NULL, birthDate = NULL WHERE id = :id")
    suspend fun moveToInbox(id: Long)

    @Query("SELECT * FROM measurement WHERE id = :id")
    suspend fun get(id: Long): MeasurementWithDetails?

    @Transaction
    @Query("SELECT * FROM measurement WHERE scaleBindingId = :bindingId ORDER BY takenAt DESC, id DESC LIMIT 1")
    suspend fun latestFromScale(bindingId: Long): MeasurementWithDetails?

    @Query("SELECT COUNT(*) > 0 FROM measurement WHERE scaleBindingId = :bindingId AND takenAt = :takenAt")
    suspend fun existsFromScale(bindingId: Long, takenAt: Instant): Boolean

    @Insert
    suspend fun insert(measurement: MeasurementEntity): Long

    @Update
    suspend fun update(measurement: MeasurementEntity)

    @Query("DELETE FROM measurement WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM measurement WHERE profileId = :profileId")
    suspend fun deleteForProfile(profileId: Long)

    @Query("SELECT COUNT(*) FROM measurement WHERE profileId = :profileId")
    suspend fun countForProfile(profileId: Long): Int

    @Insert
    suspend fun insertImpedances(impedances: List<ImpedanceEntity>)

    @Insert
    suspend fun insertManualValues(values: List<ManualValueEntity>)

    @Insert
    suspend fun insertImportedValues(values: List<ImportedValueEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTagLinks(links: List<MeasurementTagEntity>)

    @Query("DELETE FROM impedance WHERE measurementId = :measurementId")
    suspend fun deleteImpedances(measurementId: Long)

    @Query("DELETE FROM manual_value WHERE measurementId = :measurementId")
    suspend fun deleteManualValues(measurementId: Long)

    @Query("DELETE FROM imported_value WHERE measurementId = :measurementId")
    suspend fun deleteImportedValues(measurementId: Long)

    @Query("DELETE FROM measurement_tag WHERE measurementId = :measurementId")
    suspend fun deleteTagLinks(measurementId: Long)

    /** Replaces sex and birth date in all measurements of the profile; heights stay as they were. */
    @Query("UPDATE measurement SET sex = :sex, birthDate = :birthDate WHERE profileId = :profileId")
    suspend fun updatePersonForProfile(profileId: Long, sex: Sex, birthDate: LocalDate)

    @Query("SELECT takenAt, zone FROM measurement WHERE profileId = :profileId AND takenAt < :before")
    suspend fun takenBefore(profileId: Long, before: Instant): List<TakenAt>

    /** Height from the profile's measurement closest in time to [takenAt], not counting [excludeId]. */
    @Query(
        """
        SELECT heightCm FROM measurement
        WHERE profileId = :profileId AND heightCm IS NOT NULL AND id != :excludeId
        ORDER BY ABS(takenAt - :takenAt), id
        LIMIT 1
        """,
    )
    suspend fun nearestHeight(profileId: Long, takenAt: Instant, excludeId: Long): Double?

    @Query("SELECT * FROM tag ORDER BY normalizedName")
    fun observeTags(): Flow<List<TagEntity>>

    @Query("SELECT * FROM tag WHERE normalizedName = :normalizedName")
    suspend fun getTag(normalizedName: String): TagEntity?

    @Insert
    suspend fun insertTag(tag: TagEntity): Long

    @Query("DELETE FROM tag WHERE id = :id")
    suspend fun deleteTag(id: Long)
}
