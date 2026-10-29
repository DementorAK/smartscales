package dev.kinash.smartscales.core.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

/** See [dev.kinash.smartscales.core.data.ScaleBinding]. */
@Entity(
    tableName = "scale_binding",
    indices = [Index("mac", unique = true)],
)
data class ScaleBindingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mac: String,
    val bleAddress: String? = null,
    val name: String? = null,
    val protocol: String,
    val protocolVersion: String? = null,
    val productId: Int? = null,
    val v2Key: String? = null,
    val impedanceFactor: Double = 1.0,
    val unbound: Boolean = false,
    val boundAt: Instant,
)
