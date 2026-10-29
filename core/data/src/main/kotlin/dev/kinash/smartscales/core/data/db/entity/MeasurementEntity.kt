package dev.kinash.smartscales.core.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.kinash.smartscales.core.model.MeasurementSource
import dev.kinash.smartscales.core.model.Sex
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * Raw data of one weighing. Indicators are never stored.
 *
 * @property profileId null means the inbox: a weighing from the scale history that is not assigned yet.
 * @property heightCm person snapshot at the time of weighing, together with [sex] and [birthDate];
 *   all three are null only in the inbox and are filled from the target profile on assignment.
 * @property scaleBindingId scale the measurement came from (null for manual input and import);
 *   its calibration factor applies to the impedances.
 * @property scaleProperties raw scale property byte (unit, precision), kept to re-check parsing.
 */
@Entity(
    tableName = "measurement",
    foreignKeys = [
        // A profile is deleted only explicitly together with its history, see ProfileRepository.delete
        ForeignKey(ProfileEntity::class, ["id"], ["profileId"], onDelete = ForeignKey.RESTRICT),
        // Bindings are only ever marked unbound, never deleted
        ForeignKey(ScaleBindingEntity::class, ["id"], ["scaleBindingId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index("profileId", "takenAt"), Index("scaleBindingId")],
)
data class MeasurementEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long?,
    val takenAt: Instant,
    val zone: ZoneId,
    val source: MeasurementSource,
    val weightKg: Double,
    val heartRateBpm: Int? = null,
    val heightCm: Double?,
    val sex: Sex?,
    val birthDate: LocalDate?,
    val scaleBindingId: Long? = null,
    val scaleProperties: Int? = null,
    val note: String? = null,
)

/** Impedance as reported by the scale, before the calibration factor (see `core.model.Impedance`). */
@Entity(
    tableName = "impedance",
    primaryKeys = ["measurementId", "channel", "frequencyIndex"],
    foreignKeys = [
        ForeignKey(MeasurementEntity::class, ["id"], ["measurementId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class ImpedanceEntity(
    val measurementId: Long,
    val channel: Int,
    val frequencyIndex: Int,
    val frequencyKhz: Int?,
    val ohms: Double,
)

/** Manual input field: circumferences and fields declared by formula sets. Lengths in centimetres. */
@Entity(
    tableName = "manual_value",
    primaryKeys = ["measurementId", "fieldId"],
    foreignKeys = [
        ForeignKey(MeasurementEntity::class, ["id"], ["measurementId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class ManualValueEntity(
    val measurementId: Long,
    val fieldId: String,
    val value: Double,
)

/** Indicator value imported as is, for records without impedance that cannot be recalculated. */
@Entity(
    tableName = "imported_value",
    primaryKeys = ["measurementId", "indicatorId"],
    foreignKeys = [
        ForeignKey(MeasurementEntity::class, ["id"], ["measurementId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class ImportedValueEntity(
    val measurementId: Long,
    val indicatorId: String,
    val value: Double,
)

/**
 * Tag names are unique regardless of case. SQLite `NOCASE` folds only Latin letters,
 * so uniqueness is kept by [normalizedName] (see [normalizeTagName]) and [name] keeps the spelling shown.
 */
@Entity(
    tableName = "tag",
    indices = [Index("normalizedName", unique = true)],
)
data class TagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val normalizedName: String = normalizeTagName(name),
)

fun normalizeTagName(name: String): String = name.trim().lowercase(Locale.ROOT)

@Entity(
    tableName = "measurement_tag",
    primaryKeys = ["measurementId", "tagId"],
    foreignKeys = [
        ForeignKey(MeasurementEntity::class, ["id"], ["measurementId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(TagEntity::class, ["id"], ["tagId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("tagId")],
)
data class MeasurementTagEntity(
    val measurementId: Long,
    val tagId: Long,
)
