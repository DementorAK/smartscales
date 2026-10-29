package dev.kinash.smartscales.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import dev.kinash.smartscales.core.data.db.entity.ScaleBindingEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ScaleBindingDao {
    @Query("SELECT * FROM scale_binding WHERE unbound = 0 ORDER BY boundAt, id")
    fun observeBound(): Flow<List<ScaleBindingEntity>>

    /** All bindings including unbound ones, whose calibration still applies to their history. */
    @Query("SELECT * FROM scale_binding")
    fun observeAll(): Flow<List<ScaleBindingEntity>>

    @Query("SELECT * FROM scale_binding WHERE id = :id")
    suspend fun get(id: Long): ScaleBindingEntity?

    @Query("SELECT * FROM scale_binding WHERE mac = :mac")
    suspend fun getByMac(mac: String): ScaleBindingEntity?

    @Insert
    suspend fun insert(binding: ScaleBindingEntity): Long

    @Update
    suspend fun update(binding: ScaleBindingEntity)

    @Query("UPDATE scale_binding SET unbound = 1 WHERE id = :id")
    suspend fun unbind(id: Long)
}
