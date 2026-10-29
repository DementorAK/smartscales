package dev.kinash.smartscales.core.data

import dev.kinash.smartscales.core.data.db.entity.ProfileEntity
import dev.kinash.smartscales.core.model.LengthUnit
import dev.kinash.smartscales.core.model.PersonSnapshot
import dev.kinash.smartscales.core.model.Sex
import dev.kinash.smartscales.core.model.WeightUnit
import java.time.Instant
import java.time.LocalDate

/**
 * A family member or guest.
 *
 * @property color profile colour as ARGB.
 * @property avatar short text shown instead of initials (for example an emoji), or null.
 */
data class Profile(
    val id: Long = 0,
    val name: String,
    val sex: Sex,
    val birthDate: LocalDate,
    val heightCm: Double,
    val weightUnit: WeightUnit = WeightUnit.KILOGRAM,
    val lengthUnit: LengthUnit = LengthUnit.CENTIMETRE,
    val color: Int,
    val avatar: String? = null,
    val createdAt: Instant,
) {
    init {
        require(heightCm > 0) { "Height must be positive: $heightCm" }
    }

    /** Snapshot for a new measurement taken now. */
    fun snapshot(): PersonSnapshot = PersonSnapshot(heightCm, sex, birthDate)

    /** Whether a measurement dated [date] can belong to this person: it is not before the birth date. */
    fun isBornBy(date: LocalDate): Boolean = !birthDate.isAfter(date)
}

internal fun ProfileEntity.toProfile() =
    Profile(id, name, sex, birthDate, heightCm, weightUnit, lengthUnit, color, avatar, createdAt)

internal fun Profile.toEntity() =
    ProfileEntity(id, name, sex, birthDate, heightCm, weightUnit, lengthUnit, color, avatar, createdAt)
