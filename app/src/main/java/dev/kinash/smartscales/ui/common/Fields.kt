package dev.kinash.smartscales.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DisplayMode
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.kinash.smartscales.R
import dev.kinash.smartscales.core.model.WeightUnit
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Number field with a unit suffix. */
@Composable
fun NumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    suffix: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    decimal: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        suffix = { Text(suffix) },
        isError = isError,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
        modifier = modifier,
    )
}

/** Weight in the profile's unit; stones take two fields, whole stones and pounds. */
@Composable
fun WeightField(
    value: WeightInput,
    onValueChange: (WeightInput) -> Unit,
    unit: WeightUnit,
    label: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
) {
    if (unit == WeightUnit.STONE) {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(FieldSpacing)) {
            NumberField(
                value = value.main,
                onValueChange = { onValueChange(value.copy(main = it)) },
                label = label,
                suffix = stringResource(R.string.unit_st),
                isError = isError,
                decimal = false,
                modifier = Modifier.weight(1f),
            )
            NumberField(
                value = value.pounds,
                onValueChange = { onValueChange(value.copy(pounds = it)) },
                label = "",
                suffix = stringResource(R.string.unit_lb),
                isError = isError,
                modifier = Modifier.weight(1f),
            )
        }
    } else {
        NumberField(
            value = value.main,
            onValueChange = { onValueChange(value.copy(main = it)) },
            label = label,
            suffix = stringResource(unit.symbol()),
            isError = isError,
            modifier = modifier,
        )
    }
}

/** Read-only field that opens [onClick] when tapped. */
@Composable
private fun PickerField(value: String, label: String, onClick: () -> Unit, modifier: Modifier, isError: Boolean) {
    Box(modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            isError = isError,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        // The text field consumes taps itself, so a transparent layer on top opens the picker
        Box(
            Modifier
                .matchParentSize()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(
    value: LocalDate?,
    onValueChange: (LocalDate) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    initialDisplayMode: DisplayMode = DisplayMode.Picker,
    showModeToggle: Boolean = true,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    PickerField(
        value = value?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)) ?: "",
        label = label,
        onClick = { open = true },
        modifier = modifier,
        isError = isError,
    )
    if (open) {
        // DatePicker works with UTC midnight milliseconds
        val state = rememberDatePickerState(
            initialSelectedDateMillis = value?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
            initialDisplayMode = initialDisplayMode,
        )
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        state.selectedDateMillis?.let {
                            onValueChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())
                        }
                        open = false
                    },
                ) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { open = false }) { Text(stringResource(android.R.string.cancel)) }
            },
        ) {
            // The field label takes the place of the generic "Select date" title and "Entered date" headline;
            // the header keeps its height even when empty, so the label fills it instead
            DatePicker(
                state = state,
                title = null,
                headline = {
                    Text(
                        label,
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.padding(start = 24.dp, end = 12.dp, top = 24.dp, bottom = 12.dp),
                    )
                },
                showModeToggle = showModeToggle,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeField(
    value: LocalTime,
    onValueChange: (LocalTime) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    PickerField(
        value = value.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)),
        label = label,
        onClick = { open = true },
        modifier = modifier,
        isError = false,
    )
    if (open) {
        val state = rememberTimePickerState(initialHour = value.hour, initialMinute = value.minute)
        AlertDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        onValueChange(LocalTime.of(state.hour, state.minute))
                        open = false
                    },
                ) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { open = false }) { Text(stringResource(android.R.string.cancel)) }
            },
            title = { Text(label) },
            text = { TimePicker(state = state) },
        )
    }
}

/** Single choice from a few options, such as sex or units. */
@Composable
fun <T> ChoiceRow(
    options: List<T>,
    selected: T?,
    onSelect: (T) -> Unit,
    label: @Composable (T) -> String,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
            ) { Text(label(option)) }
        }
    }
}
