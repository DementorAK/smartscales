package dev.kinash.smartscales.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DisplayMode
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.kinash.smartscales.R
import dev.kinash.smartscales.core.model.LengthUnit
import dev.kinash.smartscales.core.model.WeightUnit
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Number field with a unit suffix. With [trailingUnit] the unit is shown at the end of the field instead:
 * unlike a suffix it shows while the field is still empty, and it may be a button that changes the unit.
 * Without a [label] the suffix shows all the time.
 */
@Composable
fun NumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String?,
    suffix: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    decimal: Boolean = true,
    trailingUnit: (@Composable () -> Unit)? = null,
    /** A line under the field, such as what to enter. */
    supportingText: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = label?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        suffix = if (trailingUnit == null) { { Text(suffix) } } else null,
        trailingIcon = trailingUnit,
        supportingText = supportingText?.let { { Text(it) } },
        isError = isError,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
        modifier = modifier,
    )
}

/** A unit symbol at the end of a field, see `trailingUnit` of [NumberField]. */
@Composable
fun UnitLabel(text: String) {
    Text(text, Modifier.padding(end = ScreenPadding))
}

/** Unit button inside a field: shows [symbol] of the [selected] unit and offers [options] by [title]. */
@Composable
fun <T> UnitMenu(
    selected: T,
    options: List<T>,
    onSelect: (T) -> Unit,
    symbol: @Composable (T) -> String,
    title: @Composable (T) -> String,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Text(symbol(selected))
            Icon(painterResource(R.drawable.ic_arrow_drop_down), contentDescription = null)
        }
        OptionsMenu(open, options, selected, title, onSelect, onDismiss = { open = false })
    }
}

/** Unit button for lengths; see [UnitMenu]. */
@Composable
fun LengthUnitMenu(selected: LengthUnit, onSelect: (LengthUnit) -> Unit) = UnitMenu(
    selected = selected,
    options = LengthUnit.entries,
    onSelect = onSelect,
    symbol = { stringResource(it.symbol()) },
    title = { stringResource(it.title()) },
)

/** Field that shows the [selected] option by [title] and offers [options] in a drop-down list. */
@Composable
fun <T> ChoiceField(
    label: String,
    selected: T,
    options: List<T>,
    onSelect: (T) -> Unit,
    title: @Composable (T) -> String,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        PickerField(
            value = title(selected),
            label = label,
            onClick = { open = true },
            modifier = Modifier.fillMaxWidth(),
            isError = false,
            trailingIcon = { Icon(painterResource(R.drawable.ic_arrow_drop_down), contentDescription = null) },
        )
        OptionsMenu(open, options, selected, title, onSelect, onDismiss = { open = false })
    }
}

@Composable
private fun <T> OptionsMenu(
    open: Boolean,
    options: List<T>,
    selected: T,
    title: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    DropdownMenu(expanded = open, onDismissRequest = onDismiss) {
        options.forEach { option ->
            DropdownMenuItem(
                text = { Text(title(option)) },
                onClick = {
                    onDismiss()
                    onSelect(option)
                },
                leadingIcon = if (option == selected) {
                    { Icon(painterResource(R.drawable.ic_check), contentDescription = null) }
                } else {
                    null
                },
            )
        }
    }
}

/** Weight in the profile's unit, always shown; stones take two fields, whole stones and pounds. */
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
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(FieldSpacing), verticalAlignment = Alignment.Bottom) {
            NumberField(
                value = value.main,
                onValueChange = { onValueChange(value.copy(main = it)) },
                label = label,
                suffix = stringResource(R.string.unit_st),
                isError = isError,
                decimal = false,
                trailingUnit = { UnitLabel(stringResource(R.string.unit_st)) },
                modifier = Modifier.weight(3f),
            )
            NumberField(
                value = value.pounds,
                onValueChange = { onValueChange(value.copy(pounds = it)) },
                label = null,
                suffix = stringResource(R.string.unit_lb),
                isError = isError,
                modifier = Modifier.weight(2f),
            )
        }
    } else {
        NumberField(
            value = value.main,
            onValueChange = { onValueChange(value.copy(main = it)) },
            label = label,
            suffix = stringResource(unit.symbol()),
            isError = isError,
            trailingUnit = { UnitLabel(stringResource(unit.symbol())) },
            modifier = modifier,
        )
    }
}

/** Read-only field that opens [onClick] when tapped. */
@Composable
private fun PickerField(
    value: String,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier,
    isError: Boolean,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    Box(modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = trailingIcon,
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

/** A switch with a title and an optional hint; the whole row toggles it. */
@Composable
fun SwitchRow(title: String, hint: String?, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
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

/** A check box with a title and an optional hint; the whole row toggles it. */
@Composable
fun CheckboxRow(title: String, hint: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
