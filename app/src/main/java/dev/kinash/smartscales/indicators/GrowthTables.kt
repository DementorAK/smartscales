package dev.kinash.smartscales.indicators

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.kinash.smartscales.core.data.repository.ChildNorms
import dev.kinash.smartscales.core.data.repository.SettingsRepository
import dev.kinash.smartscales.core.formula.WhoGrowthCharts
import dev.kinash.smartscales.core.formula.WhoTable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The WHO growth tables the person imported (requirements §3). They are kept in the app's own files, as read from
 * WHO's `.xlsx` files, and never leave the phone: WHO's licence keeps them out of the app itself.
 */
@Singleton
class GrowthTables @Inject constructor(
    @param:ApplicationContext private val context: Context,
    settings: SettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val file get() = File(context.filesDir, FILE_NAME)
    private val lock = Mutex()
    private val _who = MutableStateFlow<WhoGrowthCharts?>(null)

    /** The imported tables, empty without any; null until read. */
    val who: StateFlow<WhoGrowthCharts?> = _who.asStateFlow()

    init {
        scope.launch { lock.withLock { _who.value = read() } }
    }

    /**
     * The tables to place children on: WHO's when there are any and the person did not choose CDC, which is
     * also what an unset choice means once tables are imported; null for the built-in CDC charts.
     */
    val active: Flow<WhoGrowthCharts?> = combine(who.filterNotNull(), settings.childNorms) { who, norms ->
        who.takeIf { it.tables.isNotEmpty() && norms != ChildNorms.CDC }
    }

    /** Adds [tables] over those kept; a table takes the ages it covers from an earlier one of its indicator and sex. */
    suspend fun import(tables: List<WhoTable>) = update { current -> current.with(tables) }

    suspend fun clear() = update { WhoGrowthCharts.EMPTY }

    /** The tables' file as kept, for a backup; null without tables. */
    suspend fun csv(): String? = withContext(Dispatchers.IO) { lock.withLock { file.takeIf { it.exists() }?.readText() } }

    /** Replaces the tables with those of a backup's [csv]; null, or a file that does not read, leaves none. */
    suspend fun restore(csv: String?) = update { csv?.let { runCatching { WhoGrowthCharts.parseCsv(it) }.getOrNull() } ?: WhoGrowthCharts.EMPTY }

    private suspend fun update(change: (WhoGrowthCharts) -> WhoGrowthCharts) = withContext(Dispatchers.IO) {
        lock.withLock {
            val next = change(_who.value ?: read())
            if (next.tables.isEmpty()) {
                file.delete()
            } else {
                // Written aside and moved, so a write cut short leaves the earlier tables
                val temporary = File(context.filesDir, "$FILE_NAME.tmp")
                temporary.writeText(next.toCsv())
                if (!temporary.renameTo(file)) {
                    file.delete()
                    temporary.renameTo(file)
                }
            }
            _who.value = next
        }
    }

    private fun read(): WhoGrowthCharts =
        runCatching { file.takeIf { it.exists() }?.readText()?.let(WhoGrowthCharts::parseCsv) }.getOrNull() ?: WhoGrowthCharts.EMPTY

    companion object {
        /** Also its name in a backup. */
        const val FILE_NAME = "who_growth.csv"
    }
}
