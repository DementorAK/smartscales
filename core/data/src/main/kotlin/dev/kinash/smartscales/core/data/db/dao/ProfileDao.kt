package dev.kinash.smartscales.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import dev.kinash.smartscales.core.data.db.entity.ProfileEntity
import dev.kinash.smartscales.core.data.db.entity.ProfileGoalEntity
import dev.kinash.smartscales.core.data.db.entity.ProfileMainVariantEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {
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

    @Upsert
    suspend fun upsertGoal(goal: ProfileGoalEntity)

    @Query("DELETE FROM profile_goal WHERE profileId = :profileId AND indicatorId = :indicatorId")
    suspend fun deleteGoal(profileId: Long, indicatorId: String)

    @Query("SELECT * FROM profile_main_variant WHERE profileId = :profileId")
    fun observeMainVariants(profileId: Long): Flow<List<ProfileMainVariantEntity>>

    @Upsert
    suspend fun upsertMainVariant(variant: ProfileMainVariantEntity)

    @Query("DELETE FROM profile_main_variant WHERE profileId = :profileId AND groupId = :groupId")
    suspend fun deleteMainVariant(profileId: Long, groupId: String)
}
