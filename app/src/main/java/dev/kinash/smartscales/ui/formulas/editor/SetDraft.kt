package dev.kinash.smartscales.ui.formulas.editor

import dev.kinash.smartscales.core.formula.BoundSide
import dev.kinash.smartscales.core.formula.FormulaIssue
import dev.kinash.smartscales.core.formula.FormulaSetFile
import dev.kinash.smartscales.core.formula.GroupDef
import dev.kinash.smartscales.core.formula.IndicatorDef
import dev.kinash.smartscales.core.formula.IndicatorPatch
import dev.kinash.smartscales.core.formula.IndicatorUnit
import dev.kinash.smartscales.core.formula.InputDef
import dev.kinash.smartscales.core.formula.LocalizedText
import dev.kinash.smartscales.core.formula.Problem
import dev.kinash.smartscales.core.formula.ProblemKind
import dev.kinash.smartscales.core.formula.Source
import dev.kinash.smartscales.core.formula.ZoneLevel
import dev.kinash.smartscales.core.formula.ZoneRule
import dev.kinash.smartscales.ui.common.parseNumber
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.text.DecimalFormatSymbols
import java.util.Locale

/*
 * A formula set as the editor holds it while the person types: numbers as the text in their fields, texts in
 * English and in the phone's language. [SetDraft.toFile] turns it into the file it saves, with the fields that do
 * not read yet as issues at the paths the model's own issues use, so both show at the same field.
 */

/** Text in English and in the phone's language; other languages a file had stay as they were. */
@Serializable
data class TextDraft(val en: String = "", val local: String = "", val others: Map<String, String> = emptyMap()) {
    val isBlank: Boolean get() = en.isBlank() && local.isBlank() && others.isEmpty()

    /**
     * The text; English, which a file needs, is the local text when only that is written. With neither, English is
     * blank, and the model reports that at the field.
     */
    fun toText(language: String): LocalizedText {
        val text = LinkedHashMap<String, String>()
        text["en"] = en.trim().ifEmpty { if (language != "en") local.trim() else "" }
        if (language != "en" && local.isNotBlank()) text[language] = local.trim()
        return text + others
    }

    /** Null when nothing is written, for the texts a set may leave out. */
    fun toOptional(language: String): LocalizedText? = if (isBlank) null else toText(language)

    companion object {
        fun of(text: LocalizedText?, language: String) = TextDraft(
            // As the file has it, even the same as the local text: "BMI" or "Gallagher 2000" is English too, and
            // hiding it would let a change of the local text overwrite it unseen
            en = text?.get("en").orEmpty(),
            local = if (language == "en") "" else text?.get(language).orEmpty(),
            others = text.orEmpty() - "en" - language,
        )
    }
}

@Serializable
data class ParamDraft(val name: String = "", val value: String = "")

@Serializable
data class ZoneDraft(val level: ZoneLevel, val label: TextDraft = TextDraft())

/** A zone rule: its [zones] from the lowest, with one bound between each two in [bounds]. */
@Serializable
data class RuleDraft(
    val applies: String = "",
    val zones: List<ZoneDraft> = listOf(ZoneDraft(ZoneLevel.LOW), ZoneDraft(ZoneLevel.NORMAL), ZoneDraft(ZoneLevel.HIGH)),
    val bounds: List<String> = listOf("", ""),
    val atBound: BoundSide = BoundSide.ABOVE,
    /** Whether the zones have names of their own. */
    val labeled: Boolean = false,
) {
    fun withZoneAdded(): RuleDraft = copy(zones = zones + ZoneDraft(ZoneLevel.HIGH), bounds = bounds + "")

    /** Without zone [index] and the bound below it (above it for the lowest). */
    fun withoutZone(index: Int): RuleDraft = copy(
        zones = zones.filterIndexed { i, _ -> i != index },
        bounds = bounds.filterIndexed { i, _ -> i != maxOf(index - 1, 0) },
    )

    companion object {
        fun of(rule: ZoneRule, language: String) = RuleDraft(
            applies = rule.applies.orEmpty(),
            zones = rule.levels.mapIndexed { i, level -> ZoneDraft(level, TextDraft.of(rule.labels?.getOrNull(i), language)) },
            bounds = rule.bounds.map(::numberText),
            atBound = rule.atBound,
            labeled = rule.labels != null,
        )
    }
}

@Serializable
data class IndicatorDraft(
    val id: String = "",
    val group: String = "",
    val name: TextDraft = TextDraft(),
    val unit: IndicatorUnit = IndicatorUnit.NONE,
    val precision: String = "1",
    val formula: String = "",
    val applies: String = "",
    val params: List<ParamDraft> = emptyList(),
    val zones: List<RuleDraft> = emptyList(),
    val approximateZones: Boolean = false,
    val sourceText: String = "",
    val sourceUrl: String = "",
    val description: TextDraft = TextDraft(),
) {
    companion object {
        fun of(def: IndicatorDef, language: String) = IndicatorDraft(
            id = def.id,
            group = def.group.orEmpty(),
            name = TextDraft.of(def.name, language),
            unit = def.unit,
            precision = def.precision.toString(),
            formula = def.formula,
            applies = def.applies.orEmpty(),
            params = def.params.map { (name, value) -> ParamDraft(name, numberText(value)) },
            zones = def.zones.map { RuleDraft.of(it, language) },
            approximateZones = def.approximateZones,
            sourceText = def.source?.text.orEmpty(),
            sourceUrl = def.source?.url.orEmpty(),
            description = TextDraft.of(def.description, language),
        )
    }
}

/**
 * A change of an indicator below: blank fields leave it as it is. [zones] null keeps its norms; [original] keeps
 * what the editor does not show (a condition, a source, a description a file brought).
 */
@Serializable
data class PatchDraft(
    val id: String,
    val name: TextDraft = TextDraft(),
    val precision: String = "",
    /** New values of parameters by name; a blank one is not changed. */
    val params: Map<String, String> = emptyMap(),
    val zones: List<RuleDraft>? = null,
    val approximateZones: Boolean = false,
    val original: IndicatorPatch = IndicatorPatch(id),
) {
    companion object {
        fun of(patch: IndicatorPatch, language: String) = PatchDraft(
            id = patch.id,
            name = TextDraft.of(patch.name, language),
            precision = patch.precision?.toString().orEmpty(),
            params = patch.params.orEmpty().mapValues { numberText(it.value) },
            zones = patch.zones?.map { RuleDraft.of(it, language) },
            approximateZones = patch.approximateZones ?: false,
            original = patch,
        )
    }
}

@Serializable
data class InputDraft(
    val id: String = "",
    val name: TextDraft = TextDraft(),
    val unit: IndicatorUnit = IndicatorUnit.CM,
    val description: TextDraft = TextDraft(),
)

@Serializable
data class GroupDraft(val id: String = "", val name: TextDraft = TextDraft())

@Serializable
data class SetDraft(
    val id: String,
    val name: TextDraft = TextDraft(),
    val description: TextDraft = TextDraft(),
    val indicators: List<IndicatorDraft> = emptyList(),
    val patches: List<PatchDraft> = emptyList(),
    val hidden: List<String> = emptyList(),
    val inputs: List<InputDraft> = emptyList(),
    val groups: List<GroupDraft> = emptyList(),
) {
    /** The file to save, and the fields that do not read: a number that is not one. */
    class Converted(val file: FormulaSetFile, val errors: List<FormulaIssue>)

    fun toFile(language: String): Converted {
        val errors = ArrayList<FormulaIssue>()
        // A number that does not read stands as NaN, which keeps the rest in place for the check: the model
        // reports a parameter that is not a number itself, a bound is reported here
        fun unread(text: String) = Problem(if (text.isBlank()) ProblemKind.REQUIRED else ProblemKind.NOT_A_NUMBER)

        fun bound(text: String, path: String): Double =
            parseNumber(text) ?: Double.NaN.also { errors += FormulaIssue(id, path, unread(text)) }

        fun param(text: String): Double = parseNumber(text) ?: Double.NaN

        fun rules(rules: List<RuleDraft>, path: String) = rules.mapIndexed { j, rule ->
            ZoneRule(
                applies = rule.applies.trim().ifEmpty { null },
                bounds = rule.bounds.mapIndexed { k, text -> bound(text, "$path.zones[$j].bounds[$k]") },
                levels = rule.zones.map { it.level },
                labels = if (rule.labeled) rule.zones.map { it.label.toText(language) } else null,
                atBound = rule.atBound,
            )
        }

        val indicators = indicators.mapIndexed { i, draft ->
            val path = "indicators[$i]"
            draft.params.map { it.name.trim() }.groupingBy { it }.eachCount().filter { it.value > 1 }.keys.forEach { name ->
                errors += FormulaIssue(id, "$path.params.$name", Problem(ProblemKind.PARAM_TWICE, name))
            }
            // The model says "not a number" of a blank value too; this says what it is
            draft.params.filter { it.value.isBlank() }.forEach { errors += FormulaIssue(id, "$path.params.${it.name.trim()}", Problem(ProblemKind.REQUIRED)) }
            IndicatorDef(
                id = draft.id.trim(),
                group = draft.group.trim().ifEmpty { null },
                name = draft.name.toText(language),
                unit = draft.unit,
                precision = draft.precision.trim().toIntOrNull()
                    ?: 1.also { errors += FormulaIssue(id, "$path.precision", unread(draft.precision)) },
                formula = draft.formula.trim(),
                params = draft.params.associate { it.name.trim() to param(it.value) },
                applies = draft.applies.trim().ifEmpty { null },
                zones = rules(draft.zones, path),
                approximateZones = draft.approximateZones && draft.zones.isNotEmpty(),
                source = draft.sourceText.trim().takeIf { it.isNotEmpty() }?.let { Source(it, draft.sourceUrl.trim().ifEmpty { null }) },
                description = draft.description.toOptional(language),
            )
        }
        val patches = patches.mapIndexed { i, draft ->
            val path = "patches[$i]"
            draft.original.copy(
                name = draft.name.toOptional(language),
                precision = draft.precision.trim().takeIf { it.isNotEmpty() }?.let {
                    it.toIntOrNull() ?: null.also { errors += FormulaIssue(id, "$path.precision", Problem(ProblemKind.NOT_A_NUMBER)) }
                },
                params = draft.params.filterValues { it.isNotBlank() }
                    .mapValues { param(it.value) }.ifEmpty { null },
                zones = draft.zones?.let { rules(it, path) },
                approximateZones = if (draft.zones != null) draft.approximateZones else draft.original.approximateZones,
            )
        }
        val file = FormulaSetFile(
            format = FormulaSetFile.FORMAT,
            id = id.trim(),
            name = name.toText(language),
            description = description.toOptional(language),
            inputs = inputs.map { InputDef(it.id.trim(), it.name.toText(language), it.unit, it.description.toOptional(language)) },
            groups = groups.map { GroupDef(it.id.trim(), it.name.toText(language)) },
            indicators = indicators,
            patches = patches,
            hidden = hidden,
        )
        return Converted(file, errors)
    }

    companion object {
        fun of(file: FormulaSetFile, language: String) = SetDraft(
            id = file.id,
            name = TextDraft.of(file.name, language),
            description = TextDraft.of(file.description, language),
            indicators = file.indicators.map { IndicatorDraft.of(it, language) },
            patches = file.patches.map { PatchDraft.of(it, language) },
            hidden = file.hidden,
            inputs = file.inputs.map { InputDraft(it.id, TextDraft.of(it.name, language), it.unit, TextDraft.of(it.description, language)) },
            groups = file.groups.map { GroupDraft(it.id, TextDraft.of(it.name, language)) },
        )
    }
}

/**
 * A number as it is typed in [locale]: `25`, not `25.0`; `0.000123`, not `1.23E-4`; with a comma where the phone
 * writes one, as the app's other fields do ([parseNumber] reads both).
 */
fun numberText(value: Double, locale: Locale = Locale.getDefault()): String {
    if (!value.isFinite()) return value.toString()
    val plain = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
    val separator = DecimalFormatSymbols.getInstance(locale).decimalSeparator
    return if (separator == '.') plain else plain.replace('.', separator)
}
