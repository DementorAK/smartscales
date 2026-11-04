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
 * @property cards card ids of the main screen joined by commas, empty for none; null for the default set.
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
    val cards: String? = null,
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

/**
 * A manual input field the profile keeps, such as the waist (see `ManualFields.CIRCUMFERENCES`): weighings that
 * get this profile take it as their own value. Lengths in centimetres.
 */
@Entity(
    tableName = "profile_manual_value",
    primaryKeys = ["profileId", "fieldId"],
    foreignKeys = [
        ForeignKey(ProfileEntity::class, ["id"], ["profileId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class ProfileManualValueEntity(
    val profileId: Long,
    val fieldId: String,
    val value: Double,
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
