package dev.kinash.smartscales.core.formula

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The built-in set against values computed independently from the tables of FORMULAS_REVIEW §2 for its two
 * example people: a man of 180 cm, 80 kg, 35 years, 450 Ω and a woman of 165 cm, 60 kg, 35 years, 550 Ω,
 * with circumferences added for the waist-based indicators.
 */
class BuiltinFormulasTest {
    private val model = FormulaModel.build(listOf(BuiltinFormulas.layer))

    private val man = mapOf<String, Any?>(
        "weight" to 80.0, "height" to 180.0, "age" to 35.0, "sex" to 1.0, "male" to true, "female" to false,
        "impedance" to 450.0, "waist" to 90.0, "hips" to 100.0, "neck" to 40.0,
    )
    private val woman = mapOf<String, Any?>(
        "weight" to 60.0, "height" to 165.0, "age" to 35.0, "sex" to 0.0, "male" to false, "female" to true,
        "impedance" to 550.0, "waist" to 75.0, "hips" to 98.0, "neck" to 33.0,
    )

    private fun assertValues(person: Map<String, Any?>, expected: Map<String, Double>) {
        val results = model.evaluate(person)
        for ((id, value) in expected) {
            val actual = results.value(id) ?: error("$id has no value")
            // Within the last printed digit of the reference
            assertEquals(id, value, actual, 0.00051 * maxOf(1.0, kotlin.math.abs(value)))
        }
    }

    @Test
    fun setIsValidWithoutWarnings() {
        assertTrue(model.issues.toString(), model.isValid)
        assertTrue(model.warnings.toString(), model.warnings.isEmpty())
    }

    @Test
    fun everyTextIsInEnglishAndRussian() {
        val file = BuiltinFormulas.file
        val texts = listOf(file.name) + listOfNotNull(file.description) +
            file.indicators.flatMap { listOf(it.name) + listOfNotNull(it.description) + it.zones.flatMap { zone -> zone.labels.orEmpty() } }
        texts.forEach { assertTrue(it.toString(), it.keys.containsAll(listOf("en", "ru"))) }
    }

    @Test
    fun noTwoShownIndicatorsShareAName() {
        // The impedance methods for fat-free mass are hidden: the screens show the one that adds up with fat mass
        assertEquals(setOf("ffm"), model.hidden)
        val shown = BuiltinFormulas.file.indicators.filter { it.id !in model.hidden && it.group !in model.hidden }
        for (language in listOf("en", "ru")) {
            val names = shown.map { it.name.text(language) }
            assertEquals(language, names.size, names.toSet().size)
        }
        // Everywhere they are listed, the methods say what they are
        BuiltinFormulas.file.indicators.filter { it.group == "ffm" }.forEach {
            assertTrue(it.id, it.name.text("en").contains("by impedance") && it.name.text("ru").contains("по импедансу"))
        }
    }

    @Test
    fun everyGroupHasAName() {
        assertEquals(model.groups.keys, model.groupNames.keys)
        model.groupNames.values.forEach { assertTrue(it.toString(), it.keys.containsAll(listOf("en", "ru"))) }
        assertEquals("Body fat", model.groupName("bf", "bf_gallagher").text("en"))
    }

    @Test
    fun everyIndicatorNamesItsSource() {
        BuiltinFormulas.file.indicators.forEach { assertTrue(it.id, !it.source?.text.isNullOrBlank()) }
    }

    @Test
    fun manMatchesTheReview() = assertValues(
        man,
        mapOf(
            "bmi" to 24.6914, "ffm_wu" to 61.1080, "bf_bia" to 23.6150, "ffm_sun" to 65.9200,
            "lbm_boer" to 61.4200, "lbm_hume" to 57.7866, "bf_gallagher" to 19.8505, "bf_deurenberg" to 21.4796,
            "bf_navy" to 18.3675, "tbw_sun" to 48.0000, "tbw_percent" to 59.7000, "smm_janssen" to 35.3140,
            "smi" to 10.8994, "ffmi" to 18.8605, "fmi" to 5.8309, "fat_mass" to 18.892, "fat_free_mass" to 61.1080, "bmr_katch" to 1689.9328,
            "bmr_cunningham" to 1844.3760, "bmr_mifflin" to 1755.0, "whtr" to 0.5, "whr" to 0.9,
            "ideal_weight_min" to 59.94, "ideal_weight_max" to 80.676, "waist_circumference" to 90.0,
        ),
    )

    @Test
    fun womanMatchesTheReview() = assertValues(
        woman,
        mapOf(
            "bmi" to 22.0386, "ffm_wu" to 40.0380, "bf_bia" to 33.2700, "ffm_sun" to 45.8250, "ffm_bell" to 44.6311,
            "lbm_boer" to 44.8650, "lbm_hume" to 43.4395, "bf_gallagher" to 28.7870, "bf_deurenberg" to 29.0963,
            "bf_navy" to 28.4350, "tbw_sun" to 32.6250, "tbw_bell" to 32.6700, "tbw_percent" to 54.1031,
            "smm_janssen" to 22.4665, "smi" to 8.2522, "ffmi" to 14.7063, "fmi" to 7.3322, "bmr_katch" to 1234.8208,
            "bmr_cunningham" to 1380.8360, "bmr_mifflin" to 1295.25, "whtr" to 0.4545, "whr" to 0.7653,
        ),
    )

    @Test
    fun defaultsWithImpedanceFollowTheReview() {
        // Wu 2015 gives 23.6 % and 33.3 % fat for the review's two people
        assertEquals(23.6, model.evaluate(man).group("bf")!!.second, 0.05)
        assertEquals(33.3, model.evaluate(woman).group("bf")!!.second, 0.05)
        val results = model.evaluate(man)
        assertEquals("ffm_wu", results.group("ffm")!!.first)
        assertEquals("bf_bia", results.group("bf")!!.first)
        assertEquals("tbw_sun", results.group("tbw")!!.first)
        assertEquals("bmr_katch", results.group("bmr")!!.first)
    }

    @Test
    fun withoutImpedanceTheEquationsByWeightAndHeightTakeOver() {
        val results = model.evaluate(man + ("impedance" to null))
        assertNull(results.group("ffm"))
        assertEquals("bf_gallagher", results.group("bf")!!.first)
        assertEquals("bmr_mifflin", results.group("bmr")!!.first)
        assertNull(results.group("tbw"))
        assertNull(results.value("smm_janssen"))
        assertEquals(80 * 19.8505 / 100, results.value("fat_mass")!!, 0.001)
        // Fat-free mass follows the fat estimate, so the two add up to the weight
        assertEquals(80 - 80 * 19.8505 / 100, results.value("fat_free_mass")!!, 0.001)
        // Lean body mass by Boer stays a separate estimate
        assertEquals("lbm_boer", results.group("lbm")!!.first)
    }

    @Test
    fun fatAndFatFreeMassAddUpToTheWeightWhateverTheChoice() {
        val people = listOf(man, man + ("impedance" to null), woman, woman + ("impedance" to null))
        val choices = listOf(emptyMap(), mapOf("bf" to "bf_navy"), mapOf("bf" to "bf_deurenberg"), mapOf("ffm" to "ffm_sun"))
        for (person in people) {
            for (choice in choices) {
                val results = model.evaluate(person, primary = choice)
                val weight = person.getValue("weight") as Double
                assertEquals("$choice", weight, results.value("fat_mass")!! + results.value("fat_free_mass")!!, 1e-9)
                assertEquals("$choice", results.value("bmi")!!, results.value("ffmi")!! + results.value("fmi")!!, 1e-9)
            }
        }
    }

    @Test
    fun womenOnlyEquationsDoNotApplyToMen() {
        val results = model.evaluate(man)
        assertNull(results.value("ffm_bell"))
        assertNull(results.value("tbw_bell"))
    }

    @Test
    fun theSameEquationsContinueBeyondTheStudiedAges() {
        // Wu 2015 studied ages up to 75 and Janssen 2000 up to 86; the history must not switch equations there
        val results = model.evaluate(man + ("age" to 90.0))
        assertEquals("ffm_wu", results.group("ffm")!!.first)
        assertEquals("bf_bia", results.group("bf")!!.first)
        assertTrue(results.value("smm_janssen") != null)
    }

    @Test
    fun childrenGetBmiButNoBodyComposition() {
        val child = man + mapOf("age" to 12.0, "weight" to 40.0, "height" to 150.0)
        val results = model.evaluate(child)
        assertEquals(40 / 1.5 / 1.5, results.value("bmi")!!, 1e-9)
        // Adult BMI classes do not apply to children
        assertNull(results.zone("bmi"))
        listOf("ffm", "bf", "tbw", "bmr", "lbm").forEach { assertNull(it, results.group(it)) }
        assertNull(results.value("fat_mass"))
        assertNull(results.value("fat_free_mass"))
        assertNull(results.value("smm_janssen"))
    }

    @Test
    fun adultZones() {
        val results = model.evaluate(man)
        assertEquals(ZoneLevel.NORMAL, results.zone("bmi")!!.level)
        assertEquals("Normal", results.zone("bmi")!!.label!!.text("en"))
        assertEquals(ZoneLevel.NORMAL, results.zone("waist_circumference")!!.level)
        // WHtR 0.50 and WHR 0.90 are at the bound, so in the zone above
        assertEquals(ZoneLevel.ELEVATED, results.zone("whtr")!!.level)
        assertEquals(ZoneLevel.ELEVATED, results.zone("whr")!!.level)
        // BMI 37.0
        val heavy = model.evaluate(man + ("weight" to 120.0))
        assertEquals("Obesity class II", heavy.zone("bmi")!!.label!!.text("en"))
        assertEquals("Ожирение II степени", heavy.zone("bmi")!!.label!!.text("ru"))
        assertEquals(ZoneLevel.VERY_HIGH, heavy.zone("bmi")!!.level)
    }

    @Test
    fun waistBoundsAreStrictAsWhoWritesThem() {
        // WHO 2008, Table A1: increased risk above 94 cm, substantially increased above 102 cm (men)
        assertEquals(ZoneLevel.NORMAL, model.evaluate(man + ("waist" to 94.0)).zone("waist_circumference")!!.level)
        assertEquals(ZoneLevel.ELEVATED, model.evaluate(man + ("waist" to 94.1)).zone("waist_circumference")!!.level)
        assertEquals(ZoneLevel.ELEVATED, model.evaluate(man + ("waist" to 102.0)).zone("waist_circumference")!!.level)
        assertEquals(ZoneLevel.HIGH, model.evaluate(man + ("waist" to 102.1)).zone("waist_circumference")!!.level)
        assertEquals(ZoneLevel.NORMAL, model.evaluate(woman + ("waist" to 80.0)).zone("waist_circumference")!!.level)
        assertEquals(ZoneLevel.ELEVATED, model.evaluate(woman + ("waist" to 80.1)).zone("waist_circumference")!!.level)
        // Rounded to the shown precision first: 80.04 is shown as 80.0 and is still normal
        assertEquals(ZoneLevel.NORMAL, model.evaluate(woman + ("waist" to 80.04)).zone("waist_circumference")!!.level)
        // WHR keeps "≥": 0.90 is already elevated for men
        assertEquals(ZoneLevel.ELEVATED, model.evaluate(man).zone("whr")!!.level)
    }

    @Test
    fun navyNeedsHipsForWomenOnly() {
        assertNull(model.evaluate(woman + ("hips" to null)).value("bf_navy"))
        assertEquals(18.3675, model.evaluate(man + ("hips" to null)).value("bf_navy")!!, 0.001)
    }

    @Test
    fun bodyFatZonesFollowGallagherTable4() {
        // The review's man has 23.6 % fat at 35: between 20 and 25 % for men aged 20–39
        assertEquals(ZoneLevel.ELEVATED, model.evaluate(man).zone("bf_bia")!!.level)
        // The review's woman has 33.3 %: from 33 % for women aged 20–39
        assertEquals(ZoneLevel.ELEVATED, model.evaluate(woman).zone("bf_bia")!!.level)
        // Age enters Wu 2015 too, so the fat itself changes with age; each case names its value
        fun case(age: Double, fat: Double, level: ZoneLevel) {
            val results = model.evaluate(man + ("age" to age))
            assertEquals("fat at $age", fat, results.value("bf_bia")!!, 0.05)
            assertEquals("zone at $age", level, results.zone("bf_bia")!!.level)
        }
        // 20–39 ranges also for 18 and 19 year olds: 8 / 20 / 25 %
        case(18.0, 20.7, ZoneLevel.ELEVATED)
        // 40–59: 11 / 22 / 28 %
        case(45.0, 25.3, ZoneLevel.ELEVATED)
        // 60–79: 13 / 25 / 30 %
        case(65.0, 28.7, ZoneLevel.ELEVATED)
        // 80 and older use the 60–79 ranges
        case(85.0, 32.1, ZoneLevel.HIGH)
        // A woman of 45 without impedance: 29.6 % by Gallagher is normal for 40–59 (23 / 34 / 40 %)
        val woman45 = model.evaluate(woman + mapOf("age" to 45.0, "impedance" to null))
        assertEquals("bf_gallagher", woman45.group("bf")!!.first)
        assertEquals(29.6, woman45.value("bf_gallagher")!!, 0.05)
        assertEquals(ZoneLevel.NORMAL, woman45.zone("bf_gallagher")!!.level)
    }

    @Test
    fun everyBodyFatVariantHasTheSameZones() {
        val variants = BuiltinFormulas.file.indicators.filter { it.group == "bf" }
        assertEquals(listOf("bf_bia", "bf_gallagher", "bf_deurenberg", "bf_navy"), variants.map { it.id })
        variants.forEach { assertEquals(it.id, variants.first().zones, it.zones) }
        assertTrue(variants.none { it.approximateZones })
    }
}
