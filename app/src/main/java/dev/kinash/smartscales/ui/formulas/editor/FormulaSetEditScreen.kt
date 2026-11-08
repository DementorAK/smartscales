package dev.kinash.smartscales.ui.formulas.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kinash.smartscales.R
import dev.kinash.smartscales.core.formula.BuiltinFormulas
import dev.kinash.smartscales.core.formula.FormulaIssue
import dev.kinash.smartscales.core.formula.FormulaModel
import dev.kinash.smartscales.core.formula.ProblemKind
import dev.kinash.smartscales.core.formula.text
import dev.kinash.smartscales.indicators.BuiltinModel
import dev.kinash.smartscales.ui.common.BackButton
import dev.kinash.smartscales.ui.common.ConfirmDialog
import dev.kinash.smartscales.ui.common.FieldSpacing
import dev.kinash.smartscales.ui.common.ScreenPadding
import dev.kinash.smartscales.ui.formulas.issueText

/**
 * The editor of a formula set (requirements §5.1): the set with its indicators, changes of indicators below, hidden
 * ones, fields to enter and group names; each part on a page of its own. Saving checks the set as importing does.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormulaSetEditScreen(onDone: () -> Unit, viewModel: FormulaSetEditViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val check by viewModel.check.collectAsStateWithLifecycle()
    val trial by viewModel.trial.collectAsStateWithLifecycle()
    val saved by viewModel.saved.collectAsStateWithLifecycle()
    val tried by viewModel.tried.collectAsStateWithLifecycle()
    val rejected by viewModel.rejected.collectAsStateWithLifecycle()
    var discarding by rememberSaveable { mutableStateOf(false) }
    var removing by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(saved) { if (saved) onDone() }

    val current = state
    val goBack = {
        if (!viewModel.back()) {
            if (current?.changed == true) discarding = true else onDone()
        }
    }
    BackHandler { goBack() }
    // The check of what is on the screen, not of the text before the last key
    val fresh = check?.takeIf { it.draft == current?.draft }
    val language = viewModel.language
    val issues = EditorIssues(check, all = tried)
    var showingIssues by rememberSaveable { mutableStateOf(false) }
    // A refused save lists what to fix
    LaunchedEffect(rejected) { if (rejected > 0) showingIssues = true }

    Scaffold(
        topBar = {
            Column {
            TopAppBar(
                title = { current?.let { Text(pageTitle(it, language, fresh ?: check)) } },
                navigationIcon = { BackButton(goBack) },
                actions = {
                    if (current?.page == EditorPage.Set) {
                        // Not off for errors: tapping it shows them, those of blank fields too
                        TextButton(
                            onClick = viewModel::save,
                            enabled = fresh != null && (current.changed || current.isNew),
                        ) { Text(stringResource(R.string.action_save)) }
                    } else if (current != null) {
                        IconButton(onClick = { removing = true }) {
                            Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.editor_remove))
                        }
                        TextButton(onClick = { viewModel.back() }) { Text(stringResource(R.string.editor_done)) }
                    }
                },
            )
            current?.let { StatusStrip(it, check, issues, trial, language) { showingIssues = true } }
            }
        },
    ) { padding ->
        val shown = current ?: return@Scaffold
        key(shown.page) {
            Column(
                Modifier
                    .padding(padding)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(ScreenPadding),
                verticalArrangement = Arrangement.spacedBy(FieldSpacing),
            ) {
                when (val page = shown.page) {
                    EditorPage.Set -> SetPage(shown, fresh, issues, language, viewModel)
                    is EditorPage.Indicator -> IndicatorPage(page.index, shown.draft, check, issues, trial, language, viewModel)
                    is EditorPage.Patch -> PatchPage(page.index, shown.draft, check, issues, trial, language, viewModel)
                    is EditorPage.Input -> InputPage(page.index, shown.draft, issues, language, viewModel)
                    is EditorPage.Group -> GroupPage(page.index, shown.draft, check, issues, language, viewModel)
                }
            }
        }
    }

    check?.let { found -> if (showingIssues) IssuesDialog(found, issues, language) { showingIssues = false } }
    if (discarding) {
        ConfirmDialog(
            title = stringResource(R.string.editor_discard_title),
            text = stringResource(R.string.editor_discard_text),
            confirmLabel = stringResource(R.string.editor_discard),
            onConfirm = {
                discarding = false
                onDone()
            },
            onDismiss = { discarding = false },
            dismissLabel = stringResource(R.string.editor_keep_editing),
        )
    }
    if (removing) {
        ConfirmDialog(
            title = stringResource(R.string.editor_remove_title),
            text = stringResource(R.string.editor_remove_text),
            confirmLabel = stringResource(R.string.editor_remove),
            onConfirm = {
                removing = false
                current?.let { viewModel.remove(it.page) }
            },
            onDismiss = { removing = false },
        )
    }
}

@Composable
private fun pageTitle(state: EditorState, language: String, check: EditorCheck?): String = when (val page = state.page) {
    EditorPage.Set -> {
        val name = state.draft.name.toText(language).text(language)
        when {
            name.isNotBlank() -> name
            state.isNew -> stringResource(R.string.editor_new_set)
            else -> state.draft.id
        }
    }
    is EditorPage.Indicator -> state.draft.indicators.getOrNull(page.index)?.let { draft ->
        draft.name.toText(language).text(language).ifBlank { draft.id }
    }.orEmpty()
    is EditorPage.Patch -> state.draft.patches.getOrNull(page.index)?.let { patch ->
        stringResource(R.string.editor_patch_title, check?.result?.below?.indicators?.get(patch.id)?.def?.name?.text(language) ?: patch.id)
    }.orEmpty()
    is EditorPage.Input -> stringResource(R.string.editor_input_title)
    is EditorPage.Group -> stringResource(R.string.editor_group_title)
}

/**
 * The issues of the last check by path: those of the fields that do not read and those of the model together.
 * Blank fields count only once saving was tried ([all]); before, a person filling the form in is not told off.
 */
class EditorIssues(check: EditorCheck?, all: Boolean) {
    /** Errors to show, in the order of the check. */
    val shown: List<FormulaIssue>

    private val errors: Map<String, List<FormulaIssue>>
    private val warnings = check?.warnings.orEmpty().groupBy { it.path }

    init {
        val found = check?.errors.orEmpty()
        val required = found.filter { it.problem.kind == ProblemKind.REQUIRED }.map { it.path }.toSet()
        // A blank number is "fill this in", not also "not a number"
        val distinct = found.filter { it.problem.kind == ProblemKind.REQUIRED || it.path !in required }
        shown = if (all) distinct else distinct.filterNot { it.isBlank() }
        errors = shown.groupBy { it.path }
    }

    /** Errors first, then warnings, at exactly [path]. */
    fun at(path: String): List<Pair<FormulaIssue, Boolean>> =
        errors[path].orEmpty().map { it to true } + warnings[path].orEmpty().map { it to false }

    fun hasErrorAt(path: String): Boolean = errors[path] != null

    /** Whether there is an error at [prefix] or inside it (`indicators[2]`, `indicators[2].zones[0]`). */
    fun hasErrorsUnder(prefix: String): Boolean = errors.keys.any { it == prefix || it.startsWith("$prefix.") || it.startsWith("$prefix[") }

    private companion object {
        /** An error of a field left blank, rather than of something written wrong. */
        fun FormulaIssue.isBlank(): Boolean = when (problem.kind) {
            ProblemKind.REQUIRED, ProblemKind.ENGLISH_REQUIRED, ProblemKind.EMPTY -> true
            ProblemKind.ID_INVALID, ProblemKind.PARAM_NAME -> problem.args.firstOrNull().isNullOrBlank()
            else -> false
        }
    }
}

@Composable
private fun SetPage(state: EditorState, check: EditorCheck?, issues: EditorIssues, language: String, viewModel: FormulaSetEditViewModel) {
    val draft = state.draft
    var picking by rememberSaveable { mutableStateOf<String?>(null) }
    Hint(stringResource(R.string.editor_set_hint))
    if (state.isNew) {
        IdField(draft.id, { id -> viewModel.update { it.copy(id = id) } }, stringResource(R.string.editor_set_id_hint), issues.at("id"))
    } else {
        Hint(stringResource(R.string.editor_set_id, draft.id))
        FieldIssues(issues.at("id"))
    }
    TextFields(R.string.editor_name, draft.name, { name -> viewModel.update { it.copy(name = name) } }, language, issues, "name", tag = "set_name")
    TextFields(
        R.string.editor_description, draft.description, { text -> viewModel.update { it.copy(description = text) } },
        language, issues, "description", singleLine = false,
    )

    Section(R.string.editor_indicators, R.string.editor_indicators_hint)
    draft.indicators.forEachIndexed { i, indicator ->
        ItemRow(
            title = indicator.name.toText(language).text(language).ifBlank { indicator.id },
            subtitle = indicator.id + if (indicator.formula.isNotBlank()) " = " + indicator.formula else "",
            error = issues.hasErrorsUnder("indicators[$i]"),
            onClick = { viewModel.open(EditorPage.Indicator(i)) },
        )
    }
    AddButton(R.string.editor_add_indicator, viewModel::addIndicator)

    Section(R.string.editor_patches, R.string.editor_patches_hint)
    draft.patches.forEachIndexed { i, patch ->
        ItemRow(
            title = check?.result?.below?.indicators?.get(patch.id)?.def?.name?.text(language) ?: patch.id,
            subtitle = patchSummary(patch),
            error = issues.hasErrorsUnder("patches[$i]"),
            onClick = { viewModel.open(EditorPage.Patch(i)) },
        )
    }
    AddButton(R.string.editor_add_patch) { picking = PICK_PATCH }

    Section(R.string.editor_hidden, R.string.editor_hidden_hint)
    val below = check?.result?.below
    draft.hidden.forEach { id ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(below?.let { nameOf(it, id, language) } ?: id, style = MaterialTheme.typography.bodyLarge)
                Hint(id)
            }
            IconButton(onClick = { viewModel.unhide(id) }) {
                Icon(painterResource(R.drawable.ic_close), stringResource(R.string.editor_unhide, id))
            }
        }
    }
    FieldIssues(issues.at("hidden"))
    AddButton(R.string.editor_add_hidden) { picking = PICK_HIDDEN }

    Section(R.string.editor_inputs, R.string.editor_inputs_hint)
    draft.inputs.forEachIndexed { i, input ->
        ItemRow(
            title = input.name.toText(language).text(language).ifBlank { input.id },
            subtitle = input.id + " · " + stringResource(input.unit.title()),
            error = issues.hasErrorsUnder("inputs[$i]"),
            onClick = { viewModel.open(EditorPage.Input(i)) },
        )
    }
    AddButton(R.string.editor_add_input, viewModel::addInput)

    Section(R.string.editor_groups, R.string.editor_groups_hint)
    draft.groups.forEachIndexed { i, group ->
        ItemRow(
            title = group.name.toText(language).text(language).ifBlank { group.id },
            subtitle = group.id,
            error = issues.hasErrorsUnder("groups[$i]"),
            onClick = { viewModel.open(EditorPage.Group(i)) },
        )
    }
    AddButton(R.string.editor_add_group, viewModel::addGroup)

    when (picking) {
        PICK_PATCH -> {
            // Changes of the built-in indicators: those of the person's other sets are theirs to change. Not those
            // this set hides or replaces with one of its own, which a change would not reach
            val own = draft.indicators.map { it.id.trim() }.toSet()
            val options = BuiltinModel.indicators.values
                .filter { it.id !in own && it.id !in draft.hidden && it.def.group !in draft.hidden }
                .map { it.id to it.def.name.text(language) }
                .sortedBy { it.second.lowercase() }
            PickDialog(stringResource(R.string.editor_add_patch), options, onPick = { id ->
                picking = null
                viewModel.changeIndicator(id)
            }, onDismiss = { picking = null })
        }
        PICK_HIDDEN -> {
            val model = below ?: BuiltinModel
            val ids = (model.indicators.keys + model.groups.keys - model.hidden - draft.hidden.toSet())
            val options = ids.map { it to nameOf(model, it, language) }.sortedBy { it.second.lowercase() }
            PickDialog(stringResource(R.string.editor_add_hidden), options, onPick = { id ->
                picking = null
                viewModel.hide(id)
            }, onDismiss = { picking = null })
        }
    }
}

private const val PICK_PATCH = "patch"
private const val PICK_HIDDEN = "hidden"

/** An indicator's or a group's name in the model. */
private fun nameOf(model: FormulaModel, id: String, language: String): String =
    model.indicators[id]?.def?.name?.text(language)
        ?: model.groups[id]?.firstOrNull()?.let { model.groupName(id, it).text(language) }
        ?: id

@Composable
private fun patchSummary(patch: PatchDraft): String = listOfNotNull(
    patch.params.count { it.value.isNotBlank() }.takeIf { it > 0 }?.let { stringResource(R.string.editor_patch_params, it) },
    stringResource(R.string.editor_patch_zones).takeIf { patch.zones != null },
    stringResource(R.string.editor_patch_name).takeIf { !patch.name.isBlank },
    stringResource(R.string.editor_patch_precision).takeIf { patch.precision.isNotBlank() },
).joinToString(" · ").ifEmpty { stringResource(R.string.editor_patch_nothing) }

/**
 * A line of fixed height under the title, out of the scrolling page, so that what it says changes nothing below:
 * on an indicator's page or a change's, the value on the last weighing; elsewhere, what the check of the set found,
 * which a tap lists in full.
 */
@Composable
private fun StatusStrip(state: EditorState, check: EditorCheck?, issues: EditorIssues, trial: TrialWeighing?, language: String, onShowIssues: () -> Unit) {
    val trialOf = when (val page = state.page) {
        is EditorPage.Indicator -> state.draft.indicators.getOrNull(page.index)?.id?.trim()
        is EditorPage.Patch -> state.draft.patches.getOrNull(page.index)?.id
        else -> null
    }
    val hasList = check != null && (issues.shown.isNotEmpty() || check.warnings.isNotEmpty() || check.result.breaks.isNotEmpty())
    val text = when {
        trialOf != null -> trialText(trialOf, check, issues, trial, language)
        check == null -> stringResource(R.string.editor_checking)
        issues.shown.isNotEmpty() -> stringResource(R.string.editor_status_errors, issues.shown.size)
        check.errors.isNotEmpty() -> stringResource(R.string.editor_status_incomplete)
        check.warnings.isNotEmpty() -> stringResource(R.string.editor_status_warnings, check.warnings.size)
        else -> stringResource(R.string.editor_no_errors)
    }
    val error = trialOf == null && issues.shown.isNotEmpty()
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().let { if (trialOf == null && hasList) it.clickable(onClick = onShowIssues) else it },
    ) {
        Text(
            text,
            Modifier.padding(horizontal = ScreenPadding, vertical = 8.dp).testTag("status"),
            style = MaterialTheme.typography.bodyMedium,
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** What the check of the set found: the errors to fix, the warnings, the sets it would break. */
@Composable
private fun IssuesDialog(check: EditorCheck, issues: EditorIssues, language: String, onDismiss: () -> Unit) {
    val names = mapOf(BuiltinFormulas.file.id to BuiltinFormulas.file.name.text(language))
    val files = mapOf(BuiltinFormulas.file.id to BuiltinFormulas.file, check.file.id to check.file)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (issues.shown.isEmpty()) R.string.editor_check else R.string.editor_errors)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (issues.shown.isEmpty() && check.errors.isEmpty()) Text(stringResource(R.string.editor_no_errors), style = MaterialTheme.typography.bodyMedium)
                issues.shown.forEach {
                    Text("• " + issueText(it, check.file.id, names, files), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
                if (check.warnings.isNotEmpty()) {
                    Text(stringResource(R.string.formula_set_warnings), Modifier.padding(top = FieldSpacing), style = MaterialTheme.typography.labelLarge)
                    check.warnings.forEach { Hint("• " + issueText(it, check.file.id, names, files)) }
                }
                if (check.result.breaks.isNotEmpty()) {
                    Text(
                        stringResource(R.string.formula_import_breaks, check.result.breaks.joinToString { it.name }),
                        Modifier.padding(top = FieldSpacing),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) } },
    )
}

@Composable
private fun PickDialog(title: String, options: List<Pair<String, String>>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var filter by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    filter, { filter = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(stringResource(R.string.editor_search)) },
                )
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    val wanted = filter.trim().lowercase()
                    val mark = SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    options.filter { (id, name) -> wanted.isEmpty() || wanted in id.lowercase() || wanted in name.lowercase() }
                        .forEach { (id, name) ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(id) }
                                    .padding(vertical = 8.dp),
                            ) {
                                Text(marked(name, wanted, mark), style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    marked(id, wanted, mark),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

/** [text] with each place it has [wanted] (lower case) in [style], so that a search shows what it found. */
internal fun marked(text: String, wanted: String, style: SpanStyle): AnnotatedString = buildAnnotatedString {
    append(text)
    if (wanted.isEmpty()) return@buildAnnotatedString
    val lower = text.lowercase()
    var from = lower.indexOf(wanted)
    while (from >= 0) {
        // A lower case that changes the length (a rare letter) only shifts the mark, never past the end
        if (from + wanted.length <= text.length) addStyle(style, from, from + wanted.length)
        from = lower.indexOf(wanted, from + wanted.length)
    }
}

@Composable
internal fun ItemRow(title: String, subtitle: String, error: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp)
            .testTag("item_$title"),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        if (error) Text(stringResource(R.string.editor_has_errors), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}
