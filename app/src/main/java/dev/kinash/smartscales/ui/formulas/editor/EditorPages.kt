package dev.kinash.smartscales.ui.formulas.editor

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.kinash.smartscales.R
import dev.kinash.smartscales.core.formula.BoundSide
import dev.kinash.smartscales.core.formula.BuiltinFormulas
import dev.kinash.smartscales.core.formula.FormulaIssue
import dev.kinash.smartscales.core.formula.FormulaModel
import dev.kinash.smartscales.core.formula.Functions
import dev.kinash.smartscales.core.formula.IndicatorDef
import dev.kinash.smartscales.core.formula.IndicatorUnit
import dev.kinash.smartscales.core.formula.ZoneLevel
import dev.kinash.smartscales.core.formula.text
import dev.kinash.smartscales.ui.common.CheckboxRow
import dev.kinash.smartscales.ui.common.ChoiceField
import dev.kinash.smartscales.ui.common.ChoiceRow
import dev.kinash.smartscales.ui.common.FieldSpacing
import dev.kinash.smartscales.ui.common.ScreenPadding
import dev.kinash.smartscales.ui.formulas.issueText
import dev.kinash.smartscales.ui.history.indicatorValueText
import dev.kinash.smartscales.ui.history.title
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
internal fun IndicatorPage(
    index: Int,
    set: SetDraft,
    check: EditorCheck?,
    issues: EditorIssues,
    trial: TrialWeighing?,
    language: String,
    viewModel: FormulaSetEditViewModel,
) {
    val draft = set.indicators.getOrNull(index) ?: return
    val path = "indicators[$index]"
    val change = { transform: (IndicatorDraft) -> IndicatorDraft -> viewModel.updateIndicator(index, transform) }
    var names by rememberSaveable { mutableStateOf(false) }

    TrialLine(draft.id.trim(), check, trial, set, language)
    TextFields(R.string.editor_name, draft.name, { name -> change { it.copy(name = name) } }, language, issues, "$path.name", tag = "indicator_name")
    IdField(draft.id, { id -> change { it.copy(id = id) } }, stringResource(R.string.editor_indicator_id_hint), issues.at("$path.id"))
    Row(horizontalArrangement = Arrangement.spacedBy(FieldSpacing), verticalAlignment = Alignment.Top) {
        ChoiceField(
            label = stringResource(R.string.editor_unit),
            selected = draft.unit,
            options = IndicatorUnit.entries,
            onSelect = { unit -> change { it.copy(unit = unit) } },
            title = { stringResource(it.title()) },
            modifier = Modifier.weight(1f),
        )
        Column(Modifier.weight(1f)) {
            NumberText(draft.precision, { text -> change { it.copy(precision = text) } }, stringResource(R.string.editor_precision), issues.at("$path.precision"), decimal = false)
        }
    }
    FormulaField(
        R.string.editor_formula, draft.formula, { text -> change { it.copy(formula = text) } }, issues.at("$path.formula"),
        hint = stringResource(R.string.editor_formula_hint), tag = "indicator_formula",
    )
    TextButton(onClick = { names = true }) { Text(stringResource(R.string.editor_names)) }
    FormulaField(
        R.string.editor_condition, draft.applies, { text -> change { it.copy(applies = text) } }, issues.at("$path.when"),
        hint = stringResource(R.string.editor_condition_hint),
    )
    PlainField(R.string.editor_group, draft.group, { text -> change { it.copy(group = text) } }, issues.at("$path.group"), stringResource(R.string.editor_group_hint))

    Section(R.string.editor_params, R.string.editor_params_hint)
    draft.params.forEachIndexed { k, param ->
        Row(horizontalArrangement = Arrangement.spacedBy(FieldSpacing), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                param.name, { name -> change { it.copy(params = it.params.mapIndexed { j, p -> if (j == k) p.copy(name = name) else p }) } },
                Modifier.weight(1f), singleLine = true, label = { Text(stringResource(R.string.editor_param_name)) },
                isError = issues.hasErrorAt("$path.params.${param.name.trim()}"),
            )
            OutlinedTextField(
                param.value, { value -> change { it.copy(params = it.params.mapIndexed { j, p -> if (j == k) p.copy(value = value) else p }) } },
                Modifier.weight(1f), singleLine = true, label = { Text(stringResource(R.string.editor_param_value)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
            IconButton(onClick = { change { it.copy(params = it.params.filterIndexed { j, _ -> j != k }) } }) {
                Icon(painterResource(R.drawable.ic_close), stringResource(R.string.editor_remove_param, param.name))
            }
        }
        FieldIssues(issues.at("$path.params.${param.name.trim()}"))
    }
    AddButton(R.string.editor_add_param) { change { it.copy(params = it.params + ParamDraft()) } }

    Section(R.string.editor_zones, R.string.editor_zones_hint)
    RulesEditor(draft.zones, { rules -> change { it.copy(zones = rules) } }, path, issues, language)
    if (draft.zones.isNotEmpty()) {
        CheckboxRow(stringResource(R.string.editor_approximate), stringResource(R.string.editor_approximate_hint), draft.approximateZones) { on ->
            change { it.copy(approximateZones = on) }
        }
    }

    Section(R.string.editor_about, null)
    PlainField(R.string.editor_source, draft.sourceText, { text -> change { it.copy(sourceText = text) } }, emptyList(), stringResource(R.string.editor_source_hint))
    PlainField(R.string.editor_source_url, draft.sourceUrl, { text -> change { it.copy(sourceUrl = text) } }, emptyList(), null)
    TextFields(
        R.string.editor_description, draft.description, { text -> change { it.copy(description = text) } },
        language, issues, "$path.description", singleLine = false,
    )

    if (names) {
        NamesDialog(check?.result?.below, set, onPick = { name ->
            names = false
            change { it.copy(formula = it.formula + name) }
        }, onDismiss = { names = false }, language = language)
    }
}

@Composable
internal fun PatchPage(
    index: Int,
    set: SetDraft,
    check: EditorCheck?,
    issues: EditorIssues,
    trial: TrialWeighing?,
    language: String,
    viewModel: FormulaSetEditViewModel,
) {
    val draft = set.patches.getOrNull(index) ?: return
    val path = "patches[$index]"
    val change = { transform: (PatchDraft) -> PatchDraft -> viewModel.updatePatch(index, transform) }
    val target: IndicatorDef? = check?.result?.below?.indicators?.get(draft.id)?.def
    val from = check?.result?.below?.indicators?.get(draft.id)?.set

    FieldIssues(issues.at("$path.id"))
    if (target != null) {
        val where = if (from == BuiltinFormulas.file.id) stringResource(R.string.editor_patch_builtin, draft.id) else stringResource(R.string.editor_patch_of_set, draft.id, from.orEmpty())
        Hint(where)
        Text(target.formula, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
    }
    Hint(stringResource(R.string.editor_patch_hint))
    TrialLine(draft.id, check, trial, set, language)

    Section(R.string.editor_params, null)
    val names = target?.params?.keys.orEmpty() + draft.params.keys
    if (names.isEmpty()) Hint(stringResource(R.string.editor_patch_no_params))
    names.forEach { name ->
        OutlinedTextField(
            draft.params[name].orEmpty(),
            { value -> change { it.copy(params = it.params + (name to value)) } },
            Modifier.fillMaxWidth().testTag("param_$name"),
            singleLine = true,
            label = { Text(name) },
            placeholder = target?.params?.get(name)?.let { { Text(numberText(it)) } },
            supportingText = target?.params?.get(name)?.let { { Text(stringResource(R.string.editor_patch_now, numberText(it))) } },
            isError = issues.hasErrorAt("$path.params.$name"),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )
        FieldIssues(issues.at("$path.params.$name"))
    }

    Section(R.string.editor_zones, null)
    CheckboxRow(stringResource(R.string.editor_patch_replace_zones), stringResource(R.string.editor_patch_replace_zones_hint), draft.zones != null) { on ->
        change {
            if (on) {
                it.copy(zones = target?.zones.orEmpty().map { rule -> RuleDraft.of(rule, language) }, approximateZones = target?.approximateZones ?: false)
            } else {
                it.copy(zones = null)
            }
        }
    }
    draft.zones?.let { rules ->
        RulesEditor(rules, { changed -> change { it.copy(zones = changed) } }, path, issues, language)
        CheckboxRow(stringResource(R.string.editor_approximate), stringResource(R.string.editor_approximate_hint), draft.approximateZones) { on ->
            change { it.copy(approximateZones = on) }
        }
    }

    Section(R.string.editor_patch_shown, null)
    TextFields(
        R.string.editor_patch_new_name, draft.name, { name -> change { it.copy(name = name) } }, language, issues, "$path.name",
        hint = target?.name?.text(language)?.let { stringResource(R.string.editor_patch_now, it) },
    )
    NumberText(
        draft.precision, { text -> change { it.copy(precision = text) } }, stringResource(R.string.editor_precision), issues.at("$path.precision"),
        decimal = false, hint = target?.precision?.let { stringResource(R.string.editor_patch_now, it.toString()) },
    )
}

@Composable
internal fun InputPage(index: Int, set: SetDraft, issues: EditorIssues, language: String, viewModel: FormulaSetEditViewModel) {
    val draft = set.inputs.getOrNull(index) ?: return
    val path = "inputs[$index]"
    val change = { transform: (InputDraft) -> InputDraft -> viewModel.updateInput(index, transform) }
    Hint(stringResource(R.string.editor_input_hint))
    TextFields(R.string.editor_name, draft.name, { name -> change { it.copy(name = name) } }, language, issues, "$path.name")
    IdField(draft.id, { id -> change { it.copy(id = id) } }, stringResource(R.string.editor_input_id_hint), issues.at("$path.id"))
    Text(stringResource(R.string.editor_unit), style = MaterialTheme.typography.labelLarge)
    ChoiceRow(
        options = listOf(IndicatorUnit.CM, IndicatorUnit.KG, IndicatorUnit.NONE),
        selected = draft.unit,
        onSelect = { unit -> change { it.copy(unit = unit) } },
        label = { stringResource(it.title()) },
    )
    FieldIssues(issues.at("$path.unit"))
    TextFields(
        R.string.editor_input_description, draft.description, { text -> change { it.copy(description = text) } },
        language, issues, "$path.description", singleLine = false,
    )
}

@Composable
internal fun GroupPage(index: Int, set: SetDraft, check: EditorCheck?, issues: EditorIssues, language: String, viewModel: FormulaSetEditViewModel) {
    val draft = set.groups.getOrNull(index) ?: return
    val path = "groups[$index]"
    val change = { transform: (GroupDraft) -> GroupDraft -> viewModel.updateGroup(index, transform) }
    Hint(stringResource(R.string.editor_group_page_hint))
    val known = (check?.result?.below?.groups?.keys.orEmpty() + set.indicators.mapNotNull { it.group.trim().ifEmpty { null } }).sorted()
    IdField(
        draft.id, { id -> change { it.copy(id = id) } },
        if (known.isEmpty()) null else stringResource(R.string.editor_groups_known, known.joinToString(", ")),
        issues.at("$path.id"),
    )
    TextFields(R.string.editor_name, draft.name, { name -> change { it.copy(name = name) } }, language, issues, "$path.name")
}

/** The value the formula gives on the last weighing, or why there is none. */
@Composable
private fun TrialLine(id: String, check: EditorCheck?, trial: TrialWeighing?, set: SetDraft, language: String) {
    val text = when {
        trial == null -> stringResource(R.string.editor_trial_none)
        check == null || check.draft != set -> stringResource(R.string.editor_checking)
        check.result.model == null -> stringResource(R.string.editor_trial_errors)
        else -> {
            val model = check.result.model
            // Worked out once per check, not on every recomposition of the page
            val results = remember(model, trial) { model.evaluate(trial.inputs, trial.primary) }
            val value = remember(results, id) { results.value(id) }
            val date = trial.takenAt.atZone(trial.zone).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
            val def = model.indicators[id]?.def
            if (value == null || def == null || !value.isFinite()) {
                stringResource(R.string.editor_trial_no_value, date, trial.profile.name)
            } else {
                val shown = indicatorValueText(value, def.unit, def.precision, trial.profile)
                val zone = results.zone(id)?.let { zone -> zone.label?.text(language) ?: stringResource(zone.level.title()) }
                stringResource(R.string.editor_trial_value, date, trial.profile.name, listOfNotNull(shown, zone).joinToString(" · "))
            }
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Text(text, Modifier.padding(ScreenPadding).testTag("trial"), style = MaterialTheme.typography.bodyMedium)
    }
}

/** Rules of norms: for whom (a condition), the zones from the lowest and the bounds between them. */
@Composable
private fun RulesEditor(rules: List<RuleDraft>, onChange: (List<RuleDraft>) -> Unit, path: String, issues: EditorIssues, language: String) {
    fun changeRule(j: Int, transform: (RuleDraft) -> RuleDraft) = onChange(rules.mapIndexed { i, rule -> if (i == j) transform(rule) else rule })
    rules.forEachIndexed { j, rule ->
        val rulePath = "$path.zones[$j]"
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(ScreenPadding), verticalArrangement = Arrangement.spacedBy(FieldSpacing)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.editor_rule, j + 1), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    IconButton(onClick = { onChange(rules.filterIndexed { i, _ -> i != j }) }) {
                        Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.editor_remove_rule, j + 1))
                    }
                }
                FormulaField(
                    R.string.editor_rule_condition, rule.applies, { text -> changeRule(j) { it.copy(applies = text) } }, issues.at("$rulePath.when"),
                    hint = stringResource(R.string.editor_rule_condition_hint),
                )
                rule.zones.forEachIndexed { k, zone ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ChoiceField(
                            label = stringResource(R.string.editor_zone, k + 1),
                            selected = zone.level,
                            options = ZoneLevel.entries,
                            onSelect = { level -> changeRule(j) { it.copy(zones = it.zones.mapIndexed { i, z -> if (i == k) z.copy(level = level) else z }) } },
                            title = { stringResource(it.title()) },
                            modifier = Modifier.weight(1f),
                        )
                        if (rule.zones.size > 1) {
                            IconButton(onClick = { changeRule(j) { it.withoutZone(k) } }) {
                                Icon(painterResource(R.drawable.ic_close), stringResource(R.string.editor_remove_zone, k + 1))
                            }
                        }
                    }
                    if (rule.labeled) {
                        TextFields(
                            R.string.editor_zone_label, zone.label,
                            { label -> changeRule(j) { it.copy(zones = it.zones.mapIndexed { i, z -> if (i == k) z.copy(label = label) else z }) } },
                            language, issues, "$rulePath.labels[$k]",
                        )
                    }
                    if (k < rule.bounds.size) {
                        NumberText(
                            rule.bounds[k],
                            { text -> changeRule(j) { it.copy(bounds = it.bounds.mapIndexed { i, b -> if (i == k) text else b }) } },
                            stringResource(R.string.editor_bound, k + 1),
                            issues.at("$rulePath.bounds[$k]"),
                        )
                    }
                }
                AddButton(R.string.editor_add_zone) { changeRule(j) { it.withZoneAdded() } }
                ChoiceRow(
                    options = BoundSide.entries,
                    selected = rule.atBound,
                    onSelect = { side -> changeRule(j) { it.copy(atBound = side) } },
                    label = { stringResource(if (it == BoundSide.ABOVE) R.string.editor_bound_above else R.string.editor_bound_below) },
                )
                CheckboxRow(stringResource(R.string.editor_zone_labels), null, rule.labeled) { on -> changeRule(j) { it.copy(labeled = on) } }
                FieldIssues(issues.at("$rulePath.bounds") + issues.at("$rulePath.levels") + issues.at("$rulePath.labels"))
            }
        }
    }
    AddButton(R.string.editor_add_rule) { onChange(rules + RuleDraft()) }
}

/** The names a formula can use, with what they are; tapping one adds it to the formula. */
@Composable
private fun NamesDialog(below: FormulaModel?, set: SetDraft, onPick: (String) -> Unit, onDismiss: () -> Unit, language: String) {
    val model = below
    val own = set.indicators.map { it.id.trim() to it.name.toText(language).text(language) }
    val indicators = model?.indicators?.values?.map { it.id to it.def.name.text(language) }.orEmpty()
    val groups = model?.groups?.map { (id, variants) -> id to model.groupName(id, variants.first()).text(language) }.orEmpty()
    val inputs = model?.inputs?.values?.map { it.id to it.name.text(language) }.orEmpty() +
        set.inputs.map { it.id.trim() to it.name.toText(language).text(language) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.editor_names)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Hint(stringResource(R.string.editor_names_inputs))
                Hint(stringResource(R.string.editor_names_functions, Functions.names.sorted().joinToString(", ")))
                NameList(R.string.editor_names_own_inputs, inputs.distinct(), onPick)
                NameList(R.string.editor_names_groups, groups.sortedBy { it.second.lowercase() }, onPick)
                NameList(R.string.editor_names_indicators, (own + indicators).distinctBy { it.first }.sortedBy { it.second.lowercase() }, onPick)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

@Composable
private fun NameList(@StringRes title: Int, names: List<Pair<String, String>>, onPick: (String) -> Unit) {
    if (names.isEmpty()) return
    Text(stringResource(title), Modifier.padding(top = FieldSpacing), style = MaterialTheme.typography.labelLarge)
    names.forEach { (id, name) ->
        Column(Modifier.fillMaxWidth().clickable { onPick(id) }.padding(vertical = 4.dp)) {
            Text(id, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
            Hint(name)
        }
    }
}

/** A text in English and, on a phone in another language, in that language; [hint] goes under the English one. */
@Composable
internal fun TextFields(
    @StringRes label: Int,
    value: TextDraft,
    onChange: (TextDraft) -> Unit,
    language: String,
    issues: EditorIssues,
    path: String,
    singleLine: Boolean = true,
    hint: String? = null,
    tag: String? = null,
) {
    val local = language != "en"
    OutlinedTextField(
        value.en, { onChange(value.copy(en = it)) },
        Modifier.fillMaxWidth().let { if (tag != null) it.testTag(tag) else it },
        singleLine = singleLine,
        label = { Text(if (local) stringResource(R.string.editor_in_english, stringResource(label)) else stringResource(label)) },
        supportingText = hint?.let { { Text(it) } },
        isError = issues.hasErrorAt(path),
    )
    if (local) {
        OutlinedTextField(
            value.local, { onChange(value.copy(local = it)) }, Modifier.fillMaxWidth(),
            singleLine = singleLine,
            label = { Text(stringResource(R.string.editor_in_language, stringResource(label))) },
        )
    }
    FieldIssues(issues.at(path))
}

@Composable
internal fun IdField(value: String, onChange: (String) -> Unit, hint: String?, issues: List<Pair<FormulaIssue, Boolean>>) {
    OutlinedTextField(
        value, onChange, Modifier.fillMaxWidth().testTag("id_field"), singleLine = true,
        label = { Text(stringResource(R.string.editor_id)) },
        supportingText = hint?.let { { Text(it) } },
        isError = issues.any { it.second },
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
    )
    FieldIssues(issues)
}

@Composable
private fun FormulaField(
    @StringRes label: Int,
    value: String,
    onChange: (String) -> Unit,
    issues: List<Pair<FormulaIssue, Boolean>>,
    hint: String?,
    tag: String? = null,
) {
    OutlinedTextField(
        value, onChange, Modifier.fillMaxWidth().let { if (tag != null) it.testTag(tag) else it },
        label = { Text(stringResource(label)) },
        supportingText = hint?.let { { Text(it) } },
        isError = issues.any { it.second },
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
    )
    FieldIssues(issues)
}

@Composable
private fun PlainField(@StringRes label: Int, value: String, onChange: (String) -> Unit, issues: List<Pair<FormulaIssue, Boolean>>, hint: String?) {
    OutlinedTextField(
        value, onChange, Modifier.fillMaxWidth(), singleLine = true,
        label = { Text(stringResource(label)) },
        supportingText = hint?.let { { Text(it) } },
        isError = issues.any { it.second },
    )
    FieldIssues(issues)
}

@Composable
private fun NumberText(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    issues: List<Pair<FormulaIssue, Boolean>>,
    decimal: Boolean = true,
    hint: String? = null,
) {
    OutlinedTextField(
        value, onChange, Modifier.fillMaxWidth(), singleLine = true,
        label = { Text(label) },
        supportingText = hint?.let { { Text(it) } },
        isError = issues.any { it.second },
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
    )
    FieldIssues(issues)
}

/** The issues of one field under it: errors in red, warnings in grey. */
@Composable
internal fun FieldIssues(issues: List<Pair<FormulaIssue, Boolean>>) {
    val names = remember { mapOf(BuiltinFormulas.file.id to BuiltinFormulas.file.name.text("en")) }
    issues.forEach { (issue, error) ->
        val color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
        Text(issueText(issue.copy(path = ""), issue.set, names), style = MaterialTheme.typography.bodySmall, color = color)
    }
}

@Composable
internal fun Section(@StringRes title: Int, @StringRes hint: Int?) {
    Text(
        stringResource(title),
        Modifier.padding(top = FieldSpacing),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
    hint?.let { Hint(stringResource(it)) }
}

@Composable
internal fun AddButton(@StringRes label: Int, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Icon(painterResource(R.drawable.ic_add), contentDescription = null)
        Text(stringResource(label), Modifier.padding(start = 8.dp))
    }
}

@Composable
internal fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@StringRes
internal fun IndicatorUnit.title(): Int = when (this) {
    IndicatorUnit.KG -> R.string.unit_kg
    IndicatorUnit.CM -> R.string.unit_cm
    IndicatorUnit.PERCENT -> R.string.unit_percent
    IndicatorUnit.LITER -> R.string.unit_liter
    IndicatorUnit.KCAL -> R.string.unit_kcal
    IndicatorUnit.KG_M2 -> R.string.unit_kg_m2
    IndicatorUnit.OHM -> R.string.unit_ohm
    IndicatorUnit.NONE -> R.string.editor_unit_none
}
