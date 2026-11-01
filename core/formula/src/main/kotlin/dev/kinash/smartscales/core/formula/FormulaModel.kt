package dev.kinash.smartscales.core.formula

import java.math.BigDecimal
import java.math.RoundingMode

/** Inputs every formula can use, in kilograms, centimetres, years and ohms. */
object StandardInputs {
    val types: Map<String, ValueType> = mapOf(
        "weight" to ValueType.NUMBER,
        "height" to ValueType.NUMBER,
        /** Years with a fraction, from the birth date and the time of the measurement. */
        "age" to ValueType.NUMBER,
        /** 1 for male, 0 for female, as in the published equations. */
        "sex" to ValueType.NUMBER,
        "male" to ValueType.BOOLEAN,
        "female" to ValueType.BOOLEAN,
        /** Foot-to-foot impedance with the scale's calibration factor; missing when the scale sends none or a placeholder. */
        "impedance" to ValueType.NUMBER,
        "heart_rate" to ValueType.NUMBER,
        "waist" to ValueType.NUMBER,
        "hips" to ValueType.NUMBER,
        "neck" to ValueType.NUMBER,
    )
}

/** A problem in a formula set: [set] and [path] say where, [position] is the character in a formula. */
data class FormulaIssue(val set: String, val path: String, val message: String, val position: Int? = null) {
    override fun toString() = "$set: $path: $message" + (position?.let { " (character ${it + 1})" } ?: "")
}

/** A formula set layer: the file and whether it is the built-in one, whose ids others may replace without a warning. */
data class FormulaLayer(val file: FormulaSetFile, val builtIn: Boolean = false)

/** An indicator after layering, with its parsed expressions and the set it came from. */
class Indicator internal constructor(
    val def: IndicatorDef,
    val set: String,
    internal val formula: Expr,
    internal val applies: Expr?,
    internal val zoneConditions: List<Expr?>,
) {
    val id: String get() = def.id
}

/**
 * Formula sets layered into one model: the built-in set at the bottom, the person's enabled sets above it in
 * order. [issues] lists errors that make the model unusable; [warnings] lists conflicts and leftovers that do not.
 */
class FormulaModel private constructor(
    val indicators: Map<String, Indicator>,
    /** Variants of each group in layer order: the first is the primary one unless a profile chooses another. */
    val groups: Map<String, List<String>>,
    val inputs: Map<String, InputDef>,
    val hidden: Set<String>,
    val issues: List<FormulaIssue>,
    val warnings: List<FormulaIssue>,
) {
    val isValid: Boolean get() = issues.isEmpty()

    /**
     * Values of all indicators for one measurement. [values] gives the inputs (missing ones are null);
     * [primary] the variant a profile chose for a group, when it chose one.
     */
    fun evaluate(values: Map<String, Any?>, primary: Map<String, String> = emptyMap()): Results {
        check(isValid) { "Formula model has errors: ${issues.first()}" }
        return Results(this, values, primary)
    }

    /** One measurement's indicator values, computed on demand and remembered. */
    class Results internal constructor(
        private val model: FormulaModel,
        private val values: Map<String, Any?>,
        private val primary: Map<String, String>,
    ) {
        private val cache = HashMap<String, Double?>()

        /** The value of an indicator, or null when it has no data or does not apply. */
        fun value(id: String): Double? {
            if (id in cache) return cache[id]
            val indicator = model.indicators[id] ?: return null
            val nameValue = { name: String -> lookup(indicator, name) }
            val result = if (indicator.applies != null && indicator.applies.evaluate(nameValue) != true) {
                null
            } else {
                indicator.formula.evaluate(nameValue) as Double?
            }
            cache[id] = result
            return result
        }

        /**
         * The value of a group and the variant it came from: the primary variant, or the next one in order
         * that has a value for this measurement (no impedance, so fat by BMI instead of by impedance).
         */
        fun group(group: String): Pair<String, Double>? =
            model.variantsInOrder(group, primary).firstNotNullOfOrNull { id -> value(id)?.let { id to it } }

        /**
         * The zone of the indicator's value, or null without a value or a matching rule. The value is rounded
         * to the indicator's precision first, so a BMI of 24.96 shown as 25.0 is in the zone from 25 up.
         */
        fun zone(id: String): Zone? {
            val value = value(id) ?: return null
            val indicator = model.indicators.getValue(id)
            val shown = BigDecimal(value.toString()).setScale(indicator.def.precision, RoundingMode.HALF_UP).toDouble()
            val nameValue = { name: String -> lookup(indicator, name) }
            for ((i, rule) in indicator.def.zones.withIndex()) {
                val condition = indicator.zoneConditions[i]
                if (condition != null && condition.evaluate(nameValue) != true) continue
                val index = rule.bounds.indexOfFirst { shown < it }.let { if (it < 0) rule.bounds.size else it }
                return Zone(rule.levels[index], rule.labels?.get(index), indicator.def.approximateZones)
            }
            return null
        }

        private fun lookup(indicator: Indicator, name: String): Any? = when (name) {
            in indicator.def.params -> indicator.def.params.getValue(name)
            in StandardInputs.types, in model.inputs -> values[name]
            in model.indicators -> value(name)
            in model.groups -> group(name)?.second
            else -> null
        }
    }

    data class Zone(val level: ZoneLevel, val label: LocalizedText?, val approximate: Boolean)

    /** The primary variant first, then the others in layer order. */
    internal fun variantsInOrder(group: String, primary: Map<String, String>): List<String> {
        val variants = groups[group].orEmpty()
        val chosen = primary[group]?.takeIf { it in variants } ?: return variants
        return listOf(chosen) + (variants - chosen)
    }

    /** The layers a usable model was built from, and those left out with the errors that made them unusable. */
    data class Usable(val model: FormulaModel, val rejected: Map<String, List<FormulaIssue>>)

    companion object {
        /** Layers [layers], bottom first, and checks the result. */
        fun build(layers: List<FormulaLayer>): FormulaModel = Builder(layers).build()

        /**
         * A model from the layers that work: each of the person's sets is added in order and left out when it
         * brings errors, so one broken set does not switch off the built-in indicators and the other sets.
         * The built-in layer is always kept; its errors are a bug of the app, caught by its tests.
         */
        fun buildUsable(layers: List<FormulaLayer>): Usable {
            val kept = ArrayList<FormulaLayer>()
            val rejected = LinkedHashMap<String, List<FormulaIssue>>()
            var model = build(emptyList())
            for (layer in layers) {
                val attempt = build(kept + layer)
                if (attempt.isValid || layer.builtIn) {
                    kept += layer
                    model = attempt
                } else {
                    rejected[layer.file.id] = attempt.issues
                }
            }
            return Usable(model, rejected)
        }
    }

    /** Where a part of an indicator was written: the set and the path in it. */
    private data class Origin(val set: String, val path: String, val builtIn: Boolean)

    /** An indicator during layering, with where its formula, condition and zones came from. */
    private data class Entry(val def: IndicatorDef, val formulaFrom: Origin, val whenFrom: Origin, val zonesFrom: Origin, val patchedBy: Origin?)

    private class Builder(private val layers: List<FormulaLayer>) {
        val issues = ArrayList<FormulaIssue>()
        val warnings = ArrayList<FormulaIssue>()
        val entries = LinkedHashMap<String, Entry>()
        val inputs = LinkedHashMap<String, Pair<InputDef, Origin>>()
        val hidden = LinkedHashMap<String, String>()
        val setIds = HashSet<String>()

        fun build(): FormulaModel {
            layers.forEach(::merge)
            val groups = LinkedHashMap<String, MutableList<String>>()
            entries.values.forEach { entry -> entry.def.group?.let { groups.getOrPut(it) { ArrayList() } += entry.def.id } }
            checkNamespaces(groups)
            val nameTypes = StandardInputs.types + inputs.keys.associateWith { ValueType.NUMBER } +
                entries.keys.associateWith { ValueType.NUMBER } + groups.keys.associateWith { ValueType.NUMBER }
            val indicators = LinkedHashMap<String, Indicator>()
            for ((id, entry) in entries) compile(entry, nameTypes)?.let { indicators[id] = it }
            if (issues.isEmpty()) findCycles(indicators, groups)
            for ((id, set) in hidden) {
                if (id !in entries && id !in groups) warnings += FormulaIssue(set, "hidden", "Nothing called '$id' to hide")
            }
            return FormulaModel(indicators, groups, inputs.mapValues { it.value.first }, hidden.keys, issues, warnings)
        }

        private fun merge(layer: FormulaLayer) {
            val file = layer.file
            val set = file.id
            if (checkId(set, "id", set) && !setIds.add(set)) issues += FormulaIssue(set, "id", "Another enabled set has the id '$set'")
            checkText(set, "name", file.name)
            file.description?.let { checkText(set, "description", it) }
            for ((i, input) in file.inputs.withIndex()) {
                val path = "inputs[$i]"
                if (!checkId(set, "$path.id", input.id)) continue
                if (input.id in StandardInputs.types) {
                    issues += FormulaIssue(set, "$path.id", "'${input.id}' is a standard input already")
                    continue
                }
                if (input.unit !in INPUT_UNITS) {
                    issues += FormulaIssue(set, "$path.unit", "An input is in cm, kg or none")
                }
                checkText(set, "$path.name", input.name)
                inputs[input.id]?.let { (other, from) ->
                    val units = if (other.unit != input.unit) " with another unit" else ""
                    warnings += FormulaIssue(set, "$path.id", "Input '${input.id}' from '${from.set}' is replaced by this set$units")
                }
                inputs[input.id] = input to Origin(set, path, layer.builtIn)
            }
            val seen = HashSet<String>()
            for ((i, def) in file.indicators.withIndex()) {
                val path = "indicators[$i]"
                if (!checkId(set, "$path.id", def.id)) continue
                def.group?.let { checkId(set, "$path.group", it) }
                if (!seen.add(def.id)) {
                    issues += FormulaIssue(set, "$path.id", "'${def.id}' is defined twice in this set")
                    continue
                }
                entries[def.id]?.let { below ->
                    // Replacing a built-in indicator is what layers are for; two of the person's sets doing it is a conflict
                    if (!below.formulaFrom.builtIn) {
                        warnings += FormulaIssue(set, "$path.id", "'${def.id}' from '${below.formulaFrom.set}' is replaced by this set")
                    }
                }
                checkDef(set, path, def)
                val origin = Origin(set, path, layer.builtIn)
                entries[def.id] = Entry(def, origin, origin, origin, null)
            }
            for ((i, patch) in file.patches.withIndex()) {
                val path = "patches[$i]"
                val entry = entries[patch.id]
                if (entry == null) {
                    issues += FormulaIssue(set, "$path.id", "No indicator '${patch.id}' below this set to change")
                    continue
                }
                if (entry.patchedBy != null && !entry.patchedBy.builtIn) {
                    warnings += FormulaIssue(set, "$path.id", "'${patch.id}' is also changed by '${entry.patchedBy.set}'; this set's changes win")
                }
                val def = entry.def
                val patched = def.copy(
                    name = patch.name ?: def.name,
                    precision = patch.precision ?: def.precision,
                    params = def.params + patch.params.orEmpty(),
                    applies = patch.applies ?: def.applies,
                    zones = patch.zones ?: def.zones,
                    approximateZones = patch.approximateZones ?: def.approximateZones,
                    source = patch.source ?: def.source,
                    description = patch.description ?: def.description,
                )
                checkDef(set, path, patched)
                val origin = Origin(set, path, layer.builtIn)
                entries[patch.id] = entry.copy(
                    def = patched,
                    whenFrom = if (patch.applies != null) origin else entry.whenFrom,
                    zonesFrom = if (patch.zones != null) origin else entry.zonesFrom,
                    patchedBy = origin,
                )
            }
            file.hidden.forEach { hidden[it] = set }
        }

        private fun checkId(set: String, path: String, id: String): Boolean {
            if (Expr.isValidName(id)) return true
            val why = if (id in Expr.RESERVED) "a reserved word" else "not Latin letters, digits and _, starting with a letter or _"
            issues += FormulaIssue(set, path, "'$id' cannot be an id: $why")
            return false
        }

        /** Every text needs English: the app falls back to it in any other language. */
        private fun checkText(set: String, path: String, text: LocalizedText) {
            if (text["en"].isNullOrBlank()) issues += FormulaIssue(set, path, "English text ('en') is required")
        }

        private fun checkDef(set: String, path: String, def: IndicatorDef) {
            checkText(set, "$path.name", def.name)
            def.description?.let { checkText(set, "$path.description", it) }
            if (def.precision !in 0..MAX_PRECISION) issues += FormulaIssue(set, "$path.precision", "Precision must be 0 to $MAX_PRECISION")
            for ((param, value) in def.params) {
                if (!Expr.isValidName(param)) issues += FormulaIssue(set, "$path.params.$param", "'$param' cannot be a parameter name")
                if (!value.isFinite()) issues += FormulaIssue(set, "$path.params.$param", "Not a number")
            }
            for ((i, rule) in def.zones.withIndex()) {
                val zonePath = "$path.zones[$i]"
                if (rule.levels.size != rule.bounds.size + 1) {
                    issues += FormulaIssue(set, "$zonePath.levels", "Needs one level more than bounds: ${rule.bounds.size + 1}")
                }
                if (rule.bounds.zipWithNext().any { (a, b) -> a >= b }) {
                    issues += FormulaIssue(set, "$zonePath.bounds", "Bounds must go up")
                }
                if (rule.labels != null && rule.labels.size != rule.levels.size) {
                    issues += FormulaIssue(set, "$zonePath.labels", "Needs one label per level")
                }
                rule.labels?.forEachIndexed { j, label -> checkText(set, "$zonePath.labels[$j]", label) }
            }
        }

        /**
         * Indicators, groups and inputs share one set of names, and parameters must not hide any of them: a
         * parameter `weight` would quietly replace the real weight in its formula.
         */
        private fun checkNamespaces(groups: Map<String, List<String>>) {
            val inputNames = StandardInputs.types.keys + inputs.keys
            for ((group, variants) in groups) {
                val from = entries.getValue(variants.first()).formulaFrom
                if (group in entries) issues += FormulaIssue(from.set, "${from.path}.group", "'$group' is both a group and an indicator")
                if (group in inputNames) issues += FormulaIssue(from.set, "${from.path}.group", "Group '$group' has the name of an input")
            }
            for ((id, entry) in entries) {
                val from = entry.formulaFrom
                if (id in inputNames) issues += FormulaIssue(from.set, "${from.path}.id", "Indicator '$id' has the name of an input")
                val owner = entry.patchedBy ?: from
                for (param in entry.def.params.keys) {
                    when {
                        param in inputNames ->
                            issues += FormulaIssue(owner.set, "${owner.path}.params.$param", "Parameter '$param' would hide the input of that name")
                        // A set above may add an indicator with a parameter's name; the formula keeps its parameter
                        param in entries || param in groups ->
                            warnings += FormulaIssue(owner.set, "${owner.path}.params.$param", "Parameter '$param' hides the indicator of that name in this formula")
                    }
                }
            }
        }

        private fun compile(entry: Entry, nameTypes: Map<String, ValueType>): Indicator? {
            val def = entry.def
            val typeOf = { name: String -> if (name in def.params) ValueType.NUMBER else nameTypes[name] }
            val formula = parse(entry.formulaFrom, "formula", def.formula, typeOf, ValueType.NUMBER)
            val applies = def.applies?.let { parse(entry.whenFrom, "when", it, typeOf, ValueType.BOOLEAN) }
            val zones = def.zones.mapIndexed { i, rule ->
                rule.applies?.let { parse(entry.zonesFrom, "zones[$i].when", it, typeOf, ValueType.BOOLEAN) }
            }
            val failed = formula == null || (def.applies != null && applies == null) ||
                def.zones.indices.any { def.zones[it].applies != null && zones[it] == null }
            if (failed) return null
            val used = formula!!.names().keys + applies?.names()?.keys.orEmpty() + zones.flatMap { it?.names()?.keys.orEmpty() }
            for (param in def.params.keys - used) {
                val owner = entry.patchedBy ?: entry.formulaFrom
                warnings += FormulaIssue(owner.set, "${owner.path}.params.$param", "Parameter is not used")
            }
            return Indicator(def, entry.formulaFrom.set, formula, applies, zones)
        }

        /** Errors are reported where the text was written: a patch's condition at the patch, the formula below it. */
        private fun parse(origin: Origin, field: String, text: String, typeOf: (String) -> ValueType?, type: ValueType): Expr? = try {
            val expr = Expr.parse(text)
            if (expr.check(typeOf) != type) {
                val wanted = if (type == ValueType.NUMBER) "a number" else "a yes/no condition"
                issues += FormulaIssue(origin.set, "${origin.path}.$field", "Must give $wanted", 0)
                null
            } else {
                expr
            }
        } catch (e: FormulaException) {
            issues += FormulaIssue(origin.set, "${origin.path}.$field", e.reason, e.position)
            null
        }

        /**
         * Indicators referring to each other in a loop cannot be computed. A group reference may lead to any of
         * its variants, depending on the profile, so it counts as referring to all of them.
         */
        private fun findCycles(indicators: Map<String, Indicator>, groups: Map<String, List<String>>) {
            fun references(indicator: Indicator): List<String> {
                val names = indicator.formula.names().keys + indicator.applies?.names()?.keys.orEmpty() +
                    indicator.zoneConditions.flatMap { it?.names()?.keys.orEmpty() }
                return names.filter { it !in indicator.def.params }.flatMap { name ->
                    when (name) {
                        in indicators -> listOf(name)
                        in groups -> groups.getValue(name)
                        else -> emptyList()
                    }
                }
            }
            val state = HashMap<String, Int>() // 1 visiting, 2 done
            val path = ArrayList<String>()
            fun visit(id: String): Boolean {
                when (state[id]) {
                    1 -> {
                        val loop = path.subList(path.indexOf(id), path.size) + id
                        val from = entries.getValue(id).formulaFrom
                        issues += FormulaIssue(from.set, "${from.path}.formula", "Formulas refer to each other in a loop: ${loop.joinToString(" → ")}")
                        return true
                    }
                    2 -> return false
                }
                state[id] = 1
                path += id
                val found = references(indicators.getValue(id)).any(::visit)
                path.removeAt(path.lastIndex)
                state[id] = 2
                return found
            }
            for (id in indicators.keys) if (visit(id)) return
        }
    }
}

/** Decimals an indicator may show. */
private const val MAX_PRECISION = 4

/** Hand-entered values are lengths, masses or plain numbers. */
private val INPUT_UNITS = setOf(IndicatorUnit.CM, IndicatorUnit.KG, IndicatorUnit.NONE)
