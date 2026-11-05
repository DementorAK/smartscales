package dev.kinash.smartscales.ui.formulas

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.kinash.smartscales.R
import dev.kinash.smartscales.core.data.FormulaSet
import dev.kinash.smartscales.core.data.repository.FormulaSetRepository
import dev.kinash.smartscales.core.formula.BuiltinFormulas
import dev.kinash.smartscales.core.formula.FormulaSetFile
import dev.kinash.smartscales.core.formula.text
import dev.kinash.smartscales.indicators.ImportCheck
import dev.kinash.smartscales.indicators.Indicators
import dev.kinash.smartscales.indicators.Layers
import dev.kinash.smartscales.indicators.MAX_SET_BYTES
import dev.kinash.smartscales.indicators.checkImport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import javax.inject.Inject

/** A file read for importing, waiting for the person to confirm it, or the reasons it is not taken. */
data class PendingImport(val text: String, val check: ImportCheck)

@HiltViewModel
class FormulaSetsViewModel @Inject constructor(
    private val sets: FormulaSetRepository,
    indicators: Indicators,
    @param:ApplicationContext private val context: Context,
) : ViewModel() {
    /** The sets as the model took them; null until read. */
    val layers: StateFlow<Layers?> = indicators.layers

    private val _pending = MutableStateFlow<PendingImport?>(null)
    val pending: StateFlow<PendingImport?> = _pending.asStateFlow()

    private val _message = MutableStateFlow<Int?>(null)

    /** A result to show once, as a string resource. */
    val message: StateFlow<Int?> = _message.asStateFlow()

    fun messageShown() {
        _message.value = null
    }

    /** Reads the file and checks it over the sets now in use; nothing is stored before [confirmImport]. */
    fun import(uri: Uri) {
        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { readAtMost(it, MAX_SET_BYTES + 1) } }.getOrNull()
            }
            when {
                bytes == null -> _message.value = R.string.formula_import_failed
                bytes.size > MAX_SET_BYTES -> _message.value = R.string.formula_import_too_big
                else -> {
                    val text = bytes.decodeToString()
                    val stored = sets.observeAll().first()
                    _pending.value = PendingImport(text, withContext(Dispatchers.Default) { checkImport(text, stored) })
                }
            }
        }
    }

    fun confirmImport() {
        val pending = _pending.value ?: return
        _pending.value = null
        val check = pending.check as? ImportCheck.Accepted ?: return
        viewModelScope.launch {
            withContext(NonCancellable) {
                val name = check.file.name.text("en")
                val replaces = check.replaces
                if (replaces == null) {
                    sets.add(name, pending.text, FormulaSetFile.FORMAT)
                } else {
                    sets.replace(replaces.id, name, pending.text, FormulaSetFile.FORMAT)
                }
                _message.value = when {
                    replaces == null -> R.string.formula_set_added
                    replaces.enabled -> R.string.formula_set_replaced
                    else -> R.string.formula_set_replaced_off
                }
            }
        }
    }

    fun dismissImport() {
        _pending.value = null
    }

    fun setEnabled(set: FormulaSet, enabled: Boolean) {
        viewModelScope.launch { sets.setEnabled(set.id, enabled) }
    }

    /** Up is a layer higher, which wins over those below. */
    fun move(set: FormulaSet, up: Boolean) {
        viewModelScope.launch { sets.move(set.id, up) }
    }

    fun delete(set: FormulaSet) {
        viewModelScope.launch { withContext(NonCancellable) { sets.delete(set.id) } }
    }

    /** Saves the file of [set] as it was imported, or the built-in one for null. */
    fun export(uri: Uri, set: FormulaSet?) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val stream = requireNotNull(context.contentResolver.openOutputStream(uri)) { "Cannot open $uri" }
                    stream.bufferedWriter().use { it.write(set?.json ?: BuiltinFormulas.text) }
                }
            }
            _message.value = if (result.isSuccess) R.string.formula_set_exported else R.string.formula_set_export_failed
        }
    }
}

/** Up to [limit] bytes of [stream]; `readNBytes` needs API 33. */
private fun readAtMost(stream: InputStream, limit: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (out.size() < limit) {
        val read = stream.read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (read < 0) break
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}
