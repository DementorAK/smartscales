package dev.kinash.smartscales.ui.profile

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DisplayMode
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kinash.smartscales.R
import dev.kinash.smartscales.core.model.LengthUnit
import dev.kinash.smartscales.core.model.Sex
import dev.kinash.smartscales.core.model.WeightUnit
import dev.kinash.smartscales.ui.common.BackButton
import dev.kinash.smartscales.ui.common.ChoiceRow
import dev.kinash.smartscales.ui.common.ConfirmDialog
import dev.kinash.smartscales.ui.common.DateField
import dev.kinash.smartscales.ui.common.FieldSpacing
import dev.kinash.smartscales.ui.common.NumberField
import dev.kinash.smartscales.ui.common.ProfileAvatar
import dev.kinash.smartscales.ui.common.ProfileColors
import dev.kinash.smartscales.ui.common.ScreenPadding
import dev.kinash.smartscales.ui.common.WeightField
import dev.kinash.smartscales.ui.common.symbol
import dev.kinash.smartscales.ui.common.title

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileEditScreen(onDone: () -> Unit, viewModel: ProfileEditViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.done) { if (state.done) onDone() }
    val form = state.form

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (state.isNew) R.string.profile_new else R.string.profile_edit)) },
                navigationIcon = { BackButton(onDone) },
                actions = {
                    if (!state.isNew) {
                        IconButton(onClick = viewModel::requestDelete) {
                            Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.profile_delete))
                        }
                    }
                    TextButton(onClick = viewModel::save, enabled = state.loaded && !state.saving) {
                        Text(stringResource(R.string.action_save))
                    }
                },
            )
        },
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
            OutlinedTextField(
                value = form.name,
                onValueChange = { name -> viewModel.edit { it.copy(name = name) } },
                label = { Text(stringResource(R.string.profile_name)) },
                isError = ProfileField.NAME in state.errors,
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("profile_name"),
            )
            SectionLabel(stringResource(R.string.profile_sex), isError = ProfileField.SEX in state.errors)
            ChoiceRow(
                options = Sex.entries,
                selected = form.sex,
                onSelect = { sex -> viewModel.edit { it.copy(sex = sex) } },
                label = { stringResource(if (it == Sex.MALE) R.string.sex_male else R.string.sex_female) },
            )
            DateField(
                value = form.birthDate,
                onValueChange = { date -> viewModel.edit { it.copy(birthDate = date) } },
                label = stringResource(R.string.profile_birth_date),
                isError = ProfileField.BIRTH_DATE in state.errors,
                // Typing a birth date is quicker than paging back through decades, so no calendar at all
                initialDisplayMode = DisplayMode.Input,
                showModeToggle = false,
                modifier = Modifier.fillMaxWidth(),
            )
            NumberField(
                value = form.height,
                onValueChange = { height -> viewModel.edit { it.copy(height = height) } },
                label = stringResource(R.string.profile_height),
                suffix = stringResource(form.lengthUnit.symbol()),
                isError = ProfileField.HEIGHT in state.errors,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("profile_height"),
            )
            SectionLabel(stringResource(R.string.profile_weight_unit))
            ChoiceRow(
                options = WeightUnit.entries,
                selected = form.weightUnit,
                onSelect = viewModel::setWeightUnit,
                label = { stringResource(it.title()) },
            )
            SectionLabel(stringResource(R.string.profile_length_unit))
            ChoiceRow(
                options = LengthUnit.entries,
                selected = form.lengthUnit,
                onSelect = viewModel::setLengthUnit,
                label = { stringResource(it.title()) },
            )
            WeightField(
                value = form.goal,
                onValueChange = { goal -> viewModel.edit { it.copy(goal = goal) } },
                unit = form.weightUnit,
                label = stringResource(R.string.profile_goal_weight),
                isError = ProfileField.GOAL in state.errors,
                modifier = Modifier.fillMaxWidth(),
            )
            SectionLabel(stringResource(R.string.profile_color))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(FieldSpacing)) {
                ProfileColors.forEach { color ->
                    val selected = color == form.color
                    ProfileAvatar(
                        name = form.name,
                        avatar = form.avatar,
                        color = color,
                        size = 40.dp,
                        modifier = Modifier
                            .border(if (selected) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                            .clickable { viewModel.edit { it.copy(color = color) } },
                    )
                }
            }
            OutlinedTextField(
                value = form.avatar,
                onValueChange = { avatar -> viewModel.edit { it.copy(avatar = avatar.take(MAX_AVATAR_LENGTH)) } },
                label = { Text(stringResource(R.string.profile_avatar)) },
                supportingText = { Text(stringResource(R.string.profile_avatar_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    state.applyToPast?.let { question ->
        val blocked = question.beforeBirthDate > 0
        AlertDialog(
            onDismissRequest = viewModel::dismissApplyToPast,
            title = { Text(stringResource(R.string.profile_apply_to_past_title)) },
            text = {
                Text(
                    if (blocked) {
                        pluralStringResource(
                            R.plurals.profile_apply_to_past_blocked,
                            question.beforeBirthDate,
                            question.beforeBirthDate,
                        )
                    } else {
                        stringResource(R.string.profile_apply_to_past_text)
                    },
                )
            },
            confirmButton = {
                if (blocked) {
                    TextButton(onClick = { viewModel.applyToPast(false) }) {
                        Text(stringResource(R.string.profile_apply_to_past_no))
                    }
                } else {
                    TextButton(onClick = { viewModel.applyToPast(true) }) {
                        Text(stringResource(R.string.profile_apply_to_past_yes))
                    }
                }
            },
            dismissButton = {
                if (blocked) {
                    TextButton(onClick = viewModel::dismissApplyToPast) {
                        Text(stringResource(android.R.string.cancel))
                    }
                } else {
                    TextButton(onClick = { viewModel.applyToPast(false) }) {
                        Text(stringResource(R.string.profile_apply_to_past_no))
                    }
                }
            },
        )
    }

    state.deleteWarning?.let { count ->
        ConfirmDialog(
            title = stringResource(R.string.profile_delete_title, form.name),
            text = if (count == 0) {
                stringResource(R.string.profile_delete_text_empty)
            } else {
                pluralStringResource(R.plurals.profile_delete_text, count, count)
            },
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = viewModel::confirmDelete,
            onDismiss = viewModel::dismissDelete,
        )
    }
}

@Composable
private fun SectionLabel(text: String, isError: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = FieldSpacing),
    )
}

/** An emoji may take several characters. */
private const val MAX_AVATAR_LENGTH = 8
