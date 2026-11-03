package dev.kinash.smartscales.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import dev.kinash.smartscales.core.data.db.entity.ScaleBindingEntity
import kotlinx.coroutines.flow.Flow
import java.time.Instant

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

    @Query("UPDATE scale_binding SET bleAddress = :address WHERE id = :id")
    suspend fun updateAddress(id: Long, address: String)

    /** Moves the mark forward only: a connection that saw older records does not take it back. */
    @Query(
        "UPDATE scale_binding SET historyAcceptedUntil = :until " +
            "WHERE id = :id AND (historyAcceptedUntil IS NULL OR historyAcceptedUntil < :until)",
    )
    suspend fun acceptHistoryUntil(id: Long, until: Instant)

    @Query("UPDATE scale_binding SET v2KeyConfirmed = 1 WHERE id = :id AND v2Key = :key")
    suspend fun confirmKey(id: Long, key: String)
}
