package dev.kinash.smartscales.ui.profile

import dev.kinash.smartscales.core.formula.BuiltinFormulas
import dev.kinash.smartscales.core.formula.FormulaModel
import dev.kinash.smartscales.core.model.Sex
import dev.kinash.smartscales.ui.history.DEFAULT_CARDS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VariantGroupsTest {
    private val groups = ProfileEditViewModel.variantGroups(FormulaModel.build(listOf(BuiltinFormulas.layer)), DEFAULT_CARDS)

    private fun group(id: String) = groups.single { it.id == id }

    @Test
    fun onlyGroupsThatChangeTheScreens() {
        // Lean body mass is on no card; fat-free mass by impedance feeds body fat
        assertEquals(listOf("bf", "ffm", "tbw", "bmr"), groups.map { it.id })
    }

    @Test
    fun equationsForWomenAreNotOfferedToMen() {
        assertEquals(mapOf("ffm_bell" to Sex.FEMALE), group("ffm").sexOnly)
        assertEquals(listOf("ffm_wu", "ffm_sun"), group("ffm").options(Sex.MALE))
        assertEquals(listOf("ffm_wu", "ffm_sun", "ffm_bell"), group("ffm").options(Sex.FEMALE))
        assertEquals(listOf("tbw_sun", "tbw_watson"), group("tbw").options(Sex.MALE))
        assertFalse(group("tbw").allows("tbw_bell", Sex.MALE))
        assertTrue(group("tbw").allows("tbw_bell", Sex.FEMALE))
        // Nothing else depends on sex this way
        assertTrue(group("bf").sexOnly.isEmpty())
    }

    @Test
    fun equationsByImpedanceAreMarkedWhereTheGroupHasOthers() {
        assertEquals(setOf("bf_bia"), group("bf").needsImpedance)
        assertEquals(setOf("bmr_katch", "bmr_cunningham"), group("bmr").needsImpedance)
        assertTrue(group("bf").marksImpedance)
        assertEquals(setOf("tbw_sun", "tbw_bell"), group("tbw").needsImpedance)
        assertTrue(group("tbw").marksImpedance)
        // Every equation of fat-free mass by impedance needs it: nothing to tell apart
        assertFalse(group("ffm").marksImpedance)
    }

    @Test
    fun unknownVariantIsShownByItsIdInsteadOfFailing() {
        assertFalse(group("bf").allows("bf_gone", Sex.MALE))
        assertEquals("bf_gone", group("bf").method("bf_gone", "en"))
        assertEquals("Gallagher 2000, from BMI", group("bf").method("bf_gallagher", "en"))
    }
}
