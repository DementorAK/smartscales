package dev.kinash.smartscales.core.data.repository

import androidx.room.withTransaction
import dev.kinash.smartscales.core.data.FormulaSet
import dev.kinash.smartscales.core.data.db.SmartScalesDatabase
import dev.kinash.smartscales.core.data.toEntity
import dev.kinash.smartscales.core.data.toFormulaSet
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/** The person's formula sets, stored as files; reading and checking them is up to :core:formula. */
@Singleton
class FormulaSetRepository @Inject constructor(
    private val database: SmartScalesDatabase,
    private val clock: Clock,
) {
    private val dao = database.formulaSetDao()

    /** All sets, the bottom layer first. */
    fun observeAll(): Flow<List<FormulaSet>> = dao.observeAll().map { list -> list.map { it.toFormulaSet() } }

    suspend fun get(id: Long): FormulaSet? = dao.get(id)?.toFormulaSet()

    /** Adds a set on top of the others, enabled. */
    suspend fun add(name: String, json: String, formatVersion: Int): Long = database.withTransaction {
        val position = (dao.maxPosition() ?: -1) + 1
        dao.insert(FormulaSet(name = name, json = json, formatVersion = formatVersion, position = position, addedAt = clock.instant()).toEntity())
    }

    /** A new version of set [id]'s file; its place in the layers and whether it is on stay. */
    suspend fun replace(id: Long, name: String, json: String, formatVersion: Int) = database.withTransaction {
        val set = dao.get(id)?.toFormulaSet() ?: return@withTransaction
        dao.update(set.copy(name = name, json = json, formatVersion = formatVersion).toEntity())
    }

    suspend fun setEnabled(id: Long, enabled: Boolean) = dao.setEnabled(id, enabled)

    /** Moves set [id] one layer up (above the next set) or down; at the end it stays. */
    suspend fun move(id: Long, up: Boolean) = database.withTransaction {
        val sets = dao.getAll()
        val index = sets.indexOfFirst { it.id == id }
        val other = if (up) index + 1 else index - 1
        if (index < 0 || other !in sets.indices) return@withTransaction
        // Positions are renumbered from 0, so equal or missing ones from old data cannot keep two sets in place
        val order = sets.toMutableList().apply { add(other, removeAt(index)) }
        order.forEachIndexed { position, set -> if (set.position != position) dao.setPosition(set.id, position) }
    }

    suspend fun delete(id: Long) = dao.delete(id)
}
