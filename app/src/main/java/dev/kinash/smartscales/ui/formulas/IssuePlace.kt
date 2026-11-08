package dev.kinash.smartscales.ui.formulas

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.kinash.smartscales.R
import dev.kinash.smartscales.core.formula.FormulaSetFile
import dev.kinash.smartscales.core.formula.text

private val PART = Regex("""(indicators|patches|inputs|groups)\[(\d+)](.*)""")
private val ZONES = Regex("""\.zones\[(\d+)](?:\.(bounds|labels)\[(\d+)]|\.(\w+))?.*""")

/**
 * Where in a set an issue is, in words rather than as its path: “Half weight”, formula for `indicators[2].formula`,
 * with the names [file] gives its parts; a change of an indicator is named after the indicator in [others], the
 * sets below. The path itself where [file] is not at hand (a file that does not read) or the place is not one of
 * the known ones; null for the set as a whole.
 */
@Composable
internal fun issuePlace(path: String, file: FormulaSetFile?, language: String, others: Collection<FormulaSetFile> = emptyList()): String? {
    if (path.isEmpty()) return null
    val part = PART.matchEntire(path) ?: return setField(path)?.let { stringResource(it) } ?: path
    val (kind, index, rest) = part.destructured
    val i = index.toInt()
    val name = when (kind) {
        "indicators" -> file?.indicators?.getOrNull(i)?.let { it.name.text(language).ifBlank { it.id } }
        "patches" -> file?.patches?.getOrNull(i)?.id?.let { id ->
            others.firstNotNullOfOrNull { set -> set.indicators.firstOrNull { it.id == id } }?.name?.text(language) ?: id
        }
        "inputs" -> file?.inputs?.getOrNull(i)?.let { it.name.text(language).ifBlank { it.id } }
        else -> file?.groups?.getOrNull(i)?.id
    } ?: return path
    val field = when {
        rest.isEmpty() -> null
        rest.startsWith(".params.") -> stringResource(R.string.issue_param, rest.removePrefix(".params."))
        rest.startsWith(".zones") -> zonesPlace(rest)
        else -> partField(rest.removePrefix("."))?.let { stringResource(it).lowercase() } ?: rest.removePrefix(".")
    }
    return if (field == null) stringResource(R.string.issue_place_part, name) else stringResource(R.string.issue_place, name, field)
}

/** Which rule of the norms, and which bound or zone in it: "norms, rule 2, bound 1". */
@Composable
private fun zonesPlace(rest: String): String {
    val parts = mutableListOf(stringResource(R.string.editor_zones).lowercase())
    ZONES.matchEntire(rest)?.let { match ->
        val (rule, list, item, field) = match.destructured
        parts += stringResource(R.string.editor_rule, rule.toInt() + 1).lowercase()
        when {
            list == "bounds" -> parts += stringResource(R.string.editor_bound, item.toInt() + 1).lowercase()
            list == "labels" -> parts += stringResource(R.string.editor_zone, item.toInt() + 1).lowercase()
            field == "when" -> parts += stringResource(R.string.editor_condition).lowercase()
        }
    }
    return parts.joinToString(", ")
}

@StringRes
private fun setField(path: String): Int? = when (path) {
    "id" -> R.string.issue_set_id
    "name" -> R.string.issue_set_name
    "description" -> R.string.issue_set_description
    "hidden" -> R.string.editor_hidden
    else -> null
}

@StringRes
private fun partField(field: String): Int? = when (field) {
    "id" -> R.string.editor_id
    "name" -> R.string.editor_name
    "formula" -> R.string.editor_formula
    "when" -> R.string.editor_condition
    "precision" -> R.string.editor_precision
    "group" -> R.string.editor_group
    "unit" -> R.string.editor_unit
    "description" -> R.string.editor_description
    else -> null
}
