package dev.kinash.smartscales.ui.formulas

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kinash.smartscales.R
import dev.kinash.smartscales.core.data.repository.ChildNorms
import dev.kinash.smartscales.core.formula.GrowthIndicator
import dev.kinash.smartscales.core.formula.WhoGrowthCharts
import dev.kinash.smartscales.core.formula.WhoTable
import dev.kinash.smartscales.core.formula.WhoTableProblem
import dev.kinash.smartscales.core.model.Sex
import dev.kinash.smartscales.ui.common.BackButton
import dev.kinash.smartscales.ui.common.ConfirmDialog
import dev.kinash.smartscales.ui.common.FieldSpacing
import dev.kinash.smartscales.ui.common.ScreenPadding
import kotlin.math.roundToInt

/**
 * The norms children are placed on (requirements §3): the built-in CDC 2000 charts, or the WHO tables the person
 * downloads from WHO and imports here, which WHO's licence keeps out of the app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChildNormsScreen(onBack: () -> Unit, viewModel: ChildNormsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val result by viewModel.result.collectAsStateWithLifecycle()
    val importing by viewModel.importing.collectAsStateWithLifecycle()
    var clearing by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { viewModel.import(it) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.child_norms_title)) }, navigationIcon = { BackButton(onBack) })
        },
    ) { padding ->
        val current = state ?: return@Scaffold
        val hasWho = current.who.tables.isNotEmpty()
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(FieldSpacing),
        ) {
            Hint(stringResource(R.string.child_norms_hint))
            Column(Modifier.selectableGroup()) {
                NormsOption(
                    title = stringResource(R.string.child_norms_cdc),
                    hint = stringResource(R.string.child_norms_cdc_hint),
                    selected = current.norms == ChildNorms.CDC,
                    enabled = true,
                    onSelect = { viewModel.choose(ChildNorms.CDC) },
                )
                NormsOption(
                    title = stringResource(R.string.child_norms_who),
                    hint = stringResource(if (hasWho) R.string.child_norms_who_hint else R.string.child_norms_who_none),
                    selected = current.norms == ChildNorms.WHO,
                    enabled = hasWho,
                    onSelect = { viewModel.choose(ChildNorms.WHO) },
                )
            }
            Text(
                stringResource(R.string.child_norms_tables),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = FieldSpacing),
            )
            GrowthIndicator.entries.forEach { indicator -> TableLine(current.who, indicator) }
            Hint(stringResource(R.string.child_norms_download))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(FieldSpacing)) {
                OutlinedButton(onClick = { launcher.launch(arrayOf("*/*")) }, enabled = !importing) {
                    Text(stringResource(R.string.child_norms_import))
                }
                if (hasWho) {
                    TextButton(onClick = { clearing = true }) { Text(stringResource(R.string.child_norms_clear)) }
                }
            }
            Hint(stringResource(R.string.child_norms_licence))
        }
    }

    result?.let { ImportResultDialog(it, viewModel::resultShown) }
    if (clearing) {
        ConfirmDialog(
            title = stringResource(R.string.child_norms_clear_title),
            text = stringResource(R.string.child_norms_clear_text),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = {
                viewModel.clear()
                clearing = false
            },
            onDismiss = { clearing = false },
        )
    }
}

@Composable
private fun NormsOption(title: String, hint: String, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Hint(hint)
        }
    }
}

/** "BMI: boys 0–19 years, girls none". */
@Composable
private fun TableLine(who: WhoGrowthCharts, indicator: GrowthIndicator) {
    val parts = ArrayList<String>()
    for (sex in listOf(Sex.MALE, Sex.FEMALE)) {
        val range = who.ages(indicator, sex)
        val ages = if (range != null) yearsText(range) else stringResource(R.string.child_norms_no_table)
        parts += stringResource(if (sex == Sex.MALE) R.string.child_norms_boys else R.string.child_norms_girls, ages)
    }
    val sexes = parts.joinToString(", ")
    Text(stringResource(R.string.child_norms_table_line, stringResource(indicator.title()), sexes), style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun yearsText(months: ClosedFloatingPointRange<Double>): String {
    val to = (months.endInclusive / 12).roundToInt()
    return pluralStringResource(R.plurals.child_norms_years, to, (months.start / 12).roundToInt(), to)
}

@StringRes
private fun GrowthIndicator.title(): Int = when (this) {
    GrowthIndicator.BMI -> R.string.child_norms_bmi
    GrowthIndicator.WEIGHT -> R.string.child_norms_weight
    GrowthIndicator.HEIGHT -> R.string.child_norms_height
}

@StringRes
private fun WhoTableProblem.text(): Int = when (this) {
    WhoTableProblem.NOT_XLSX -> R.string.who_problem_not_xlsx
    WhoTableProblem.UNKNOWN_TABLE -> R.string.who_problem_unknown
    WhoTableProblem.NO_LMS -> R.string.who_problem_no_lms
    WhoTableProblem.IMPLAUSIBLE -> R.string.who_problem_implausible
}

@Composable
private fun ImportResultDialog(result: WhoImportResult, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (result.taken.isEmpty()) R.string.child_norms_import_none else R.string.child_norms_import_done)) },
        text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!result.saved) Text(stringResource(R.string.child_norms_import_failed), color = MaterialTheme.colorScheme.error)
                result.taken.forEach { table -> Text("• " + tableText(table), style = MaterialTheme.typography.bodyMedium) }
                if (result.rejected.isNotEmpty()) {
                    Text(stringResource(R.string.child_norms_import_rejected), style = MaterialTheme.typography.labelLarge)
                    result.rejected.forEach { (name, problem) ->
                        Text("• $name: " + stringResource(problem.text()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) } },
    )
}

/** "BMI, boys 0–5 years". */
@Composable
private fun tableText(table: WhoTable): String {
    val ages = yearsText(table.rows.first().ageMonths..table.rows.last().ageMonths)
    val sex = stringResource(if (table.sex == Sex.MALE) R.string.child_norms_boys else R.string.child_norms_girls, ages)
    return stringResource(R.string.child_norms_table_taken, stringResource(table.indicator.title()), sex)
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
