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
 * @property cards ids of the indicators and groups on the main screen, in order; null for the app's default set,
 *   so a profile that never chose follows changes to it.
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
    val cards: List<String>? = null,
) {
    init {
        require(heightCm > 0) { "Height must be positive: $heightCm" }
    }

    /** Snapshot for a new measurement taken now. */
    fun snapshot(): PersonSnapshot = PersonSnapshot(heightCm, sex, birthDate)

    /** Whether a measurement dated [date] can belong to this person: it is not before the birth date. */
    fun isBornBy(date: LocalDate): Boolean = !birthDate.isAfter(date)
}

internal fun ProfileEntity.toProfile() = Profile(
    id, name, sex, birthDate, heightCm, weightUnit, lengthUnit, color, avatar, createdAt,
    cards?.split(CARD_SEPARATOR)?.filter { it.isNotEmpty() },
)

internal fun Profile.toEntity() = ProfileEntity(
    id, name, sex, birthDate, heightCm, weightUnit, lengthUnit, color, avatar, createdAt,
    cards?.joinToString(CARD_SEPARATOR),
)

/** Indicator ids are letters, digits and underscores, so a comma separates them safely. */
private const val CARD_SEPARATOR = ","

