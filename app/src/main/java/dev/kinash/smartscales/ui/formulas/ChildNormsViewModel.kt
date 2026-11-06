package dev.kinash.smartscales.ui.formulas

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.kinash.smartscales.core.data.repository.ChildNorms
import dev.kinash.smartscales.core.data.repository.SettingsRepository
import dev.kinash.smartscales.core.formula.WhoGrowthCharts
import dev.kinash.smartscales.core.formula.WhoTable
import dev.kinash.smartscales.core.formula.WhoTableException
import dev.kinash.smartscales.core.formula.WhoTableProblem
import dev.kinash.smartscales.indicators.GrowthTables
import dev.kinash.smartscales.ui.common.displayName
import dev.kinash.smartscales.ui.common.readAtMost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** The norms children are placed on, and the WHO tables there are. */
data class ChildNormsState(
    val who: WhoGrowthCharts,
    /** What applies: CDC until there are WHO tables, then the person's choice, WHO when not made. */
    val norms: ChildNorms,
)

/**
 * Picked files as they went: the tables taken, and the files that are not WHO tables with the reason; [saved] is false
 * when the tables could not be written (no room left), and then none were taken.
 */
data class WhoImportResult(val taken: List<WhoTable>, val rejected: List<Pair<String, WhoTableProblem>>, val saved: Boolean = true)

@HiltViewModel
class ChildNormsViewModel @Inject constructor(
    private val tables: GrowthTables,
    private val settings: SettingsRepository,
    @param:ApplicationContext private val context: Context,
) : ViewModel() {
    val state: StateFlow<ChildNormsState?> = combine(tables.who.filterNotNull(), settings.childNorms) { who, chosen ->
        ChildNormsState(who, if (who.tables.isEmpty()) ChildNorms.CDC else chosen ?: ChildNorms.WHO)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _result = MutableStateFlow<WhoImportResult?>(null)

    /** The last import, until the person closes it. */
    val result: StateFlow<WhoImportResult?> = _result.asStateFlow()

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    fun choose(norms: ChildNorms) {
        viewModelScope.launch { settings.setChildNorms(norms) }
    }

    /** Reads every picked file and keeps those that are WHO tables; the others are listed with why not. */
    fun import(uris: List<Uri>) {
        if (uris.isEmpty() || _importing.value) return
        _importing.value = true
        viewModelScope.launch {
            try {
                _result.value = importNow(uris)
            } finally {
                _importing.value = false
            }
        }
    }

    private suspend fun importNow(uris: List<Uri>): WhoImportResult {
        val read = withContext(Dispatchers.IO) {
            uris.map { uri ->
                val name = displayName(context, uri)
                val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { readAtMost(it, MAX_TABLE_BYTES + 1) } }.getOrNull()
                name to when {
                    bytes == null || bytes.size > MAX_TABLE_BYTES -> Result.failure(WhoTableException(WhoTableProblem.NOT_XLSX, "Not read"))
                    else -> runCatching { WhoTable.readXlsx(bytes, name) }
                }
            }
        }
        val taken = read.mapNotNull { (_, table) -> table.getOrNull() }
        val rejected = read.mapNotNull { (name, table) ->
            (table.exceptionOrNull() as? WhoTableException)?.let { name to it.problem }
                ?: table.exceptionOrNull()?.let { name to WhoTableProblem.NOT_XLSX }
        }
        val saved = taken.isEmpty() || withContext(NonCancellable) {
            try {
                tables.import(taken)
                true
            } catch (e: Exception) {
                false
            }
        }
        return if (saved) WhoImportResult(taken, rejected) else WhoImportResult(emptyList(), rejected, saved = false)
    }

    fun resultShown() {
        _result.value = null
    }

    fun clear() {
        viewModelScope.launch { withContext(NonCancellable) { tables.clear() } }
    }

    private companion object {
        /** A WHO table by day is under 300 KB; much bigger is not one. */
        const val MAX_TABLE_BYTES = 5_000_000
    }
}
