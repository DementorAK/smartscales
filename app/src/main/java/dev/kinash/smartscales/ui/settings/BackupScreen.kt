package dev.kinash.smartscales.ui.settings

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kinash.smartscales.R
import dev.kinash.smartscales.ui.common.BackButton
import dev.kinash.smartscales.ui.common.FieldSpacing
import dev.kinash.smartscales.ui.common.ScreenPadding
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** A backup of everything on the phone into one file, and restoring one (requirements §4.3). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(onBack: () -> Unit, viewModel: BackupViewModel = hiltViewModel()) {
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    // Not saved with the screen's state: a password does not belong in a saved Bundle
    var password by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    val mismatch = password.isNotEmpty() && repeat != password

    val writeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) viewModel.write(uri, password)
    }
    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::open)
    }
    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        // Cleared after it is shown: clearing first would change the key and cancel the snackbar
        snackbar.showSnackbar(resources.getString(text))
        viewModel.messageShown()
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.backup_title)) }, navigationIcon = { BackButton(onBack) }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(FieldSpacing),
        ) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(stringResource(R.string.backup_hint), style = MaterialTheme.typography.bodyMedium)

            Title(stringResource(R.string.backup_create_title))
            Hint(stringResource(R.string.backup_password_hint))
            PasswordField(password, { password = it }, stringResource(R.string.backup_password), "backup_password")
            if (password.isNotEmpty()) {
                PasswordField(repeat, { repeat = it }, stringResource(R.string.backup_password_repeat), "backup_password_repeat")
                if (mismatch && repeat.isNotEmpty()) {
                    Text(stringResource(R.string.backup_password_mismatch), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
            Button(
                onClick = { writeLauncher.launch("smartscales-${LocalDate.now()}.json") },
                enabled = !busy && !mismatch,
            ) { Text(stringResource(R.string.backup_create)) }

            Title(stringResource(R.string.backup_restore_title))
            Hint(stringResource(R.string.backup_restore_hint))
            OutlinedButton(onClick = { openLauncher.launch(arrayOf("*/*")) }, enabled = !busy) {
                Text(stringResource(R.string.backup_restore))
            }
        }
    }

    when (val shown = pending) {
        is PendingRestore.Password -> PasswordDialog(shown.wrong, busy, viewModel::unlock, viewModel::cancel)
        is PendingRestore.Confirm -> ConfirmDialog(shown, viewModel::restore, viewModel::cancel)
        null -> Unit
    }
}

@Composable
private fun PasswordDialog(wrong: Boolean, busy: Boolean, onUnlock: (String) -> Unit, onCancel: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.backup_locked_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(FieldSpacing)) {
                Text(stringResource(R.string.backup_locked_text))
                PasswordField(typed, { typed = it }, stringResource(R.string.backup_password), "backup_unlock")
                if (wrong) Text(stringResource(R.string.backup_wrong_password), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { onUnlock(typed) }, enabled = typed.isNotEmpty() && !busy) { Text(stringResource(R.string.backup_unlock)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(android.R.string.cancel)) } },
    )
}

@Composable
private fun ConfirmDialog(confirm: PendingRestore.Confirm, onRestore: () -> Unit, onCancel: () -> Unit) {
    val backup = confirm.backup
    val date = confirm.createdAt?.atZone(ZoneId.systemDefault())?.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.backup_confirm_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(FieldSpacing)) {
                date?.let { Text(stringResource(R.string.backup_confirm_made, it)) }
                Text(
                    listOf(
                        pluralStringResource(R.plurals.backup_profiles, backup.profiles.size, backup.profiles.size),
                        pluralStringResource(R.plurals.backup_weighings, backup.measurements.size, backup.measurements.size),
                        pluralStringResource(R.plurals.backup_scales, backup.scales.size, backup.scales.size),
                        pluralStringResource(R.plurals.backup_sets, backup.formulaSets.size, backup.formulaSets.size),
                    ).joinToString(" · "),
                )
                Text(stringResource(R.string.backup_confirm_text), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(onClick = onRestore) { Text(stringResource(R.string.backup_confirm), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(android.R.string.cancel)) } },
    )
}

@Composable
private fun PasswordField(value: String, onChange: (String) -> Unit, label: String, tag: String) {
    OutlinedTextField(
        value, onChange,
        Modifier.fillMaxWidth().testTag(tag),
        singleLine = true,
        label = { Text(label) },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
    )
}

@Composable
private fun Title(text: String) {
    Text(text, Modifier.padding(top = FieldSpacing), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
