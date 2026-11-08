package dev.kinash.smartscales.ui.formulas

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kinash.smartscales.R
import dev.kinash.smartscales.core.data.FormulaSet
import dev.kinash.smartscales.core.formula.BuiltinFormulas
import dev.kinash.smartscales.core.formula.FormulaIssue
import dev.kinash.smartscales.core.formula.FormulaSetFile
import dev.kinash.smartscales.core.formula.text
import dev.kinash.smartscales.indicators.ImportCheck
import dev.kinash.smartscales.indicators.LayerState
import dev.kinash.smartscales.ui.common.BackButton
import dev.kinash.smartscales.ui.common.ConfirmDialog
import dev.kinash.smartscales.ui.common.FieldSpacing
import dev.kinash.smartscales.ui.common.ScreenPadding

/**
 * The person's formula sets over the built-in one (requirements §5.1): the top layer first, each with its
 * switch, place, export and deletion, and the errors that keep it out or the warnings it brings. Importing
 * checks the file first and shows what it adds or why it is not taken.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormulaSetsScreen(
    onBack: () -> Unit,
    onCreate: () -> Unit,
    onEdit: (Long) -> Unit,
    viewModel: FormulaSetsViewModel = hiltViewModel(),
) {
    val layers by viewModel.layers.collectAsStateWithLifecycle()
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val language = LocalConfiguration.current.locales[0].language
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    // The set to export once the file is picked; -1 for none, 0 for the built-in one
    var exporting by rememberSaveable { mutableLongStateOf(-1L) }
    var deleting by rememberSaveable { mutableStateOf<Long?>(null) }
    // An issue may point into another set, where a clash was found: its name goes before the path
    val names = remember(layers, language) {
        mapOf(BuiltinFormulas.file.id to BuiltinFormulas.file.name.text(language)) +
            layers?.sets.orEmpty().mapNotNull { layer -> layer.file?.let { it.id to layer.title(language) } }
    }
    // And the sets' files, for the names of the parts an issue is in
    val files = remember(layers) {
        mapOf(BuiltinFormulas.file.id to BuiltinFormulas.file) + layers?.sets.orEmpty().mapNotNull { layer -> layer.file?.let { it.id to it } }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::import)
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val id = exporting
        exporting = -1L
        if (uri != null && id >= 0) viewModel.export(uri, layers?.sets?.firstOrNull { it.stored.id == id }?.stored)
    }

    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        snackbar.showSnackbar(resources.getString(text))
        viewModel.messageShown()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.formula_sets_title)) },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    TextButton(onClick = onCreate) { Text(stringResource(R.string.formula_sets_create)) }
                    // JSON files often come without their type from messengers and downloads
                    TextButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.formula_sets_import)) }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(FieldSpacing),
        ) {
            Text(
                stringResource(R.string.formula_sets_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val sets = layers?.sets ?: return@Column
            val top = sets.reversed()
            top.forEachIndexed { index, layer ->
                SetCard(
                    layer, language, names, files,
                    canMoveUp = index > 0,
                    canMoveDown = index < top.lastIndex,
                    onEnabled = { viewModel.setEnabled(layer.stored, it) },
                    onMove = { up -> viewModel.move(layer.stored, up) },
                    onExport = {
                        exporting = layer.stored.id
                        exportLauncher.launch(fileName(layer.file?.id ?: layer.stored.name))
                    },
                    onDelete = { deleting = layer.stored.id },
                    onEdit = { onEdit(layer.stored.id) },
                )
            }
            BuiltinCard(language) {
                exporting = 0L
                exportLauncher.launch(fileName(BuiltinFormulas.file.id))
            }
        }
    }

    pending?.let { ImportDialog(it.check, language, names, files, viewModel::confirmImport, viewModel::dismissImport) }
    deleting?.let { id ->
        val layer = layers?.sets?.firstOrNull { it.stored.id == id }
        if (layer == null) {
            deleting = null
        } else {
            ConfirmDialog(
                title = stringResource(R.string.formula_set_delete_title, layer.title(language)),
                text = stringResource(R.string.formula_set_delete_text),
                confirmLabel = stringResource(R.string.action_delete),
                onConfirm = {
                    viewModel.delete(layer.stored)
                    deleting = null
                },
                onDismiss = { deleting = null },
            )
        }
    }
}

private fun fileName(id: String) = "$id.json"

private fun LayerState.title(language: String): String = file?.name?.text(language) ?: stored.name

@Composable
private fun SetCard(
    layer: LayerState,
    language: String,
    names: Map<String, String>,
    files: Map<String, FormulaSetFile>,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onEnabled: (Boolean) -> Unit,
    onMove: (up: Boolean) -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = ScreenPadding, end = ScreenPadding, top = ScreenPadding)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(layer.title(language), style = MaterialTheme.typography.titleMedium)
                    layer.file?.let { Text(contents(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                Switch(checked = layer.stored.enabled, onCheckedChange = onEnabled)
            }
            layer.file?.description?.let {
                Text(it.text(language), Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyMedium)
            }
            val status = when {
                layer.file == null -> R.string.formula_set_unreadable
                layer.errors.isNotEmpty() -> R.string.formula_set_left_out
                !layer.stored.enabled -> R.string.formula_set_off
                else -> null
            }
            status?.let {
                val color = if (it == R.string.formula_set_off) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
                Text(stringResource(it), Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyMedium, color = color)
            }
            Issues(layer.errors, error = true, layer.file?.id, names, files)
            if (layer.stored.enabled && layer.warnings.isNotEmpty()) {
                Text(stringResource(R.string.formula_set_warnings), Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelLarge)
                Issues(layer.warnings, error = false, layer.file?.id, names, files)
            }
        }
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onMove(true) }, enabled = canMoveUp) {
                Icon(painterResource(R.drawable.ic_arrow_upward), stringResource(R.string.formula_set_up))
            }
            IconButton(onClick = { onMove(false) }, enabled = canMoveDown) {
                Icon(painterResource(R.drawable.ic_arrow_downward), stringResource(R.string.formula_set_down))
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                // A set of an unknown format cannot be read into the editor
                if (layer.file != null) TextButton(onClick = onEdit) { Text(stringResource(R.string.formula_set_edit)) }
                TextButton(onClick = onExport) { Text(stringResource(R.string.formula_set_export)) }
                IconButton(onClick = onDelete) {
                    Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.formula_set_delete))
                }
            }
        }
    }
}

@Composable
private fun BuiltinCard(language: String, onExport: () -> Unit) {
    val file = BuiltinFormulas.file
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = ScreenPadding, end = ScreenPadding, top = ScreenPadding)) {
            Text(file.name.text(language), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.formula_sets_builtin) + " · " + contents(file),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onExport) { Text(stringResource(R.string.formula_set_export)) }
        }
    }
}

/** What a set brings: its indicators, changes of others, hidden ones and fields to enter, those it has. */
@Composable
private fun contents(file: FormulaSetFile): String = listOfNotNull(
    file.indicators.size.takeIf { it > 0 }?.let { stringResource(R.string.formula_set_indicators, it) },
    file.patches.size.takeIf { it > 0 }?.let { stringResource(R.string.formula_set_patches, it) },
    file.hidden.size.takeIf { it > 0 }?.let { stringResource(R.string.formula_set_hidden, it) },
    file.inputs.size.takeIf { it > 0 }?.let { stringResource(R.string.formula_set_inputs, it) },
).joinToString(" · ").ifEmpty { stringResource(R.string.formula_set_empty) }

/** Issues of the set [owner]; one found in another set says which, by its name from [names] or its id. */
@Composable
private fun Issues(issues: List<FormulaIssue>, error: Boolean, owner: String?, names: Map<String, String>, files: Map<String, FormulaSetFile>) {
    val color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    issues.forEach { Text("• " + issueText(it, owner, names, files), style = MaterialTheme.typography.bodySmall, color = color) }
}

/**
 * Where in the file and what: `indicators[3].formula: Unknown name 'wieght' (character 5)`, in the person's
 * language, after `Set 'Other' · ` when the place is in another set than [owner].
 */
@Composable
internal fun issueText(issue: FormulaIssue, owner: String?, names: Map<String, String>, files: Map<String, FormulaSetFile> = emptyMap()): String {
    val problem = stringResource(issue.problem.kind.text, *issue.problem.args.toTypedArray())
    val where = issuePlace(issue.path, files[issue.set], LocalConfiguration.current.locales[0].language, files.values)
    val place = listOfNotNull(where, problem).filter { it.isNotEmpty() }.joinToString(": ")
    val text = issue.position?.let { stringResource(R.string.formula_issue_at, place, it + 1) } ?: place
    val other = issue.set.takeIf { it.isNotEmpty() && it != owner } ?: return text
    return stringResource(R.string.formula_issue_in_set, names[other] ?: other, text)
}

@Composable
private fun ImportDialog(check: ImportCheck, language: String, names: Map<String, String>, files: Map<String, FormulaSetFile>, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    when (check) {
        is ImportCheck.Rejected -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.formula_import_rejected_title)) },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.formula_import_rejected_text), style = MaterialTheme.typography.bodyMedium)
                    Issues(check.errors, error = true, check.set, names, files + listOfNotNull(check.file).associateBy { it.id })
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) } },
        )
        is ImportCheck.Accepted -> AlertDialog(
            onDismissRequest = onDismiss,
            title = {
                val title = if (check.replaces == null) R.string.formula_import_title_add else R.string.formula_import_title_replace
                Text(stringResource(title, check.file.name.text(language)))
            },
            text = {
                Column(
                    Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(contents(check.file), style = MaterialTheme.typography.bodyMedium)
                    check.file.description?.let { Text(it.text(language), style = MaterialTheme.typography.bodyMedium) }
                    check.replaces?.let { replaces ->
                        val text = if (replaces.enabled) R.string.formula_import_replace_text else R.string.formula_import_replace_off_text
                        Text(stringResource(text, check.file.id), style = MaterialTheme.typography.bodyMedium)
                    }
                    if (check.breaks.isNotEmpty()) {
                        Text(
                            stringResource(R.string.formula_import_breaks, check.breaks.joinToString { it.name }),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (check.warnings.isNotEmpty()) {
                        Text(stringResource(R.string.formula_set_warnings), style = MaterialTheme.typography.labelLarge)
                        Issues(check.warnings, error = false, check.file.id, names, files + (check.file.id to check.file))
                    }
                }
            },
            confirmButton = {
                val label = if (check.replaces == null) R.string.formula_import_add else R.string.formula_import_replace
                TextButton(onClick = onConfirm) { Text(stringResource(label)) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}
