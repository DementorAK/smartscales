package dev.kinash.smartscales.core.data

import dev.kinash.smartscales.core.data.db.entity.FormulaSetEntity
import dev.kinash.smartscales.core.data.db.entity.FormulaSetOrigin
import java.time.Instant

/**
 * A person's formula set, a layer over the built-in one. The file is kept as imported in [json], so exporting
 * gives back the same text; :core:formula reads and checks it. Enabled sets apply in [position] order, the
 * higher wins.
 *
 * @property name the set's English name when it was imported, for showing a set that no longer reads.
 */
data class FormulaSet(
    val id: Long = 0,
    val name: String,
    val json: String,
    val formatVersion: Int,
    val enabled: Boolean = true,
    val position: Int,
    val addedAt: Instant,
)

internal fun FormulaSetEntity.toFormulaSet() = FormulaSet(id, name, json, formatVersion, enabled, position, addedAt)

internal fun FormulaSet.toEntity() =
    FormulaSetEntity(id, name, json, formatVersion, FormulaSetOrigin.IMPORTED, enabled, position, addedAt)
