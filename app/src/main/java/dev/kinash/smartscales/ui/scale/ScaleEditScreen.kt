package dev.kinash.smartscales.ui.scale

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.kinash.smartscales.R
import dev.kinash.smartscales.core.ble.DeviceCapability
import dev.kinash.smartscales.core.ble.ProtocolDescription
import dev.kinash.smartscales.core.ble.ScaleDrivers
import dev.kinash.smartscales.core.ble.ScaleSetting
import dev.kinash.smartscales.core.data.Profile
import dev.kinash.smartscales.core.data.ScaleBinding
import dev.kinash.smartscales.core.data.ScaleDisplayUnit
import dev.kinash.smartscales.core.data.repository.ProfileRepository
import dev.kinash.smartscales.core.data.repository.ScaleBindingRepository
import dev.kinash.smartscales.core.model.WeightUnit
import dev.kinash.smartscales.core.protocol.ChipseaProtocol
import dev.kinash.smartscales.core.protocol.V2Cipher
import dev.kinash.smartscales.core.protocol.V2KeyFile
import dev.kinash.smartscales.core.protocol.V2KeyImport
import dev.kinash.smartscales.ui.ScaleEditRoute
import dev.kinash.smartscales.ui.common.BackButton
import dev.kinash.smartscales.ui.common.CheckboxRow
import dev.kinash.smartscales.ui.common.ChoiceField
import dev.kinash.smartscales.ui.common.ConfirmDialog
import dev.kinash.smartscales.ui.common.FieldSpacing
import dev.kinash.smartscales.ui.common.Prefill
import dev.kinash.smartscales.ui.common.ScreenPadding
import dev.kinash.smartscales.ui.common.SwitchRow
import dev.kinash.smartscales.ui.common.title
import dev.kinash.smartscales.ui.common.formatNumber
import dev.kinash.smartscales.ui.common.parseNumber
import dev.kinash.smartscales.ui.common.resolve
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import javax.inject.Inject

data class ScaleForm(
    val name: String = "",
    val v2Key: String = "",
    val factor: String = "",
    val displayUnit: ScaleDisplayUnit = ScaleDisplayUnit.PROFILE,
    val syncTime: Boolean = true,
    /** Profiles the scale is not told about. */
    val excluded: Set<Long> = emptySet(),
)

enum class ScaleField { V2_KEY, FACTOR }

/** The result of importing the V2 key from a file, shown under the key field until it is edited. */
enum class KeyImportNote(val isError: Boolean) {
    IMPORTED(false),
    NOT_FOUND(true),
    OTHER_SCALES(true),
    UNREADABLE(true),
}

data class ScaleEditState(
    val binding: ScaleBinding? = null,
    val title: String = "",
    val description: ProtocolDescription? = null,
    /** What the driver honours for this scale; see [ScaleSetting]. */
    val settings: Set<ScaleSetting> = emptySet(),
    val profiles: List<Profile> = emptyList(),
    val form: ScaleForm = ScaleForm(),
    val errors: Set<ScaleField> = emptySet(),
    val keyNote: KeyImportNote? = null,
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
    private val profiles: ProfileRepository,
    private val drivers: ScaleDrivers,
    @param:ApplicationContext private val context: Context,
) : ViewModel() {
    private val id = savedStateHandle.toRoute<ScaleEditRoute>().id
    private var factorPrefill: Prefill<String>? = null

    /** A file picked before the scale was loaded, as after the process was restarted behind the picker. */
    private var pendingImport: Uri? = null

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
                    settings = drivers.settings(binding.protocol, binding.protocolVersion),
                    profiles = profiles.observeProfiles().first(),
                    form = ScaleForm(
                        name = binding.name.orEmpty(),
                        v2Key = binding.v2Key.orEmpty(),
                        factor = factor,
                        displayUnit = binding.displayUnit,
                        syncTime = binding.syncTime,
                        excluded = bindings.excludedProfiles(binding.id),
                    ),
                )
            }
            pendingImport?.let { uri ->
                pendingImport = null
                importKey(uri)
            }
        }
    }

    fun edit(transform: (ScaleForm) -> ScaleForm) = _state.update { state ->
        val form = transform(state.form)
        state.copy(form = form, keyNote = state.keyNote.takeIf { form.v2Key == state.form.v2Key })
    }

    /**
     * Reads the V2 key of this scale from a file the person picked. The key goes to the field and is saved
     * with the form, so a wrong file changes nothing until Save.
     */
    fun importKey(uri: Uri) {
        val mac = _state.value.binding?.mac
        if (mac == null) {
            pendingImport = uri
            return
        }
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        val bytes = stream.readUpTo(MAX_KEY_FILE_BYTES + 1)
                        if (bytes.size > MAX_KEY_FILE_BYTES) null else bytes.toString(Charsets.UTF_8)
                    }
                }.getOrNull()
            }
            val result = text?.let { V2KeyFile.find(it, mac) }
            _state.update { state ->
                when (result) {
                    is V2KeyImport.Found -> state.copy(
                        form = state.form.copy(v2Key = result.key),
                        errors = state.errors - ScaleField.V2_KEY,
                        keyNote = KeyImportNote.IMPORTED,
                    )
                    V2KeyImport.OtherScales -> state.copy(keyNote = KeyImportNote.OTHER_SCALES)
                    V2KeyImport.NotFound -> state.copy(keyNote = KeyImportNote.NOT_FOUND)
                    null -> state.copy(keyNote = KeyImportNote.UNREADABLE)
                }
            }
        }
    }

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
                    // One form for one key, so writing it differently does not ask to confirm it again
                    v2Key = V2Cipher.normalizeKey(key),
                    impedanceFactor = factor!!,
                    displayUnit = form.displayUnit,
                    syncTime = form.syncTime,
                ),
                // Profiles deleted meanwhile are left out: they are no one to exclude
                excludedProfiles = form.excluded.filterTo(mutableSetOf()) { id -> state.profiles.any { it.id == id } },
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

        /** A key file is a few lines; anything larger is not one. */
        const val MAX_KEY_FILE_BYTES = 1024 * 1024
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScaleEditScreen(onDone: () -> Unit, viewModel: ScaleEditViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.done) { if (state.done) onDone() }
    val binding = state.binding
    val form = state.form
    val keyFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importKey)
    }

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
                    // A failed import leaves the key as it was, so only the message is red, not the field
                    supportingText = {
                        val note = state.keyNote
                        Text(
                            stringResource(note?.text() ?: R.string.scale_v2_key_hint),
                            color = if (note?.isError == true) MaterialTheme.colorScheme.error else Color.Unspecified,
                        )
                    },
                    isError = ScaleField.V2_KEY in state.errors,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        keyboardType = KeyboardType.Ascii,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                // Any type: key files come as text, XML or JSON, and file managers label them differently
                OutlinedButton(onClick = { keyFilePicker.launch(arrayOf("*/*")) }) {
                    Text(stringResource(R.string.scale_v2_key_import))
                }
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
            if (state.settings.isNotEmpty()) {
                DriverSettings(state.settings, state.profiles, form, viewModel::edit)
            }
            state.description?.let { description ->
                CapabilityList(stringResource(R.string.scale_capabilities), description.capabilities)
                CapabilityList(stringResource(R.string.scale_implemented), description.implemented)
                if (description.canWeigh && !description.testedOnRealScale) {
                    Text(
                        stringResource(R.string.scale_untested),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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

private fun KeyImportNote.text(): Int = when (this) {
    KeyImportNote.IMPORTED -> R.string.scale_v2_key_imported
    KeyImportNote.NOT_FOUND -> R.string.scale_v2_key_not_found
    KeyImportNote.OTHER_SCALES -> R.string.scale_v2_key_other_scales
    KeyImportNote.UNREADABLE -> R.string.scale_v2_key_unreadable
}

/** Up to [limit] bytes; `readNBytes` needs API 33. */
private fun InputStream.readUpTo(limit: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    while (out.size() < limit) {
        val read = read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (read < 0) break
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
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

/**
 * What the scale is told on connecting, as far as its driver honours it: the unit on its display, its clock,
 * and the profiles it knows.
 */
@Composable
private fun DriverSettings(settings: Set<ScaleSetting>, profiles: List<Profile>, form: ScaleForm, edit: ((ScaleForm) -> ScaleForm) -> Unit) {
    Text(stringResource(R.string.scale_settings_title), style = MaterialTheme.typography.titleSmall)
    if (ScaleSetting.UNIT in settings) {
        ChoiceField(
            label = stringResource(R.string.scale_display_unit),
            selected = form.displayUnit,
            options = ScaleDisplayUnit.entries,
            onSelect = { unit -> edit { it.copy(displayUnit = unit) } },
            title = { stringResource(it.title()) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (ScaleSetting.TIME in settings) {
        SwitchRow(
            title = stringResource(R.string.scale_sync_time),
            hint = stringResource(R.string.scale_sync_time_hint),
            checked = form.syncTime,
            enabled = true,
            onChange = { on -> edit { it.copy(syncTime = on) } },
        )
    }
    val users = ScaleSetting.USERS in settings
    if (users || ScaleSetting.USER in settings) {
        // Without profiles there is nothing to check, but the hint still says what the scale will be told
        if (profiles.isNotEmpty()) Text(stringResource(R.string.scale_profiles), style = MaterialTheme.typography.bodyLarge)
        profiles.forEach { profile ->
            CheckboxRow(
                title = profile.name,
                hint = null,
                checked = profile.id !in form.excluded,
                onChange = { on -> edit { it.copy(excluded = if (on) it.excluded - profile.id else it.excluded + profile.id) } },
            )
        }
        Text(
            stringResource(if (users) R.string.scale_users_sync_hint else R.string.scale_user_sync_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun ScaleDisplayUnit.title(): Int = when (this) {
    ScaleDisplayUnit.PROFILE -> R.string.scale_display_unit_profile
    ScaleDisplayUnit.KILOGRAM -> WeightUnit.KILOGRAM.title()
    ScaleDisplayUnit.POUND -> WeightUnit.POUND.title()
    ScaleDisplayUnit.STONE -> WeightUnit.STONE.title()
    ScaleDisplayUnit.KEEP -> R.string.scale_display_unit_keep
}
