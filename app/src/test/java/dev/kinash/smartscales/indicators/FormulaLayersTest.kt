package dev.kinash.smartscales.indicators

import dev.kinash.smartscales.core.data.FormulaSet
import dev.kinash.smartscales.core.formula.FormulaSetFile
import dev.kinash.smartscales.core.formula.ProblemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class FormulaLayersTest {
    private fun set(id: String, indicators: String = DOUBLE_BMI, patches: String = "") =
        """{"format": 1, "id": "$id", "name": {"en": "Set $id"}, "indicators": [$indicators], "patches": [$patches]}"""

    private fun stored(json: String, id: Long, enabled: Boolean = true) =
        FormulaSet(id = id, name = "Stored $id", json = json, formatVersion = 1, enabled = enabled, position = id.toInt(), addedAt = Instant.EPOCH)

    @Test
    fun enabledSetsJoinTheModelAndBrokenOnesStayOut() {
        val layers = layersOf(
            listOf(
                stored(set("mine"), 1),
                stored(set("off", indicators = TRIPLE_BMI), 2, enabled = false),
                stored("{not json", 3),
                // The same id as the first: the one higher up is left out, not both
                stored(set("mine", indicators = TRIPLE_BMI), 4),
            ),
        )
        assertTrue("double_bmi" in layers.model.indicators)
        assertFalse("triple_bmi" in layers.model.indicators)
        assertTrue("bmi" in layers.model.indicators)
        val (mine, off, broken, twin) = layers.sets
        assertTrue(mine.active)
        assertFalse(off.active)
        assertEquals(emptyList<Any>(), off.errors)
        assertNull(broken.file)
        assertEquals(1, broken.errors.size)
        assertFalse(twin.active)
        assertTrue(twin.errors.any { it.path == "id" })
    }

    @Test
    fun aFileWithErrorsIsNotTakenAndSaysWhere() {
        val check = checkImport(set("mine", indicators = """{"id": "x", "name": {"en": "X"}, "formula": "wieght * 2"}"""), emptyList())
        val errors = (check as ImportCheck.Rejected).errors
        assertEquals("indicators[0].formula", errors.single().path)
        assertEquals(0, errors.single().position)

        assertTrue(checkImport("""{"format": 2}""", emptyList()) is ImportCheck.Rejected)
        // The built-in set's id is taken for good
        assertTrue(checkImport(set("builtin"), emptyList()) is ImportCheck.Rejected)
    }

    @Test
    fun theSameIdReplacesTheStoredSetInItsPlace() {
        val first = stored(set("mine"), 1, enabled = false)
        val above = stored(set("above", indicators = "", patches = """{"id": "double_bmi", "precision": 2}"""), 2)
        val check = checkImport(set("mine", indicators = TRIPLE_BMI + "," + DOUBLE_BMI), listOf(first, above))
        check as ImportCheck.Accepted
        assertEquals(first, check.replaces)
        assertEquals(emptyList<FormulaSet>(), check.breaks)

        // A new version without the indicator the set above patches would leave that set out
        val dropping = checkImport(set("mine", indicators = TRIPLE_BMI), listOf(stored(set("mine"), 1), above))
        assertEquals(listOf(above), (dropping as ImportCheck.Accepted).breaks)

        val added = checkImport(set("other", indicators = TRIPLE_BMI), listOf(first))
        assertNull((added as ImportCheck.Accepted).replaces)

        // The new version's indicator clashes with a group of the set above, which would be left out if it were
        // on; switched off, it stays off, and the set above goes on working
        val grouped = stored(set("grouped", indicators = """{"id": "a", "group": "g2", "name": {"en": "A"}, "formula": "bmi"}"""), 2)
        val clashing = set("mine", indicators = """{"id": "g2", "name": {"en": "G"}, "formula": "bmi"}""")
        assertEquals(listOf(grouped), (checkImport(clashing, listOf(stored(set("mine"), 1), grouped)) as ImportCheck.Accepted).breaks)
        val off = checkImport(clashing, listOf(stored(set("mine"), 1, enabled = false), grouped))
        assertEquals(emptyList<FormulaSet>(), (off as ImportCheck.Accepted).breaks)
    }

    @Test
    fun aClashFoundInASetBelowNamesThatSet() {
        val below = stored(set("below", indicators = """{"id": "a", "group": "fat2", "name": {"en": "A"}, "formula": "bmi"}"""), 1)
        val check = checkImport(set("mine", indicators = """{"id": "fat2", "name": {"en": "F"}, "formula": "bmi"}"""), listOf(below))
        check as ImportCheck.Rejected
        assertEquals("mine", check.set)
        assertTrue(check.errors.toString(), check.errors.any { it.set == "below" && it.path == "indicators[0].group" })
    }

    @Test
    fun anEditedSetIsCheckedInItsPlaceAndNeedsAnIdOfItsOwn() {
        val mine = stored(set("mine"), 1, enabled = false)
        val above = stored(set("above", indicators = "", patches = """{"id": "double_bmi", "precision": 2}"""), 2)
        val file = FormulaSetFile.parse(set("mine", indicators = DOUBLE_BMI + "," + TRIPLE_BMI))
        // A set switched off is checked as if on, under the set above, which sees its indicators
        val check = checkEdit(file, mine, listOf(mine, above))
        assertEquals(emptyList<Any>(), check.errors)
        assertTrue("triple_bmi" in check.model!!.indicators)
        assertFalse("double_bmi" in check.below.indicators)
        // The stored text changed since the editor opened: the place is found by the set's id in the database
        assertEquals(emptyList<Any>(), checkEdit(file, mine.copy(json = "{}"), listOf(mine, above)).errors)

        // Dropping what the set above patches would leave it out
        val dropping = checkEdit(FormulaSetFile.parse(set("mine", indicators = TRIPLE_BMI)), mine.copy(enabled = true), listOf(mine.copy(enabled = true), above))
        assertEquals(listOf(above), dropping.breaks)

        // A new set goes on top and may not take another set's id, though that one is off, nor the built-in one's
        val taken = checkEdit(FormulaSetFile.parse(set("mine", indicators = TRIPLE_BMI)), null, listOf(mine, above))
        assertEquals(listOf("id"), taken.errors.map { it.path })
        assertNull(taken.model)
        assertEquals(ProblemKind.SET_ID_USED, taken.errors.single().problem.kind)
        assertEquals(ProblemKind.BUILTIN_ID, checkEdit(FormulaSetFile.parse(set("builtin")), null, emptyList()).errors.first().problem.kind)
        val new = checkEdit(FormulaSetFile.parse(set("new", indicators = "", patches = """{"id": "bmi", "precision": 3}""")), null, listOf(mine, above))
        assertEquals(emptyList<Any>(), new.errors.map { it.toString() })
    }

    private companion object {
        const val DOUBLE_BMI = """{"id": "double_bmi", "name": {"en": "Double BMI"}, "formula": "bmi * 2"}"""
        const val TRIPLE_BMI = """{"id": "triple_bmi", "name": {"en": "Triple BMI"}, "formula": "bmi * 3"}"""
    }
}
