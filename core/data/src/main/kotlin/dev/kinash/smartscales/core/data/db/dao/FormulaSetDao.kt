package dev.kinash.smartscales.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import dev.kinash.smartscales.core.data.db.entity.FormulaSetEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FormulaSetDao {
    /** All layers from bottom to top. */
    @Query("SELECT * FROM formula_set ORDER BY position, id")
    fun observeAll(): Flow<List<FormulaSetEntity>>

    @Query("SELECT MAX(position) FROM formula_set")
    suspend fun maxPosition(): Int?

    @Insert
    suspend fun insert(set: FormulaSetEntity): Long

    @Update
    suspend fun update(set: FormulaSetEntity)

    @Query("DELETE FROM formula_set WHERE id = :id")
    suspend fun delete(id: Long)
}
