package dev.kinash.smartscales.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kinash.smartscales.R
import dev.kinash.smartscales.core.model.LengthUnit
import dev.kinash.smartscales.importing.DateFormat
import dev.kinash.smartscales.importing.ImportField
import dev.kinash.smartscales.importing.ImportPlan
import dev.kinash.smartscales.importing.ImportWeightUnit
import dev.kinash.smartscales.importing.TableSource
import dev.kinash.smartscales.ui.common.BackButton
import dev.kinash.smartscales.ui.common.ChoiceField
import dev.kinash.smartscales.ui.common.FieldSpacing
import dev.kinash.smartscales.ui.common.ScreenPadding
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Weighings from a table of OKOK International, openScale, this app or any spreadsheet (requirements §4.3). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(onBack: () -> Unit, viewModel: ImportViewModel = hiltViewModel()) {
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val plan by viewModel.plan.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::open) }
    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        snackbar.showSnackbar(text)
        viewModel.messageShown()
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.import_title)) }, navigationIcon = { BackButton(onBack) }) },
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
            val current = draft
            if (current == null) {
                Text(stringResource(R.string.import_hint), style = MaterialTheme.typography.bodyMedium)
                Button(onClick = { launcher.launch(arrayOf("*/*")) }, enabled = !busy) { Text(stringResource(R.string.import_pick)) }
            } else {
                Preview(current, plan, profiles.map { it.id to it.name }, busy, viewModel)
            }
        }
    }
}

@Composable
private fun Preview(draft: ImportDraft, plan: ImportPlan?, profiles: List<Pair<Long, String>>, busy: Boolean, viewModel: ImportViewModel) {
    val layout = draft.layout
    Text(stringResource(R.string.import_file, draft.name, stringResource(layout.source.title())), style = MaterialTheme.typography.bodyMedium)
    if (layout.source == TableSource.SMARTSCALES) Hint(stringResource(R.string.import_own_table))

    Title(R.string.import_profile)
    Column(Modifier.selectableGroup()) {
        profiles.forEach { (id, name) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(selected = id == draft.profileId, onClick = { viewModel.change { it.copy(profileId = id) } }, role = Role.RadioButton)
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = id == draft.profileId, onClick = null)
                Text(name, Modifier.padding(start = FieldSpacing))
            }
        }
    }

    Title(R.string.import_reading)
    ChoiceField(
        label = stringResource(R.string.import_weight_unit),
        selected = layout.weightUnit,
        options = ImportWeightUnit.entries,
        onSelect = { unit -> viewModel.change { it.copy(layout = it.layout.copy(weightUnit = unit)) } },
        title = { stringResource(it.title()) },
        modifier = Modifier.fillMaxWidth(),
    )
    if (layout.columns.any { it.kind == ImportField.Kind.LENGTH }) {
        ChoiceField(
            label = stringResource(R.string.import_length_unit),
            selected = layout.lengthUnit,
            options = LengthUnit.entries,
            onSelect = { unit -> viewModel.change { it.copy(layout = it.layout.copy(lengthUnit = unit)) } },
            title = { stringResource(if (it == LengthUnit.INCH) R.string.import_unit_in else R.string.import_unit_cm) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    // OKOK's dates without a year are read one way only
    if (layout.dateFormat != DateFormat.MONTH_DAY) {
        ChoiceField(
            label = stringResource(R.string.import_date_format),
            selected = layout.dateFormat,
            options = DateFormat.entries.filter { it != DateFormat.MONTH_DAY },
            onSelect = { format -> viewModel.change { it.copy(layout = it.layout.copy(dateFormat = format)) } },
            title = { stringResource(it.title()) },
            modifier = Modifier.fillMaxWidth(),
        )
    }

    Title(R.string.import_columns)
    Hint(stringResource(R.string.import_columns_hint))
    val header = draft.table.rows.getOrNull(layout.headerRow).orEmpty()
    val sample = draft.table.rows.getOrNull(layout.firstDataRow).orEmpty()
    layout.columns.forEachIndexed { column, field ->
        val name = header.getOrNull(column).orEmpty().ifBlank { stringResource(R.string.import_column, column + 1) }
        val example = sample.getOrNull(column).orEmpty()
        ChoiceField(
            label = if (example.isBlank()) name else stringResource(R.string.import_column_example, name, example),
            selected = field,
            options = ImportField.entries,
            onSelect = { chosen ->
                viewModel.change { d -> d.copy(layout = d.layout.copy(columns = d.layout.columns.mapIndexed { i, f -> if (i == column) chosen else f })) }
            },
            title = { stringResource(it.title()) },
            modifier = Modifier.fillMaxWidth().testTag("import_column_$column"),
        )
    }

    Title(R.string.import_result)
    when {
        draft.profileId == null -> Hint(stringResource(R.string.import_no_profile))
        plan == null -> Hint(stringResource(R.string.import_checking))
        else -> {
            val first = plan.add.firstOrNull()?.takenAt?.atZone(plan.add.first().zone)
            val last = plan.add.lastOrNull()?.takenAt?.atZone(plan.add.last().zone)
            val format = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
            Text(
                pluralStringResource(R.plurals.import_will_add, plan.add.size, plan.add.size) +
                    if (first != null && last != null) " " + stringResource(R.string.import_period, first.format(format), last.format(format)) else "",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("import_summary"),
            )
            if (plan.duplicates > 0) Hint(pluralStringResource(R.plurals.import_duplicates, plan.duplicates, plan.duplicates))
            if (plan.beforeBirth > 0) Hint(pluralStringResource(R.plurals.import_before_birth, plan.beforeBirth, plan.beforeBirth))
            if (plan.unreadable.isNotEmpty()) {
                // Rows as a spreadsheet numbers them, from 1
                val rows = plan.unreadable.take(MAX_ROWS_SHOWN).joinToString(", ") { (it + 1).toString() } + if (plan.unreadable.size > MAX_ROWS_SHOWN) "…" else ""
                Hint(pluralStringResource(R.plurals.import_unreadable_rows, plan.unreadable.size, plan.unreadable.size, rows))
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(FieldSpacing)) {
        Button(onClick = viewModel::import, enabled = !busy && (plan?.add?.isNotEmpty() == true)) { Text(stringResource(R.string.import_do)) }
        OutlinedButton(onClick = viewModel::cancel, enabled = !busy) { Text(stringResource(android.R.string.cancel)) }
    }
}

private const val MAX_ROWS_SHOWN = 10

@Composable
private fun Title(@StringRes text: Int) {
    Text(stringResource(text), Modifier.padding(top = FieldSpacing), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@StringRes
private fun TableSource.title(): Int = when (this) {
    TableSource.OKOK -> R.string.import_source_okok
    TableSource.OPENSCALE -> R.string.import_source_openscale
    TableSource.SMARTSCALES -> R.string.import_source_smartscales
    TableSource.TABLE -> R.string.import_source_table
}

@StringRes
private fun ImportWeightUnit.title(): Int = when (this) {
    ImportWeightUnit.KG -> R.string.import_unit_kg
    ImportWeightUnit.LB -> R.string.import_unit_lb
    ImportWeightUnit.ST -> R.string.import_unit_st
    ImportWeightUnit.JIN -> R.string.import_unit_jin
}

@StringRes
private fun DateFormat.title(): Int = when (this) {
    DateFormat.ISO -> R.string.import_date_iso
    DateFormat.DAY_MONTH_DOT -> R.string.import_date_dot
    DateFormat.DAY_MONTH_SLASH -> R.string.import_date_day_slash
    DateFormat.MONTH_DAY_SLASH -> R.string.import_date_month_slash
    DateFormat.YEAR_SLASH -> R.string.import_date_year_slash
    DateFormat.EXCEL -> R.string.import_date_excel
    DateFormat.MONTH_DAY -> R.string.import_date_okok
}

@StringRes
private fun ImportField.title(): Int = when (this) {
    ImportField.IGNORE -> R.string.import_field_ignore
    ImportField.DATE_TIME -> R.string.import_field_date_time
    ImportField.DATE -> R.string.import_field_date
    ImportField.TIME -> R.string.import_field_time
    ImportField.WEIGHT -> R.string.import_field_weight
    ImportField.IMPEDANCE -> R.string.import_field_impedance
    ImportField.HEART_RATE -> R.string.import_field_heart_rate
    ImportField.WAIST -> R.string.measurement_waist
    ImportField.HIP -> R.string.measurement_hip
    ImportField.NECK -> R.string.measurement_neck
    ImportField.NOTE -> R.string.import_field_note
    ImportField.TAGS -> R.string.import_field_tags
    ImportField.FAT_PERCENT -> R.string.import_field_fat
    ImportField.WATER_PERCENT -> R.string.import_field_water
    ImportField.WATER_LITERS -> R.string.import_field_water_liters
    ImportField.MUSCLE_PERCENT -> R.string.import_field_muscle
    ImportField.MUSCLE_KG -> R.string.import_field_muscle_kg
    ImportField.SKELETAL_MUSCLE_PERCENT -> R.string.import_field_skeletal
    ImportField.LEAN_MASS_KG -> R.string.import_field_lean
    ImportField.BONE_KG -> R.string.import_field_bone
    ImportField.PROTEIN_PERCENT -> R.string.import_field_protein
    ImportField.VISCERAL_FAT -> R.string.import_field_visceral
    ImportField.BMR_KCAL -> R.string.import_field_bmr
    ImportField.BODY_AGE -> R.string.import_field_body_age
}
