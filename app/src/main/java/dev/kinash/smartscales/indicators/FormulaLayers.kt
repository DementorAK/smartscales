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
    val placed = place(file, replaces, stored)
    placed.errors.takeIf { it.isNotEmpty() }?.let { return ImportCheck.Rejected(it, file.id) }
    return ImportCheck.Accepted(file, replaces, placed.warnings, placed.breaks)
}

/**
 * A set from the editor checked as saving it would leave it: [model] has it in its place (on top for a new one),
 * and is null with [errors]; [breaks] as in [ImportCheck.Accepted]. [below] is the model of the layers under it,
 * the indicators it can change or hide.
 */
class EditCheck(
    val model: FormulaModel?,
    val below: FormulaModel,
    val errors: List<FormulaIssue>,
    val warnings: List<FormulaIssue>,
    val breaks: List<FormulaSet>,
)

/**
 * Checks [file] made in the editor as [editing] (null for a new set) among [stored] ones, as [checkImport] checks a
 * file; a set switched off is checked as if it were on. Its id must be its own: the built-in one's or another
 * stored set's would make importing a file of that id replace the wrong set.
 */
fun checkEdit(file: FormulaSetFile, editing: FormulaSet?, stored: List<FormulaSet>): EditCheck {
    val placed = place(file, editing, stored)
    val taken = when {
        file.id == BuiltinFormulas.file.id -> Problem(ProblemKind.BUILTIN_ID)
        stored.any { it.id != editing?.id && readSet(it.json).file?.id == file.id } -> Problem(ProblemKind.SET_ID_USED, file.id)
        else -> null
    }
    val errors = listOfNotNull(taken?.let { FormulaIssue(file.id, "id", it) }) + placed.errors
    return EditCheck(placed.model.takeIf { errors.isEmpty() }, placed.below, errors, placed.warnings, placed.breaks)
}

private class Placed(
    val model: FormulaModel,
    val below: FormulaModel,
    val errors: List<FormulaIssue>,
    val warnings: List<FormulaIssue>,
    val breaks: List<FormulaSet>,
)

/** The sets in use with [file] in the place of [at] (by its id: its text may have changed since), or on top without one. */
private fun place(file: FormulaSetFile, at: FormulaSet?, stored: List<FormulaSet>): Placed {
    val place = at?.let { set -> stored.firstOrNull { it.id == set.id } }
    // Null stands for the new file
    val layers = stored.mapNotNull { set ->
        if (set == place) null to file else readSet(set.json).file?.takeIf { set.enabled }?.let { set to it }
    }.let { if (place == null) it + (null to file) else it }
    val stack = stack<FormulaSet?>(layers)
    val below = stack(layers.takeWhile { it.first != null }).model
    val errors = stack.rejected[null].orEmpty()
    val before = layersOf(stored).sets.filter { it.active }.map { it.stored }
    val breaks = if (errors.isNotEmpty() || place?.enabled == false) emptyList() else before.filter { it != place && it in stack.rejected }
    return Placed(stack.model, below, errors, stack.model.warnings.filter { it.set == file.id }.takeIf { errors.isEmpty() }.orEmpty(), breaks)
}

private class ReadSet(val file: FormulaSetFile?, val error: FormulaIssue?)

/**
 * The file, or why it does not read: an issue of the file as a whole, with no set, since its id is unknown. The
 * editor checks its draft among the stored sets at every pause in the typing, so the last texts read are kept.
 */
private fun readSet(text: String): ReadSet = synchronized(readCache) {
    readCache.getOrPut(text) {
        try {
            ReadSet(FormulaSetFile.parse(text), null)
        } catch (e: FormulaSetException) {
            ReadSet(null, FormulaIssue("", "", e.problem))
        }
    }
}

private const val READ_CACHE_SIZE = 32

private val readCache = object : LinkedHashMap<String, ReadSet>(READ_CACHE_SIZE, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ReadSet>?) = size > READ_CACHE_SIZE
}
