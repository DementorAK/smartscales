package dev.kinash.smartscales.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
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
    val v2KeyConfirmed: Boolean = false,
    val impedanceFactor: Double = 1.0,
    val unbound: Boolean = false,
    val boundAt: Instant,
    val historyAcceptedUntil: Instant? = null,
    @ColumnInfo(defaultValue = "PROFILE") val displayUnit: String = "PROFILE",
    @ColumnInfo(defaultValue = "1") val syncTime: Boolean = true,
)

/**
 * A profile a scale is not told about (users on the scale). Stored as exclusions, so a new profile reaches
 * every scale by default.
 */
@Entity(
    tableName = "scale_excluded_profile",
    primaryKeys = ["bindingId", "profileId"],
    indices = [Index("profileId")],
    foreignKeys = [
        ForeignKey(ScaleBindingEntity::class, ["id"], ["bindingId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(ProfileEntity::class, ["id"], ["profileId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class ScaleExcludedProfileEntity(
    val bindingId: Long,
    val profileId: Long,
)
