package dev.kinash.smartscales.core.formula

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Text in several languages by language code (`en`, `ru`…); `en` is required and is the fallback. */
typealias LocalizedText = Map<String, String>

/** The text for [language], or English; any text at all rather than a crash if a set slipped through without one. */
fun LocalizedText.text(language: String): String =
    this[language] ?: this[language.substringBefore('-')] ?: this["en"] ?: values.firstOrNull().orEmpty()

/**
 * A formula set file, format version [FORMAT]. The built-in set and the sets people import use the same format.
 * Sets are layers: [indicators] add indicators or replace lower ones with the same id, [patches] change parts
 * of lower ones, [hidden] removes indicators or groups from the screens but not from the calculation.
 */
@Serializable
data class FormulaSetFile(
    val format: Int,
    val id: String,
    val name: LocalizedText,
    val description: LocalizedText? = null,
    val inputs: List<InputDef> = emptyList(),
    /** Names of indicator groups, shown where the group's value is shown whichever variant gave it. */
    val groups: List<GroupDef> = emptyList(),
    val indicators: List<IndicatorDef> = emptyList(),
    val patches: List<IndicatorPatch> = emptyList(),
    val hidden: List<String> = emptyList(),
) {
    companion object {
        const val FORMAT = 1

        private val json = Json {
            // A misspelt key is a mistake to report, not something to skip quietly
            ignoreUnknownKeys = false
            isLenient = false
        }

        @OptIn(ExperimentalSerializationApi::class)
        private val writer = Json {
            prettyPrint = true
            prettyPrintIndent = "  "
            // A file as someone would write it by hand: without the values left as they are
            encodeDefaults = false
            explicitNulls = false
        }

        /** The text of [file] in the format [parse] reads, as the editor saves a set. */
        fun encode(file: FormulaSetFile): String = writer.encodeToString(serializer(), file) + "\n"

        /**
         * Reads a set; a malformed file throws [FormulaSetException] naming the place. The format version is
         * checked first, so a newer file is reported as such and not as one with unknown keys.
         */
        fun parse(text: String): FormulaSetFile = try {
            // Notepad and some phone editors save UTF-8 with a byte order mark, which the parser does not skip
            val tree = json.parseToJsonElement(text.removePrefix("\uFEFF")) as? JsonObject ?: throw FormulaSetException(Problem(ProblemKind.NOT_OBJECT))
            val format = (tree["format"] as? JsonPrimitive)?.intOrNull ?: throw FormulaSetException(Problem(ProblemKind.NO_FORMAT))
            if (format != FORMAT) throw FormulaSetException(Problem(ProblemKind.UNSUPPORTED_FORMAT, format, FORMAT))
            json.decodeFromJsonElement(serializer(), tree)
        } catch (e: SerializationException) {
            throw FormulaSetException(Problem(ProblemKind.MALFORMED, brief(e.message)))
        } catch (e: IllegalArgumentException) {
            throw FormulaSetException(Problem(ProblemKind.MALFORMED, brief(e.message)))
        }

        /** The parser's message without the piece of JSON it quotes after it, which can be long. */
        private fun brief(message: String?): String {
            val text = message.orEmpty().substringBefore("JSON input:").trim()
            return if (text.length > MAX_MESSAGE) text.take(MAX_MESSAGE).trimEnd() + "…" else text
        }

        private const val MAX_MESSAGE = 300
    }
}

/** A malformed formula set file. */
class FormulaSetException(val problem: Problem) : Exception(problem.english)

/** A value entered by hand that formulas can use, declared by a set (for example, wrist circumference). */
@Serializable
data class InputDef(
    val id: String,
    val name: LocalizedText,
    /** `cm` or `kg` for values shown in the profile's units; formulas always get centimetres and kilograms. */
    val unit: IndicatorUnit = IndicatorUnit.NONE,
    /** What to enter and how to measure it, shown under the field: needed most by a field without a unit. */
    val description: LocalizedText? = null,
)

/** The name of an indicator group, such as "Body fat" for `bf`; a layer above may rename it. */
@Serializable
data class GroupDef(val id: String, val name: LocalizedText)

/** One indicator, such as fat-free mass by Wu 2015. */
@Serializable
data class IndicatorDef(
    val id: String,
    /** Variants of one indicator share a group (`ffm` for `ffm_wu`, `ffm_sun`); a reference to it takes the primary one. */
    val group: String? = null,
    val name: LocalizedText,
    val unit: IndicatorUnit = IndicatorUnit.NONE,
    /** Decimals shown. */
    val precision: Int = 1,
    val formula: String,
    /** Named coefficients the formula uses, so a layer above can change them without rewriting it. */
    val params: Map<String, Double> = emptyMap(),
    /** When the formula applies, for example `age >= 18 and has(impedance)`; otherwise there is no value. */
    @SerialName("when") val applies: String? = null,
    val zones: List<ZoneRule> = emptyList(),
    /** The ranges are rough, not from a validated source: shown with a note. */
    val approximateZones: Boolean = false,
    val source: Source? = null,
    val description: LocalizedText? = null,
)

/**
 * A partial change of an indicator from a lower layer; fields left out stay as they are, so a patch cannot
 * remove a condition or a source. That, and a new formula, need the whole indicator replaced.
 */
@Serializable
data class IndicatorPatch(
    val id: String,
    val name: LocalizedText? = null,
    val precision: Int? = null,
    /** Merged into the existing parameters; parameters not listed keep their values. */
    val params: Map<String, Double>? = null,
    @SerialName("when") val applies: String? = null,
    /** Replaces all zone rules. */
    val zones: List<ZoneRule>? = null,
    val approximateZones: Boolean? = null,
    val source: Source? = null,
    val description: LocalizedText? = null,
)

/**
 * Ranges of an indicator for the people [applies] matches (for example, `male and age < 40`): below
 * `bounds[0]` is `levels[0]`, from `bounds[i-1]` up to `bounds[i]` is `levels[i]`, from the last bound up is the
 * last level; a value at a bound goes to the side [atBound] says. The first rule that applies is used.
 */
@Serializable
data class ZoneRule(
    @SerialName("when") val applies: String? = null,
    val bounds: List<Double>,
    val levels: List<ZoneLevel>,
    /** Names of the zones, one per level, when the level alone is not enough ("Obesity class II"). */
    val labels: List<LocalizedText>? = null,
    /** Which zone a value exactly at a bound is in: the one above (`≥ bound`, the default) or below (`> bound`). */
    val atBound: BoundSide = BoundSide.ABOVE,
)

/** The side of a zone bound that the bound itself belongs to. */
@Serializable
enum class BoundSide {
    /** A value equal to the bound is in the zone above it: the source says "≥ 25". */
    @SerialName("above") ABOVE,

    /** A value equal to the bound is in the zone below it: the source says "> 94". */
    @SerialName("below") BELOW,
}

/** How a value compares with the norm; the app gives each a colour. */
@Serializable
enum class ZoneLevel {
    @SerialName("very_low") VERY_LOW,
    @SerialName("low") LOW,
    @SerialName("normal") NORMAL,
    @SerialName("elevated") ELEVATED,
    @SerialName("high") HIGH,
    @SerialName("very_high") VERY_HIGH,
}

/** How a value is shown. Formulas always work in kilograms and centimetres; `kg` and `cm` follow the profile's units. */
@Serializable
enum class IndicatorUnit {
    @SerialName("kg") KG,
    @SerialName("cm") CM,
    @SerialName("percent") PERCENT,
    @SerialName("liter") LITER,
    @SerialName("kcal") KCAL,
    @SerialName("kg_m2") KG_M2,
    @SerialName("ohm") OHM,
    @SerialName("none") NONE,
}

@Serializable
data class Source(val text: String, val url: String? = null)
