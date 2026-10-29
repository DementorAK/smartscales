package dev.kinash.smartscales.core.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import dev.kinash.smartscales.core.model.LengthUnit
import dev.kinash.smartscales.core.model.Sex
import dev.kinash.smartscales.core.model.WeightUnit
import java.time.Instant
import java.time.LocalDate

/**
 * @property color profile colour as ARGB.
 * @property avatar short text shown instead of initials (for example an emoji), or null.
 */
@Entity(tableName = "profile")
data class ProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val sex: Sex,
    val birthDate: LocalDate,
    val heightCm: Double,
    val weightUnit: WeightUnit = WeightUnit.KILOGRAM,
    val lengthUnit: LengthUnit = LengthUnit.CENTIMETRE,
    val color: Int,
    val avatar: String? = null,
    val createdAt: Instant,
)

/** Goal for any indicator: weight, body fat or one from a formula set. */
@Entity(
    tableName = "profile_goal",
    primaryKeys = ["profileId", "indicatorId"],
    foreignKeys = [
        ForeignKey(ProfileEntity::class, ["id"], ["profileId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class ProfileGoalEntity(
    val profileId: Long,
    val indicatorId: String,
    val targetValue: Double,
    val setAt: Instant,
)

/** Main variant of an indicator group chosen in the profile, for example `ffm` → `ffm_wu`. */
@Entity(
    tableName = "profile_main_variant",
    primaryKeys = ["profileId", "groupId"],
    foreignKeys = [
        ForeignKey(ProfileEntity::class, ["id"], ["profileId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class ProfileMainVariantEntity(
    val profileId: Long,
    val groupId: String,
    val indicatorId: String,
)
