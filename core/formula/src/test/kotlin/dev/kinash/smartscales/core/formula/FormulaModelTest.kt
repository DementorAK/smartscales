package dev.kinash.smartscales.core.formula

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FormulaModelTest {
    private val base = """
        {
          "format": 1,
          "id": "base",
          "name": {"en": "Base", "ru": "Основа"},
          "indicators": [
            {
              "id": "bmi", "name": {"en": "BMI"}, "unit": "kg_m2",
              "formula": "weight / (height / 100)^2",
              "zones": [{"bounds": [18.5, 25, 30], "levels": ["low", "normal", "elevated", "high"]}]
            },
            {
              "id": "ffm_wu", "group": "ffm", "name": {"en": "Fat-free mass (Wu 2015)"}, "unit": "kg",
              "formula": "a + b * weight + c * height^2 / impedance - d * age + e * sex",
              "params": {"a": 13.055, "b": 0.204, "c": 0.394, "d": 0.136, "e": 8.125},
              "when": "has(impedance)"
            },
            {
              "id": "ffm_boer", "group": "ffm", "name": {"en": "Lean body mass (Boer 1984)"}, "unit": "kg",
              "formula": "if(male, 0.407 * weight + 0.267 * height - 19.2, 0.252 * weight + 0.473 * height - 48.3)"
            },
            {
              "id": "bf", "name": {"en": "Body fat"}, "unit": "percent",
              "formula": "(weight - ffm) / weight * 100",
              "zones": [
                {"when": "male", "bounds": [8, 20, 25], "levels": ["low", "normal", "elevated", "high"]},
                {"bounds": [21, 33, 39], "levels": ["low", "normal", "elevated", "high"]}
              ]
            }
          ]
        }
    """.trimIndent()

    private val man = mapOf<String, Any?>(
        "weight" to 80.0, "height" to 180.0, "age" to 35.0, "sex" to 1.0, "male" to true, "female" to false,
        "impedance" to 450.0,
    )

    private fun model(vararg user: String) = FormulaModel.build(
        listOf(FormulaLayer(FormulaSetFile.parse(base), builtIn = true)) + user.map { FormulaLayer(FormulaSetFile.parse(it)) },
    )

    private fun set(id: String, body: String) = """{"format": 1, "id": "$id", "name": {"en": "$id"}, $body}"""

    private fun issues(vararg user: String) = model(*user).issues.map { it.toString() }

    @Test
    fun indicatorsAndParameters() {
        val results = model().evaluate(man)
        assertEquals(24.69, results.value("bmi")!!, 0.01)
        assertEquals(23.6, results.value("bf")!!, 0.05)
    }

    @Test
    fun groupFallsBackWhenThePrimaryVariantHasNoValue() {
        val results = model().evaluate(man + ("impedance" to null))
        assertNull(results.value("ffm_wu"))
        val (variant, ffm) = results.group("ffm")!!
        assertEquals("ffm_boer", variant)
        assertEquals(0.407 * 80 + 0.267 * 180 - 19.2, ffm, 1e-9)
        assertEquals((80 - ffm) / 80 * 100, results.value("bf")!!, 1e-9)
    }

    @Test
    fun profileChoosesThePrimaryVariant() {
        val results = model().evaluate(man, primary = mapOf("ffm" to "ffm_boer"))
        assertEquals("ffm_boer", results.group("ffm")!!.first)
        // An unknown choice falls back to the set's order
        assertEquals("ffm_wu", model().evaluate(man, primary = mapOf("ffm" to "gone")).group("ffm")!!.first)
    }

    @Test
    fun missingInputMeansNoValue() {
        val results = model().evaluate(man + ("weight" to null))
        assertNull(results.value("bmi"))
        assertNull(results.zone("bmi"))
    }

    @Test
    fun zonesBySex() {
        assertEquals(ZoneLevel.ELEVATED, model().evaluate(man).zone("bf")!!.level)
        val woman = man + mapOf("sex" to 0.0, "male" to false, "female" to true, "weight" to 60.0, "height" to 165.0, "impedance" to 550.0)
        assertEquals(ZoneLevel.ELEVATED, model().evaluate(woman).zone("bf")!!.level)
        assertEquals(ZoneLevel.NORMAL, model().evaluate(man).zone("bmi")!!.level)
        // A bound belongs to the zone above it
        assertEquals(ZoneLevel.ELEVATED, model().evaluate(man + ("weight" to 81.0)).zone("bmi")!!.level)
    }

    @Test
    fun patchChangesParametersOnly() {
        val patched = model(set("tweak", """"patches": [{"id": "ffm_wu", "params": {"a": 10.0}}]"""))
        assertTrue(patched.issues.isEmpty())
        val before = model().evaluate(man).value("ffm_wu")!!
        assertEquals(before - 3.055, patched.evaluate(man).value("ffm_wu")!!, 1e-9)
        assertEquals(0.204, patched.indicators.getValue("ffm_wu").def.params["b"]!!, 0.0)
    }

    @Test
    fun layersReplaceAndWarnOnlyAboutUserConflicts() {
        val one = set("one", """"indicators": [{"id": "bmi", "name": {"en": "BMI"}, "formula": "1"}]""")
        val two = set("two", """"indicators": [{"id": "bmi", "name": {"en": "BMI"}, "formula": "2"}]""")
        assertTrue(model(one).warnings.isEmpty())
        assertEquals(1.0, model(one).evaluate(man).value("bmi")!!, 0.0)
        val both = model(one, two)
        assertEquals(2.0, both.evaluate(man).value("bmi")!!, 0.0)
        assertEquals(1, both.warnings.size)
        assertTrue(both.warnings.single().message.contains("'one'"))
    }

    @Test
    fun hiddenStaysComputed() {
        val hiding = model(set("quiet", """"hidden": ["bmi", "ffm", "nothing"]"""))
        assertEquals(setOf("bmi", "ffm", "nothing"), hiding.hidden)
        assertEquals(24.69, hiding.evaluate(man).value("bmi")!!, 0.01)
        assertTrue(hiding.warnings.single().message.contains("nothing"))
    }

    @Test
    fun customInputs() {
        val wrist = set(
            "wrist",
            """
            "inputs": [{"id": "wrist", "name": {"en": "Wrist"}, "unit": "cm"}],
            "indicators": [{"id": "frame", "name": {"en": "Frame"}, "formula": "height / wrist"}]
            """,
        )
        val results = model(wrist).evaluate(man + ("wrist" to 18.0))
        assertEquals(10.0, results.value("frame")!!, 1e-9)
    }

    @Test
    fun malformedFiles() {
        assertTrue(assertThrows(FormulaSetException::class.java) { FormulaSetFile.parse(set("x", """"indicatorz": []""")) }.message!!.contains("indicatorz"))
        assertTrue(assertThrows(FormulaSetException::class.java) { FormulaSetFile.parse("""{"format": 2, "id": "x", "name": {"en": "x"}}""") }.message!!.contains("format 2"))
        assertThrows(FormulaSetException::class.java) { FormulaSetFile.parse("{") }
        assertThrows(FormulaSetException::class.java) {
            FormulaSetFile.parse(set("x", """"indicators": [{"id": "a", "name": {"en": "a"}, "formula": "1", "unit": "stone"}]"""))
        }
    }

    @Test
    fun badIdsAreReportedAtImport() {
        val found = issues(
            set(
                "ids",
                """
                "inputs": [{"id": "запястье", "name": {"en": "Wrist"}}],
                "indicators": [
                  {"id": "sqrt", "name": {"en": "x"}, "formula": "1"},
                  {"id": "ok", "group": "2bad", "name": {"en": "x"}, "formula": "1"}
                ]
                """,
            ),
        )
        assertTrue(found.any { it.startsWith("ids: inputs[0].id") })
        assertTrue(found.any { it.startsWith("ids: indicators[0].id") && it.contains("reserved") })
        assertTrue(found.any { it.startsWith("ids: indicators[1].group") })
    }

    @Test
    fun formulaErrorsNameThePlace() {
        val found = model(
            set(
                "bad",
                """
                "indicators": [
                  {"id": "a", "name": {"en": "a"}, "formula": "weight * "},
                  {"id": "b", "name": {"en": "b"}, "formula": "weight * wieght"},
                  {"id": "c", "name": {"en": "c"}, "formula": "weight > 1"},
                  {"id": "d", "name": {"en": "d"}, "formula": "1", "when": "weight"}
                ]
                """,
            ),
        ).issues
        assertEquals(FormulaIssue("bad", "indicators[0].formula", "Formula ends too early", 9), found[0])
        assertEquals(FormulaIssue("bad", "indicators[1].formula", "Unknown name 'wieght'", 9), found[1])
        assertEquals("indicators[2].formula", found[2].path)
        assertEquals("indicators[3].when", found[3].path)
        assertFalse(model(set("bad", """"indicators": [{"id": "a", "name": {"en": "a"}, "formula": "1 +"}]""")).isValid)
    }

    @Test
    fun loopsAreFoundAlsoThroughGroups() {
        val direct = issues(
            set(
                "loop",
                """
                "indicators": [
                  {"id": "x", "name": {"en": "x"}, "formula": "y + 1"},
                  {"id": "y", "name": {"en": "y"}, "formula": "x + 1"}
                ]
                """,
            ),
        )
        assertTrue(direct.single().contains("x → y → x"))
        // ffm_boer refers to bf, and bf to the group ffm, which may be ffm_boer
        val throughGroup = issues(
            set("loop", """"patches": [], "indicators": [{"id": "ffm_boer", "group": "ffm", "name": {"en": "x"}, "formula": "bf"}]"""),
        )
        assertTrue(throughGroup.single().contains("loop"))
    }

    @Test
    fun structuralMistakes() {
        val found = issues(
            set(
                "shape",
                """
                "indicators": [
                  {"id": "z", "name": {"en": "z"}, "formula": "1", "precision": 7,
                   "zones": [{"bounds": [2, 1], "levels": ["low", "normal"]}]},
                  {"id": "z", "name": {"en": "z"}, "formula": "1"},
                  {"id": "waist", "name": {"en": "w"}, "formula": "1"},
                  {"id": "bmi_alt", "group": "bmi", "name": {"en": "b"}, "formula": "1"},
                  {"id": "nameless", "name": {"ru": "без имени"}, "formula": "1"}
                ],
                "patches": [{"id": "missing", "precision": 1}]
                """,
            ),
        )
        listOf(
            "indicators[0].precision", "indicators[0].zones[0].levels", "indicators[0].zones[0].bounds",
            "indicators[1].id", "indicators[2].id", "indicators[3].group", "indicators[4].name", "patches[0].id",
        ).forEach { path -> assertTrue("$path in $found", found.any { it.contains(": $path: ") }) }
    }

    @Test
    fun unusedParameterIsAWarning() {
        val extra = model(set("extra", """"patches": [{"id": "ffm_wu", "params": {"zz": 1.0}}]"""))
        assertTrue(extra.isValid)
        assertTrue(extra.warnings.single().path.endsWith("params.zz"))
    }

    @Test
    fun boundCanBelongToTheZoneBelow() {
        val strict = model(
            set(
                "strict",
                """"patches": [{"id": "bmi", "zones": [{"bounds": [25], "levels": ["normal", "elevated"], "atBound": "below"}]}]""",
            ),
        )
        // 81 kg at 180 cm is a BMI of exactly 25.0
        assertEquals(ZoneLevel.NORMAL, strict.evaluate(man + ("weight" to 81.0)).zone("bmi")!!.level)
        assertEquals(ZoneLevel.ELEVATED, model().evaluate(man + ("weight" to 81.0)).zone("bmi")!!.level)
    }

    @Test
    fun groupNamesAndTheirFallback() {
        // The base set names no group: the variant's own name is used
        assertEquals("Lean body mass (Boer 1984)", model().groupName("ffm", "ffm_boer").text("en"))
        val named = model(
            set(
                "names",
                """"groups": [{"id": "ffm", "name": {"en": "Fat-free mass"}}, {"id": "nothing", "name": {"en": "x"}}, {"id": "bad", "name": {"ru": "x"}}]""",
            ),
        )
        assertEquals("Fat-free mass", named.groupName("ffm", "ffm_boer").text("en"))
        assertTrue(named.warnings.any { it.message.contains("'nothing'") })
        assertTrue(named.issues.any { it.path == "groups[2].name" })
    }

    @Test
    fun parameterUsedOnlyInZonesIsUsed() {
        val zoned = model(
            set(
                "z",
                """"indicators": [{"id": "q", "name": {"en": "q"}, "formula": "1", "params": {"limit": 40},
                  "zones": [{"when": "age < limit", "bounds": [2], "levels": ["normal", "high"]}]}]""",
            ),
        )
        assertTrue(zoned.isValid)
        assertTrue(zoned.warnings.isEmpty())
    }

    @Test
    fun newerFormatIsReportedAsSuch() {
        val newer = """{"format": 2, "id": "x", "name": {"en": "x"}, "futureKey": true}"""
        assertTrue(assertThrows(FormulaSetException::class.java) { FormulaSetFile.parse(newer) }.message!!.contains("format 2"))
        assertThrows(FormulaSetException::class.java) { FormulaSetFile.parse("[]") }
        assertThrows(FormulaSetException::class.java) { FormulaSetFile.parse("""{"id": "x"}""") }
    }

    @Test
    fun everyTextNeedsEnglish() {
        val found = issues(
            """{"format": 1, "id": "ru", "name": {"ru": "Мой"}, "description": {"ru": "Описание"},
               "indicators": [{"id": "q", "name": {"en": "q"}, "description": {"ru": "x"}, "formula": "1"}],
               "patches": [{"id": "bmi", "description": {"ru": "x"}}]}""",
        )
        listOf("ru: name:", "ru: description:", "ru: indicators[0].description:", "ru: patches[0].description:")
            .forEach { prefix -> assertTrue("$prefix in $found", found.any { it.startsWith(prefix) }) }
        // Shown without crashing even if such a text got through
        assertEquals("Мой", mapOf("ru" to "Мой").text("de"))
    }

    @Test
    fun zoneUsesTheValueAsShown() {
        // 77.98 kg at 176.75 cm is a BMI of 24.96, shown as 25.0 with one decimal
        val results = model().evaluate(man + mapOf("weight" to 77.98, "height" to 176.75))
        assertEquals(24.96, results.value("bmi")!!, 0.005)
        assertEquals(ZoneLevel.ELEVATED, results.zone("bmi")!!.level)
    }

    @Test
    fun parameterCannotHideAnInputAndWarnsAboutAnIndicator() {
        val shadow = model(
            set(
                "shadow",
                """"indicators": [{"id": "q", "name": {"en": "q"}, "formula": "weight + bmi", "params": {"weight": 1, "bmi": 2}}]""",
            ),
        )
        assertTrue(shadow.issues.single().toString().startsWith("shadow: indicators[0].params.weight"))
        assertTrue(shadow.warnings.single().toString().startsWith("shadow: indicators[0].params.bmi"))
        // A set above adding an indicator named like a built-in parameter does not break the built-in formula
        val above = model(set("above", """"indicators": [{"id": "a", "name": {"en": "a"}, "formula": "1"}]"""))
        assertTrue(above.isValid)
        assertEquals(model().evaluate(man).value("ffm_wu")!!, above.evaluate(man).value("ffm_wu")!!, 0.0)
    }

    @Test
    fun brokenSetIsLeftOutAndTheRestWorks() {
        val broken = set("broken", """"indicators": [{"id": "q", "name": {"en": "q"}, "formula": "1 +"}]""")
        val good = set("good", """"indicators": [{"id": "r", "name": {"en": "r"}, "formula": "bmi * 2"}]""")
        val usable = FormulaModel.buildUsable(
            listOf(FormulaLayer(FormulaSetFile.parse(base), builtIn = true), FormulaLayer(FormulaSetFile.parse(broken)), FormulaLayer(FormulaSetFile.parse(good))),
        )
        assertTrue(usable.model.isValid)
        assertEquals(setOf("broken"), usable.rejected.keys)
        assertEquals(24.69 * 2, usable.model.evaluate(man).value("r")!!, 0.02)
        assertNull(usable.model.indicators["q"])
    }

    @Test
    fun errorsAreBlamedOnTheSetThatWroteThem() {
        val bad = set("low", """"indicators": [{"id": "q", "name": {"en": "q"}, "formula": "1 +"}]""")
        val patch = set("high", """"patches": [{"id": "q", "precision": 2, "when": "weight >"}]""")
        val found = model(bad, patch).issues
        assertTrue(found.any { it.set == "low" && it.path == "indicators[0].formula" })
        assertTrue(found.any { it.set == "high" && it.path == "patches[0].when" })
    }

    @Test
    fun conflictsBetweenUserSetsAreWarnings() {
        val one = set(
            "one",
            """"inputs": [{"id": "wrist", "name": {"en": "Wrist"}, "unit": "cm"}], "patches": [{"id": "bmi", "precision": 2}]""",
        )
        val two = set(
            "two",
            """"inputs": [{"id": "wrist", "name": {"en": "Wrist"}, "unit": "kg"}], "patches": [{"id": "bmi", "precision": 0}]""",
        )
        val both = model(one, two)
        assertTrue(both.isValid)
        assertTrue(both.warnings.any { it.set == "two" && it.path == "inputs[0].id" && it.message.contains("another unit") })
        assertTrue(both.warnings.any { it.set == "two" && it.path == "patches[0].id" && it.message.contains("'one'") })
    }

    @Test
    fun setIdsAndInputUnits() {
        val found = issues(
            set("base", """"inputs": [{"id": "wrist", "name": {"en": "Wrist"}, "unit": "kcal"}]"""),
            """{"format": 1, "id": "my set", "name": {"en": "x"}}""",
        )
        assertTrue(found.any { it.startsWith("base: id:") && it.contains("Another") })
        assertTrue(found.any { it.startsWith("base: inputs[0].unit:") })
        assertTrue(found.any { it.startsWith("my set: id:") })
    }
}
