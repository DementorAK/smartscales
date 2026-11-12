package dev.kinash.smartscales.core.data.db.dao

import androidx.room.Dao
import dev.kinash.smartscales.core.data.db.entity.ProfileReminderEntity
import androidx.room.OnConflictStrategy
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import dev.kinash.smartscales.core.data.db.entity.ProfileEntity
import dev.kinash.smartscales.core.data.db.entity.ProfileGoalEntity
import dev.kinash.smartscales.core.data.db.entity.ProfileMainVariantEntity
import dev.kinash.smartscales.core.data.db.entity.ProfileManualValueEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profile_reminder")
    fun observeReminders(): Flow<List<ProfileReminderEntity>>

    @Query("SELECT * FROM profile_reminder")
    suspend fun reminders(): List<ProfileReminderEntity>

    @Query("SELECT * FROM profile_reminder WHERE profileId = :profileId")
    suspend fun reminder(profileId: Long): ProfileReminderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setReminder(reminder: ProfileReminderEntity)

    @Query("DELETE FROM profile_reminder WHERE profileId = :profileId")
    suspend fun deleteReminder(profileId: Long)

    @Query("SELECT * FROM profile ORDER BY name COLLATE NOCASE, id")
    fun observeAll(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profile WHERE id = :id")
    suspend fun get(id: Long): ProfileEntity?

    @Insert
    suspend fun insert(profile: ProfileEntity): Long

    @Update
    suspend fun update(profile: ProfileEntity)

    @Query("DELETE FROM profile WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM profile_goal WHERE profileId = :profileId")
    fun observeGoals(profileId: Long): Flow<List<ProfileGoalEntity>>

    @Query("SELECT * FROM profile_goal WHERE profileId = :profileId AND indicatorId = :indicatorId")
    suspend fun getGoal(profileId: Long, indicatorId: String): ProfileGoalEntity?

    @Upsert
    suspend fun upsertGoal(goal: ProfileGoalEntity)

    @Query("DELETE FROM profile_goal WHERE profileId = :profileId AND indicatorId = :indicatorId")
    suspend fun deleteGoal(profileId: Long, indicatorId: String)

    @Query("SELECT * FROM profile_manual_value WHERE profileId = :profileId")
    suspend fun getManualValues(profileId: Long): List<ProfileManualValueEntity>

    @Insert
    suspend fun insertManualValues(values: List<ProfileManualValueEntity>)

    @Query("DELETE FROM profile_manual_value WHERE profileId = :profileId")
    suspend fun deleteManualValues(profileId: Long)

    @Query("SELECT * FROM profile_main_variant WHERE profileId = :profileId")
    fun observeMainVariants(profileId: Long): Flow<List<ProfileMainVariantEntity>>

    @Upsert
    suspend fun upsertMainVariant(variant: ProfileMainVariantEntity)

    @Query("DELETE FROM profile_main_variant WHERE profileId = :profileId AND groupId = :groupId")
    suspend fun deleteMainVariant(profileId: Long, groupId: String)
}
