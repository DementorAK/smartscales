package dev.kinash.smartscales.ui.scale

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.kinash.smartscales.BuildConfig
import dev.kinash.smartscales.R
import dev.kinash.smartscales.core.ble.AdvertisementScanner
import dev.kinash.smartscales.core.ble.BluetoothLog
import dev.kinash.smartscales.core.ble.LogEntry
import dev.kinash.smartscales.core.ble.ScaleDrivers
import dev.kinash.smartscales.core.ble.ScannedAdvertisement
import dev.kinash.smartscales.ui.common.BackButton
import dev.kinash.smartscales.ui.common.ConfirmDialog
import dev.kinash.smartscales.ui.common.FieldSpacing
import dev.kinash.smartscales.ui.common.ScreenPadding
import dev.kinash.smartscales.ui.history.EmptyState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

@HiltViewModel
class BluetoothLogViewModel @Inject constructor(
    val scanner: AdvertisementScanner,
    private val log: BluetoothLog,
    private val drivers: ScaleDrivers,
    @param:ApplicationContext private val context: Context,
) : ViewModel() {
    val recording = log.recording
    val allDevices = log.allDevices
    val recent = log.recent
    val count = log.count

    private val _message = MutableStateFlow<Int?>(null)

    /** A result to show once, as a string resource. */
    val message: StateFlow<Int?> = _message.asStateFlow()

    fun setRecording(on: Boolean) = log.setRecording(on)

    fun setAllDevices(on: Boolean) = log.setAllDevices(on)

    fun clear() = log.clear()

    fun messageShown() {
        _message.value = null
    }

    fun protocolTitle(protocol: String?): String? = protocol?.let { drivers.describe(it)?.displayName ?: it }

    /** Listens while the screen is open, so the log gets frames without going to the weighing screen. */
    suspend fun scan(): Int? = scanner.scanUntilError { }

    /** Saves the log to [uri]; with [hideAddresses] the device's and the scale's own MAC are zeroed in frames. */
    fun export(uri: Uri, hideAddresses: Boolean) {
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val stream = requireNotNull(context.contentResolver.openOutputStream(uri)) { "Cannot open $uri" }
                    stream.bufferedWriter().use {
                        log.export(it, header(), identities = if (hideAddresses) ::addressesIn else null)
                    }
                }
            }
            _message.value = if (result.isSuccess) R.string.btlog_exported else R.string.btlog_export_failed
        }
    }

    /** The address Android saw and, for a recognised scale, its own MAC from the frame (`C0` carries it). */
    private fun addressesIn(entry: LogEntry): List<String> {
        if (entry.kind != LogEntry.Kind.ADV) return listOf(entry.address)
        val advertisement = ScannedAdvertisement(entry.address, entry.name, entry.rssi, entry.bytes(), 0)
        return listOfNotNull(entry.address, drivers.recognise(advertisement)?.second?.scaleMac).distinct()
    }

    /** "All devices nearby" is for this screen only: leaving it ends the mode. */
    override fun onCleared() = log.setAllDevices(false)

    private fun header(): List<String> = listOf(
        "App ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
        "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}",
    )
}

/** Raw Bluetooth frames for reports about scales that are not found or read wrongly. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BluetoothLogScreen(onBack: () -> Unit, viewModel: BluetoothLogViewModel = hiltViewModel()) {
    val recording by viewModel.recording.collectAsStateWithLifecycle()
    val allDevices by viewModel.allDevices.collectAsStateWithLifecycle()
    val recent by viewModel.recent.collectAsStateWithLifecycle()
    val count by viewModel.count.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    var confirmExport by rememberSaveable { mutableStateOf(false) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    var retry by rememberSaveable { mutableIntStateOf(0) }
    var scanError by rememberSaveable { mutableStateOf<Int?>(null) }
    var hideAddresses by rememberSaveable { mutableStateOf(true) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri?.let { viewModel.export(it, hideAddresses) }
    }

    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        snackbar.showSnackbar(resources.getString(text))
        viewModel.messageShown()
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.btlog_title)) }, navigationIcon = { BackButton(onBack) })
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            Column(
                Modifier.padding(horizontal = ScreenPadding),
                verticalArrangement = Arrangement.spacedBy(FieldSpacing),
            ) {
                Text(stringResource(R.string.btlog_intro), style = MaterialTheme.typography.bodyMedium)
                SwitchRow(stringResource(R.string.btlog_record), null, recording, enabled = true, viewModel::setRecording)
                SwitchRow(
                    title = stringResource(R.string.btlog_all_devices),
                    hint = stringResource(R.string.btlog_all_devices_hint),
                    checked = allDevices,
                    enabled = recording,
                    onChange = viewModel::setAllDevices,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FieldSpacing)) {
                    Text(
                        pluralStringResource(R.plurals.btlog_count, count, count),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { confirmClear = true }, enabled = count > 0) {
                        Text(stringResource(R.string.btlog_clear))
                    }
                    OutlinedButton(onClick = { confirmExport = true }, enabled = count > 0) {
                        Text(stringResource(R.string.btlog_export))
                    }
                }
            }
            HorizontalDivider(Modifier.padding(top = FieldSpacing))
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                if (recording) {
                    BluetoothGate(viewModel.scanner) {
                        val error = scanError
                        if (error != null) {
                            EmptyState(stringResource(R.string.bt_scan_failed, error), stringResource(R.string.bt_retry)) {
                                scanError = null
                                retry++
                            }
                        } else {
                            ScanWhileVisible(retry) { scanError = viewModel.scan() }
                            EntryList(recent, viewModel::protocolTitle)
                        }
                    }
                } else {
                    EntryList(recent, viewModel::protocolTitle)
                }
            }
        }
    }

    if (confirmExport) {
        AlertDialog(
            onDismissRequest = { confirmExport = false },
            title = { Text(stringResource(R.string.btlog_export_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(FieldSpacing)) {
                    Text(stringResource(R.string.btlog_export_text))
                    SwitchRow(
                        title = stringResource(R.string.btlog_hide_addresses),
                        hint = stringResource(R.string.btlog_hide_addresses_hint),
                        checked = hideAddresses,
                        enabled = true,
                        onChange = { hideAddresses = it },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmExport = false
                        exportLauncher.launch("smartscales-bluetooth-${LocalDate.now()}.txt")
                    },
                ) { Text(stringResource(R.string.btlog_export_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmExport = false }) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }
    if (confirmClear) {
        ConfirmDialog(
            title = stringResource(R.string.btlog_clear_title),
            text = stringResource(R.string.btlog_clear_text),
            confirmLabel = stringResource(R.string.btlog_clear),
            onConfirm = {
                confirmClear = false
                viewModel.clear()
            },
            onDismiss = { confirmClear = false },
        )
    }
}

@Composable
private fun SwitchRow(title: String, hint: String?, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    // The whole row toggles, so the text is part of the switch for taps and screen readers
    Row(
        Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** The newest frames first: time, device alias, signal, protocol, and the frame itself. */
@Composable
private fun EntryList(entries: List<BluetoothLog.AliasedEntry>, protocolTitle: (String?) -> String?) {
    if (entries.isEmpty()) {
        EmptyState(stringResource(R.string.btlog_empty))
        return
    }
    val time = remember { DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault()) }
    LazyColumn {
        items(entries.asReversed()) { (entry, alias) ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ScreenPadding, vertical = FieldSpacing),
            ) {
                // GATT entries show the characteristic and the direction where advertisements show the signal
                val link = when (entry.kind) {
                    LogEntry.Kind.ADV -> stringResource(R.string.btlog_rssi, entry.rssi)
                    LogEntry.Kind.GATT -> "← ${entry.characteristic.orEmpty()}"
                    LogEntry.Kind.GATT_WRITE -> "→ ${entry.characteristic.orEmpty()}"
                }
                val details = listOfNotNull(
                    time.format(Instant.ofEpochMilli(entry.timeMs)),
                    alias,
                    link,
                    protocolTitle(entry.protocol) ?: entry.name,
                )
                Text(details.joinToString("  ·  "), style = MaterialTheme.typography.labelMedium)
                Text(entry.data, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
            HorizontalDivider()
        }
    }
}
