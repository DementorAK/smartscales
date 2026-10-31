package dev.kinash.smartscales.ui.scale

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kinash.smartscales.R
import dev.kinash.smartscales.core.ble.DeviceCapability
import dev.kinash.smartscales.core.ble.ProtocolDescription
import dev.kinash.smartscales.core.ble.ScaleDrivers
import dev.kinash.smartscales.core.data.ScaleBinding
import dev.kinash.smartscales.core.data.repository.ScaleBindingRepository
import dev.kinash.smartscales.core.protocol.ChipseaProtocol
import dev.kinash.smartscales.core.protocol.V2Cipher
import dev.kinash.smartscales.ui.ScaleEditRoute
import dev.kinash.smartscales.ui.common.BackButton
import dev.kinash.smartscales.ui.common.ConfirmDialog
import dev.kinash.smartscales.ui.common.FieldSpacing
import dev.kinash.smartscales.ui.common.Prefill
import dev.kinash.smartscales.ui.common.ScreenPadding
import dev.kinash.smartscales.ui.common.formatNumber
import dev.kinash.smartscales.ui.common.parseNumber
import dev.kinash.smartscales.ui.common.resolve
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ScaleForm(val name: String = "", val v2Key: String = "", val factor: String = "")

enum class ScaleField { V2_KEY, FACTOR }

data class ScaleEditState(
    val binding: ScaleBinding? = null,
    val title: String = "",
    val description: ProtocolDescription? = null,
    val form: ScaleForm = ScaleForm(),
    val errors: Set<ScaleField> = emptySet(),
    val confirmUnbind: Boolean = false,
    val saving: Boolean = false,
    val done: Boolean = false,
) {
    /** The key field is shown for V2 scales, and for any scale that has a key already. */
    val showsKey: Boolean get() = binding?.protocol == ChipseaProtocol.OKOK_V2.name || !binding?.v2Key.isNullOrEmpty()
}

@HiltViewModel
class ScaleEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val bindings: ScaleBindingRepository,
    private val drivers: ScaleDrivers,
) : ViewModel() {
    private val id = savedStateHandle.toRoute<ScaleEditRoute>().id
    private var factorPrefill: Prefill<String>? = null

    private val _state = MutableStateFlow(ScaleEditState())
    val state: StateFlow<ScaleEditState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val binding = bindings.get(id)
            if (binding == null) {
                _state.update { it.copy(done = true) }
                return@launch
            }
            val factor = formatNumber(binding.impedanceFactor, 0, 4)
            factorPrefill = Prefill(factor, binding.impedanceFactor)
            _state.update {
                it.copy(
                    binding = binding,
                    title = drivers.titleOf(binding),
                    description = drivers.describe(binding.protocol),
                    form = ScaleForm(binding.name.orEmpty(), binding.v2Key.orEmpty(), factor),
                )
            }
        }
    }

    fun edit(transform: (ScaleForm) -> ScaleForm) = _state.update { it.copy(form = transform(it.form)) }

    fun save() {
        val state = _state.value
        val binding = state.binding ?: return
        if (state.saving) return
        val form = state.form
        val key = form.v2Key.trim()
        val factor = factorPrefill.resolve(form.factor, ::parseNumber)
        val errors = buildSet {
            if (key.isNotEmpty() && V2Cipher.parseKey(key) == null) add(ScaleField.V2_KEY)
            if (factor == null || factor !in FACTOR_RANGE) add(ScaleField.FACTOR)
        }
        _state.update { it.copy(errors = errors) }
        if (errors.isNotEmpty()) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            bindings.update(
                binding.copy(
                    name = form.name.trim().ifEmpty { null },
                    v2Key = key.ifEmpty { null },
                    impedanceFactor = factor!!,
                ),
            )
            _state.update { it.copy(done = true) }
        }
    }

    fun requestUnbind() = _state.update { it.copy(confirmUnbind = true) }

    fun dismissUnbind() = _state.update { it.copy(confirmUnbind = false) }

    fun confirmUnbind() {
        if (_state.value.saving) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            bindings.unbind(id)
            _state.update { it.copy(confirmUnbind = false, done = true) }
        }
    }

    companion object {
        /** A wider correction than this means a wrong setup rather than electrode geometry. */
        val FACTOR_RANGE = 0.5..2.0
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScaleEditScreen(onDone: () -> Unit, viewModel: ScaleEditViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.done) { if (state.done) onDone() }
    val binding = state.binding
    val form = state.form

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.scale_edit_title)) },
                navigationIcon = { BackButton(onDone) },
                actions = {
                    IconButton(onClick = viewModel::requestUnbind, enabled = binding != null) {
                        Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.scale_unbind))
                    }
                    TextButton(onClick = viewModel::save, enabled = binding != null && !state.saving) {
                        Text(stringResource(R.string.action_save))
                    }
                },
            )
        },
    ) { padding ->
        if (binding == null) return@Scaffold
        Column(
            Modifier
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(FieldSpacing),
        ) {
            Text(state.description?.displayName ?: binding.protocol, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.scale_mac, binding.mac), style = MaterialTheme.typography.bodyMedium)
            binding.bleAddress?.takeIf { it != binding.mac }?.let {
                Text(stringResource(R.string.scale_address, it), style = MaterialTheme.typography.bodyMedium)
            }
            binding.productId?.let {
                Text(stringResource(R.string.scale_product, it), style = MaterialTheme.typography.bodyMedium)
            }
            OutlinedTextField(
                value = form.name,
                onValueChange = { name -> viewModel.edit { it.copy(name = name) } },
                label = { Text(stringResource(R.string.scale_name)) },
                placeholder = { Text(state.description?.displayName.orEmpty()) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.showsKey) {
                OutlinedTextField(
                    value = form.v2Key,
                    onValueChange = { key -> viewModel.edit { it.copy(v2Key = key) } },
                    label = { Text(stringResource(R.string.scale_v2_key)) },
                    supportingText = { Text(stringResource(R.string.scale_v2_key_hint)) },
                    isError = ScaleField.V2_KEY in state.errors,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        keyboardType = KeyboardType.Ascii,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            OutlinedTextField(
                value = form.factor,
                onValueChange = { factor -> viewModel.edit { it.copy(factor = factor) } },
                label = { Text(stringResource(R.string.scale_factor)) },
                supportingText = { Text(stringResource(R.string.scale_factor_hint)) },
                isError = ScaleField.FACTOR in state.errors,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            state.description?.let { description ->
                CapabilityList(stringResource(R.string.scale_capabilities), description.capabilities)
                CapabilityList(stringResource(R.string.scale_implemented), description.implemented)
            }
        }
    }

    if (state.confirmUnbind) {
        ConfirmDialog(
            title = stringResource(R.string.scale_unbind_title, state.title),
            text = stringResource(R.string.scale_unbind_text),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = viewModel::confirmUnbind,
            onDismiss = viewModel::dismissUnbind,
        )
    }
}

@Composable
private fun CapabilityList(title: String, capabilities: Set<DeviceCapability>) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = FieldSpacing),
    )
    val names = capabilities.sortedBy { it.ordinal }.map { stringResource(it.title()) }
    Text(names.ifEmpty { listOf(stringResource(R.string.scale_nothing_yet)) }.joinToString(", "))
}
