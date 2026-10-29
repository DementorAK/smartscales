package dev.kinash.smartscales.ui.measurement

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kinash.smartscales.R
import dev.kinash.smartscales.core.model.ManualFields
import dev.kinash.smartscales.ui.common.BackButton
import dev.kinash.smartscales.ui.common.ConfirmDialog
import dev.kinash.smartscales.ui.common.DateField
import dev.kinash.smartscales.ui.common.FieldSpacing
import dev.kinash.smartscales.ui.common.NumberField
import dev.kinash.smartscales.ui.common.ProfileAvatar
import dev.kinash.smartscales.ui.common.ProfileChoiceList
import dev.kinash.smartscales.ui.common.ScreenPadding
import dev.kinash.smartscales.ui.common.TimeField
import dev.kinash.smartscales.ui.common.WeightField
import dev.kinash.smartscales.ui.common.symbol

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeasurementEditScreen(onDone: () -> Unit, viewModel: MeasurementEditViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val tagChoices by viewModel.tagChoices.collectAsStateWithLifecycle()
    LaunchedEffect(state.done) { if (state.done) onDone() }
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    LaunchedEffect(state.transferredTo) {
        val name = state.transferredTo ?: return@LaunchedEffect
        // Cleared only after showing: clearing first changes the key and cancels this effect
        snackbar.showSnackbar(resources.getString(R.string.measurement_transferred, name))
        viewModel.transferMessageShown()
    }
    val form = state.form

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(if (state.isNew) R.string.measurement_new else R.string.measurement_edit))
                },
                navigationIcon = { BackButton(onDone) },
                actions = {
                    if (!state.isNew) {
                        IconButton(onClick = viewModel::requestTransfer) {
                            Icon(painterResource(R.drawable.ic_move), stringResource(R.string.measurement_transfer))
                        }
                        IconButton(onClick = viewModel::requestDelete) {
                            Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.measurement_delete))
                        }
                    }
                    TextButton(onClick = viewModel::save, enabled = state.loaded && !state.saving) {
                        Text(stringResource(R.string.action_save))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (!state.loaded) return@Scaffold
        Column(
            Modifier
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(FieldSpacing),
        ) {
            val profile = state.profile
            if (profile != null) {
                ListItem(
                    headlineContent = { Text(profile.name) },
                    leadingContent = { ProfileAvatar(profile) },
                )
            } else {
                Text(stringResource(R.string.inbox_not_assigned), style = MaterialTheme.typography.bodyMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(FieldSpacing)) {
                DateField(
                    value = form.date,
                    onValueChange = { date -> viewModel.edit { it.copy(date = date) } },
                    label = stringResource(R.string.measurement_date),
                    isError = MeasurementField.DATE_TIME in state.errors,
                    modifier = Modifier.weight(1f),
                )
                TimeField(
                    value = form.time,
                    onValueChange = { time -> viewModel.edit { it.copy(time = time) } },
                    label = stringResource(R.string.measurement_time),
                    modifier = Modifier.weight(1f),
                )
            }
            if (MeasurementField.BEFORE_BIRTH in state.errors) {
                Text(
                    stringResource(R.string.measurement_before_birth),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            WeightField(
                value = form.weight,
                onValueChange = { weight -> viewModel.edit { it.copy(weight = weight) } },
                unit = state.weightUnit,
                label = stringResource(R.string.measurement_weight),
                isError = MeasurementField.WEIGHT in state.errors,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("measurement_weight"),
            )
            if (state.hasSnapshot) {
                NumberField(
                    value = form.height,
                    onValueChange = { height -> viewModel.edit { it.copy(height = height) } },
                    label = stringResource(R.string.measurement_height),
                    suffix = stringResource(state.lengthUnit.symbol()),
                    isError = MeasurementField.HEIGHT in state.errors,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text(
                stringResource(R.string.measurement_circumferences),
                style = MaterialTheme.typography.labelLarge,
                color = if (MeasurementField.CIRCUMFERENCES in state.errors) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(top = FieldSpacing),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(FieldSpacing)) {
                CircumferenceFields.forEach { field ->
                    NumberField(
                        value = form.circumferences[field].orEmpty(),
                        onValueChange = { text ->
                            viewModel.edit { it.copy(circumferences = it.circumferences + (field to text)) }
                        },
                        label = stringResource(circumferenceLabel(field)),
                        suffix = stringResource(state.lengthUnit.symbol()),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            OutlinedTextField(
                value = form.note,
                onValueChange = { note -> viewModel.edit { it.copy(note = note) } },
                label = { Text(stringResource(R.string.measurement_note)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.measurement_tags),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = FieldSpacing),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(FieldSpacing)) {
                tagChoices.forEach { tag ->
                    FilterChip(
                        selected = form.tags.any { it.equals(tag, ignoreCase = true) },
                        onClick = { viewModel.toggleTag(tag) },
                        label = { Text(tag) },
                    )
                }
            }
            OutlinedTextField(
                value = form.newTag,
                onValueChange = { tag -> viewModel.edit { it.copy(newTag = tag) } },
                label = { Text(stringResource(R.string.measurement_new_tag)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { viewModel.addNewTag() }),
                trailingIcon = {
                    IconButton(onClick = viewModel::addNewTag, enabled = form.newTag.isNotBlank()) {
                        Icon(painterResource(R.drawable.ic_add), stringResource(R.string.measurement_add_tag))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    if (state.confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.measurement_delete_title),
            text = stringResource(R.string.measurement_delete_text),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = viewModel::confirmDelete,
            onDismiss = viewModel::dismissDelete,
        )
    }

    state.transferTargets?.let { targets ->
        AlertDialog(
            onDismissRequest = viewModel::dismissTransfer,
            title = { Text(stringResource(R.string.measurement_transfer_title)) },
            text = {
                if (targets.isEmpty()) {
                    Text(stringResource(R.string.measurement_transfer_no_profiles))
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(ScreenPadding)) {
                        Text(stringResource(R.string.measurement_transfer_text))
                        ProfileChoiceList(
                            profiles = targets,
                            onSelect = viewModel::transfer,
                            isEnabled = { it.isBornBy(form.date) },
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = viewModel::dismissTransfer) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }
}

private fun circumferenceLabel(field: String): Int = when (field) {
    ManualFields.WAIST_CM -> R.string.measurement_waist
    ManualFields.HIP_CM -> R.string.measurement_hip
    else -> R.string.measurement_neck
}
