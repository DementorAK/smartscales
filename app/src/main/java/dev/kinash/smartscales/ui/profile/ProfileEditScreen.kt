package dev.kinash.smartscales.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kinash.smartscales.R
import dev.kinash.smartscales.core.model.Sex
import dev.kinash.smartscales.core.model.WeightUnit
import dev.kinash.smartscales.ui.common.BackButton
import dev.kinash.smartscales.ui.common.ChoiceField
import dev.kinash.smartscales.ui.common.ChoiceRow
import dev.kinash.smartscales.ui.common.ConfirmDialog
import dev.kinash.smartscales.ui.common.DateField
import dev.kinash.smartscales.ui.common.FieldSpacing
import dev.kinash.smartscales.ui.common.LengthUnitMenu
import dev.kinash.smartscales.ui.common.NumberField
import dev.kinash.smartscales.ui.common.ProfileAvatar
import dev.kinash.smartscales.ui.common.ProfileColors
import dev.kinash.smartscales.ui.common.ScreenPadding
import dev.kinash.smartscales.ui.common.WeightField
import dev.kinash.smartscales.ui.common.avatarText
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
            // The circle as it will look, with everything that shapes it next to it
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ScreenPadding)) {
                // The field keeps what is typed, so input methods that compose text work; the circle shows what fits
                ProfileAvatar(name = form.name, avatar = avatarText(form.avatar), color = form.color, size = 56.dp)
                OutlinedTextField(
                    value = form.avatar,
                    onValueChange = { avatar -> viewModel.edit { it.copy(avatar = avatar) } },
                    label = { Text(stringResource(R.string.profile_avatar)) },
                    supportingText = { Text(stringResource(R.string.profile_avatar_hint)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(FieldSpacing),
                verticalArrangement = Arrangement.spacedBy(FieldSpacing),
            ) {
                ProfileColors.forEach { color ->
                    ColorSwatch(
                        color = color,
                        selected = color == form.color,
                        onClick = { viewModel.edit { it.copy(color = color) } },
                    )
                }
            }
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
            if (state.bornAfterWeighing) {
                Text(
                    stringResource(R.string.profile_born_after_weighing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            // Each unit sits by its value: length in the height field, weight next to it for the whole profile
            Row(horizontalArrangement = Arrangement.spacedBy(FieldSpacing)) {
                NumberField(
                    value = form.height,
                    onValueChange = { height -> viewModel.edit { it.copy(height = height) } },
                    label = stringResource(R.string.profile_height),
                    suffix = stringResource(form.lengthUnit.symbol()),
                    isError = ProfileField.HEIGHT in state.errors,
                    trailingUnit = { LengthUnitMenu(form.lengthUnit, viewModel::setLengthUnit) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("profile_height"),
                )
                ChoiceField(
                    label = stringResource(R.string.profile_weight_unit),
                    selected = form.weightUnit,
                    options = WeightUnit.entries,
                    onSelect = viewModel::setWeightUnit,
                    title = { stringResource(it.title()) },
                    modifier = Modifier.weight(1f),
                )
            }
            WeightField(
                value = form.goal,
                onValueChange = { goal -> viewModel.edit { it.copy(goal = goal) } },
                unit = form.weightUnit,
                label = stringResource(R.string.profile_goal_weight),
                isError = ProfileField.GOAL in state.errors,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.profile_units_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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

/** A profile colour; the chosen one carries a check mark. */
@Composable
private fun ColorSwatch(color: Int, selected: Boolean, onClick: () -> Unit) {
    val background = Color(color)
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                painterResource(R.drawable.ic_check),
                contentDescription = null,
                tint = if (background.luminance() > 0.5f) Color.Black else Color.White,
            )
        }
    }
}
