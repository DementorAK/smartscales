package dev.kinash.smartscales.indicators

import dev.kinash.smartscales.core.data.FormulaSet
import dev.kinash.smartscales.core.formula.BuiltinFormulas
import dev.kinash.smartscales.core.formula.FormulaIssue
import dev.kinash.smartscales.core.formula.FormulaLayer
import dev.kinash.smartscales.core.formula.FormulaModel
import dev.kinash.smartscales.core.formula.FormulaSetException
import dev.kinash.smartscales.core.formula.FormulaSetFile
import dev.kinash.smartscales.core.formula.Problem
import dev.kinash.smartscales.core.formula.ProblemKind

/**
 * A stored set as the model sees it. [file] is null when the text no longer reads (it did when imported, so
 * only a newer app's format or damage); [errors] then say why, with no [FormulaIssue.set]: the id is unknown.
 * A set that reads but brings errors among the others (an id another set took, a patch to an indicator a set
 * below removed) is left out with them, and [active] is false; so is a set switched off.
 */
data class LayerState(
    val stored: FormulaSet,
    val file: FormulaSetFile?,
    val errors: List<FormulaIssue>,
    val warnings: List<FormulaIssue>,
) {
    val active: Boolean get() = stored.enabled && file != null && errors.isEmpty()
}

/** The model from the built-in set and the person's enabled sets, and how each set fared, the bottom one first. */
data class Layers(val model: FormulaModel, val sets: List<LayerState>)

/** The model of the built-in set alone, which has no errors: its tests check that. */
val BuiltinModel: FormulaModel by lazy { FormulaModel.build(listOf(BuiltinFormulas.layer)) }

/** Builds the layers from [stored] sets, bottom first; a broken one is left out and the rest still work. */
fun layersOf(stored: List<FormulaSet>): Layers {
    val files = stored.map { set -> set to readSet(set.json) }
    val stack = stack(files.mapNotNull { (set, read) -> read.file?.takeIf { set.enabled }?.let { set to it } })
    val warnings = stack.model.warnings.groupBy { it.set }
    return Layers(
        stack.model,
        files.map { (set, read) ->
            LayerState(
                set, read.file,
                errors = read.error?.let(::listOf) ?: stack.rejected[set].orEmpty(),
                // Of a set in the model only: one left out has its errors to show instead
                warnings = read.file?.takeIf { set !in stack.rejected }?.let { warnings[it.id] }.orEmpty(),
            )
        },
    )
}

private class Stack<K>(val model: FormulaModel, val rejected: Map<K, List<FormulaIssue>>)

/**
 * Lays [layers] over the built-in set bottom first, like [FormulaModel.buildUsable], but tells the layers
 * apart by their key: two stored sets with one id are two layers, and only the higher one is left out.
 */
private fun <K> stack(layers: List<Pair<K, FormulaSetFile>>): Stack<K> {
    val kept = arrayListOf(BuiltinFormulas.layer)
    var model = BuiltinModel
    val rejected = LinkedHashMap<K, List<FormulaIssue>>()
    for ((key, file) in layers) {
        val attempt = FormulaModel.build(kept + FormulaLayer(file))
        if (attempt.isValid) {
            kept += FormulaLayer(file)
            model = attempt
        } else {
            rejected[key] = attempt.issues
        }
    }
    return Stack(model, rejected)
}

/** What importing a file would do. */
sealed interface ImportCheck {
    /**
     * The file is not taken: it does not read, or brings errors over the sets now enabled. [set] is the file's
     * id when it reads; an error of another [FormulaIssue.set] points into that set, where the clash was found.
     */
    data class Rejected(val errors: List<FormulaIssue>, val set: String? = null) : ImportCheck

    /**
     * The file can be added on top, or replace [replaces], the stored set with the same id. [breaks] are the
     * enabled sets above it that would be left out with it: a new version that drops an indicator they patch.
     * A set switched off stays off when replaced, so it breaks nothing; its file is checked as if it were on,
     * which is what switching it on would do.
     */
    data class Accepted(
        val file: FormulaSetFile,
        val replaces: FormulaSet?,
        val warnings: List<FormulaIssue>,
        val breaks: List<FormulaSet> = emptyList(),
    ) : ImportCheck
}

/** Files bigger than this are not formula sets; the built-in one is about 40 KB. */
const val MAX_SET_BYTES = 1_000_000

/**
 * Checks [text] as a set over [stored] ones: in the place of the set with the same id, which it would replace,
 * or on top. Errors are the file's own: the sets in use are already free of them.
 */
fun checkImport(text: String, stored: List<FormulaSet>): ImportCheck {
    val read = readSet(text)
    val file = read.file ?: return ImportCheck.Rejected(listOfNotNull(read.error))
    if (file.id == BuiltinFormulas.file.id) {
        return ImportCheck.Rejected(listOf(FormulaIssue(file.id, "id", Problem(ProblemKind.BUILTIN_ID))), file.id)
    }
    val replaces = stored.firstOrNull { readSet(it.json).file?.id == file.id }
    // The sets in use now, with the new file in its place; null stands for the new file
    val layers = stored.mapNotNull { set ->
        if (set == replaces) null to file else readSet(set.json).file?.takeIf { set.enabled }?.let { set to it }
    }.let { if (replaces == null) it + (null to file) else it }
    val stack = stack<FormulaSet?>(layers)
    stack.rejected[null]?.let { return ImportCheck.Rejected(it, file.id) }
    val before = layersOf(stored).sets.filter { it.active }.map { it.stored }
    val breaks = if (replaces?.enabled == false) emptyList() else before.filter { it != replaces && it in stack.rejected }
    return ImportCheck.Accepted(file, replaces, stack.model.warnings.filter { it.set == file.id }, breaks)
}

private class ReadSet(val file: FormulaSetFile?, val error: FormulaIssue?)

/** The file, or why it does not read: an issue of the file as a whole, with no set, since its id is unknown. */
private fun readSet(text: String): ReadSet = try {
    ReadSet(FormulaSetFile.parse(text), null)
} catch (e: FormulaSetException) {
    ReadSet(null, FormulaIssue("", "", e.problem))
}
