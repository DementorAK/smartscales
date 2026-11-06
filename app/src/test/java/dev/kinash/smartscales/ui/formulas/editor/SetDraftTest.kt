package dev.kinash.smartscales.ui.formulas.editor

import dev.kinash.smartscales.core.formula.BuiltinFormulas
import dev.kinash.smartscales.core.formula.FormulaSetFile
import dev.kinash.smartscales.core.formula.ProblemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class SetDraftTest {
    @Test
    fun aSetComesOutOfTheEditorAsItWentIn() {
        // The built-in set has every part an indicator can have: groups, parameters, conditions, zones with labels
        val file = BuiltinFormulas.file.copy(id = "copy")
        for (language in listOf("en", "ru", "de")) {
            val converted = SetDraft.of(file, language).toFile(language)
            assertEquals(emptyList<Any>(), converted.errors)
            assertEquals(language, file, converted.file)
        }
        val patched = FormulaSetFile.parse(
            """{"format": 1, "id": "p", "name": {"en": "P", "fr": "P fr"},
            "patches": [{"id": "bmi", "when": "age >= 20", "source": {"text": "Mine"}, "params": {"a": 2}}], "hidden": ["whr"]}""",
        )
        assertEquals(patched, SetDraft.of(patched, "ru").toFile("ru").file)
    }

    @Test
    fun textInThePhonesLanguageGoesBesideTheEnglish() {
        val draft = SetDraft(id = "mine", name = TextDraft(en = "Mine", local = "Мой"))
        assertEquals(mapOf("en" to "Mine", "ru" to "Мой"), draft.toFile("ru").file.name)
        // Blank English is left for the model to report at the field
        assertEquals(mapOf("en" to ""), SetDraft(id = "mine", name = TextDraft(local = "Мой")).toFile("en").file.name)
    }

    @Test
    fun fieldsThatDoNotReadAreReportedWhereTheyAre() {
        val draft = SetDraft(
            id = "mine",
            indicators = listOf(
                IndicatorDraft(
                    id = "x", precision = "two", formula = "a * weight",
                    params = listOf(ParamDraft("a", "1"), ParamDraft("a", "2")),
                    zones = listOf(RuleDraft(bounds = listOf("18,5", "x"))),
                ),
            ),
        )
        val converted = draft.toFile("en")
        assertEquals(
            listOf("indicators[0].params.a" to ProblemKind.PARAM_TWICE, "indicators[0].precision" to ProblemKind.NOT_A_NUMBER, "indicators[0].zones[0].bounds[1]" to ProblemKind.NOT_A_NUMBER),
            converted.errors.map { it.path to it.problem.kind },
        )
        // A comma reads as a decimal point
        assertEquals(18.5, converted.file.indicators.single().zones.single().bounds.first(), 0.0)
        assertTrue(converted.file.indicators.single().zones.single().bounds.last().isNaN())
    }

    @Test
    fun numbersAreWrittenAsTheyAreTyped() {
        assertEquals("25", numberText(25.0, Locale.ENGLISH))
        assertEquals("0.000123", numberText(0.000123, Locale.ENGLISH))
        assertEquals("-848", numberText(-848.0, Locale.ENGLISH))
        // With the comma of a Russian phone, which the fields read back
        assertEquals("18,5", numberText(18.5, Locale.forLanguageTag("ru")))
        assertEquals(0.000123, dev.kinash.smartscales.ui.common.parseNumber(numberText(0.000123, Locale.forLanguageTag("ru")))!!, 0.0)
    }

    @Test
    fun aDraftOutlivesTheProcessAsText() {
        // Kept in the saved state as JSON, numbers that do not read yet included
        val draft = SetDraft.of(BuiltinFormulas.file.copy(id = "copy"), "ru").let {
            it.copy(indicators = it.indicators + IndicatorDraft(id = "x", precision = "two"))
        }
        val json = kotlinx.serialization.json.Json
        assertEquals(draft, json.decodeFromString(SetDraft.serializer(), json.encodeToString(SetDraft.serializer(), draft)))
        val pages = listOf(EditorPage.Set, EditorPage.Patch(2))
        val serializer = kotlinx.serialization.builtins.ListSerializer(EditorPage.serializer())
        assertEquals(pages, json.decodeFromString(serializer, json.encodeToString(serializer, pages)))
    }

    @Test
    fun aZoneGoesWithTheBoundBelowIt() {
        val rule = RuleDraft(bounds = listOf("10", "20"))
        assertEquals(listOf("10"), rule.withoutZone(2).bounds)
        assertEquals(listOf("20"), rule.withoutZone(0).bounds)
        assertEquals(listOf("20"), rule.withoutZone(1).bounds)
        assertEquals(listOf("10", "20", ""), rule.withZoneAdded().bounds)
    }
}
