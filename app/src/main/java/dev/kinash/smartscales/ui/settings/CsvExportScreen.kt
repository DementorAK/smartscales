package dev.kinash.smartscales.ui.settings

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.kinash.smartscales.R
import dev.kinash.smartscales.core.data.Profile
import dev.kinash.smartscales.core.data.repository.ProfileRepository
import dev.kinash.smartscales.core.data.repository.SettingsRepository
import dev.kinash.smartscales.export.MeasurementsCsv
import dev.kinash.smartscales.indicators.Indicators
import dev.kinash.smartscales.ui.common.BackButton
import dev.kinash.smartscales.ui.common.FieldSpacing
import dev.kinash.smartscales.ui.common.ScreenPadding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.LocalDate
import javax.inject.Inject

@HiltViewModel
class CsvExportViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    profiles: ProfileRepository,
    settings: SettingsRepository,
    private val indicators: Indicators,
) : ViewModel() {
    val profiles: StateFlow<List<Profile>> =
        profiles.observeProfiles().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The profile on the main screen, chosen to begin with. */
    val selected: StateFlow<Long?> = settings.selectedProfileId.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<Int?>(null)
    val message: StateFlow<Int?> = _message.asStateFlow()

    fun messageShown() {
        _message.value = null
    }

    /** Writes the weighings of [profile] into [uri], in the phone's language and number format. */
    fun write(uri: Uri, profile: Profile) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            val result = runCatching {
                val history = indicators.observeHistory(profile.id).first()
                val configuration = context.resources.configuration
                val locale = configuration.locales[0]
                val text = withContext(Dispatchers.Default) { MeasurementsCsv(context.resources, locale, locale.language).write(history, profile) }
                withContext(Dispatchers.IO) {
                    val stream = runCatching { context.contentResolver.openOutputStream(uri, "wt") }.getOrNull()
                        ?: context.contentResolver.openOutputStream(uri, "w")
                    stream?.use { it.write(text.toByteArray()) } ?: throw IOException("No stream for $uri")
                }
            }
            _message.value = if (result.isSuccess) R.string.csv_written else R.string.csv_write_failed
            _busy.value = false
        }
    }
}

/** A profile's weighings with their indicators as a CSV table (requirements §4.3). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CsvExportScreen(onBack: () -> Unit, viewModel: CsvExportViewModel = hiltViewModel()) {
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    var chosen by rememberSaveable { mutableStateOf<Long?>(null) }
    val profile = profiles.firstOrNull { it.id == chosen } ?: profiles.firstOrNull { it.id == selected } ?: profiles.firstOrNull()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null && profile != null) viewModel.write(uri, profile)
    }
    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        snackbar.showSnackbar(resources.getString(text))
        viewModel.messageShown()
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.csv_title)) }, navigationIcon = { BackButton(onBack) }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(FieldSpacing),
        ) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(stringResource(R.string.csv_hint), style = MaterialTheme.typography.bodyMedium)
            if (profiles.size > 1) {
                Text(stringResource(R.string.csv_profile), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Column(Modifier.selectableGroup()) {
                    profiles.forEach { option ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .selectable(selected = option.id == profile?.id, onClick = { chosen = option.id }, role = Role.RadioButton)
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = option.id == profile?.id, onClick = null)
                            Text(option.name, Modifier.padding(start = FieldSpacing))
                        }
                    }
                }
            }
            Button(
                onClick = { profile?.let { launcher.launch("smartscales-${fileSafe(it.name)}-${LocalDate.now()}.csv") } },
                enabled = profile != null && !busy,
            ) { Text(stringResource(R.string.csv_save)) }
        }
    }
}

/** A name fit for a file: letters and digits as they are, anything else as one dash. */
private fun fileSafe(name: String): String = name.replace(Regex("[^\\p{L}\\p{N}]+"), "-").trim('-').ifEmpty { "profile" }
