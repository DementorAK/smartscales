package dev.kinash.smartscales.core.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.Instant

enum class FormulaSetOrigin {
    /** Optional set shipped with the app. The immutable built-in set ships in :core:formula and is not stored here. */
    BUILT_IN,
    IMPORTED,
}

/**
 * Formula set layered over the immutable built-in set. Enabled sets apply in [position] order,
 * a higher position wins on conflicts. The content format is defined by :core:formula.
 */
@Entity(tableName = "formula_set")
data class FormulaSetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val json: String,
    val formatVersion: Int,
    val origin: FormulaSetOrigin,
    val enabled: Boolean = true,
    val position: Int,
    val addedAt: Instant,
)
