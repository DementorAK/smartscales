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

@Dao
interface MeasurementDao {
    @Transaction
    @Query("SELECT * FROM measurement WHERE profileId = :profileId ORDER BY takenAt DESC, id DESC")
    fun observeForProfile(profileId: Long): Flow<List<MeasurementWithDetails>>

    @Transaction
    @Query("SELECT * FROM measurement WHERE profileId IS NULL ORDER BY takenAt DESC, id DESC")
    fun observeInbox(): Flow<List<MeasurementWithDetails>>

    @Transaction
    @Query("SELECT * FROM measurement WHERE id = :id")
    suspend fun get(id: Long): MeasurementWithDetails?

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
