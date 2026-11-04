package dev.kinash.smartscales.ui.history

import dev.kinash.smartscales.core.formula.BuiltinFormulas
import dev.kinash.smartscales.core.formula.FormulaModel
import dev.kinash.smartscales.ui.profile.ProfileEditViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CardChoicesTest {
    private val model = FormulaModel.build(listOf(BuiltinFormulas.layer))
    private val choices = cardChoices(model)
    private val ids = choices.map { it.id }

    @Test
    fun everyDefaultCardCanBeChosen() {
        assertTrue(ids.containsAll(DEFAULT_CARDS))
    }

    @Test
    fun aGroupComesBeforeItsVariantsAndEachIsOfferedOnce() {
        assertEquals(ids.distinct(), ids)
        assertTrue(choices.single { it.id == "bf" }.isGroup)
        assertTrue(!choices.single { it.id == "bf_gallagher" }.isGroup)
        model.groups.forEach { (group, variants) ->
            variants.forEach { assertTrue("$group before $it", ids.indexOf(group) < ids.indexOf(it)) }
        }
    }

    @Test
    fun cardsKeepTheProfilesOrderAndSkipIdsNoSetHas() {
        val inputs = mapOf<String, Any?>(
            "weight" to 80.0, "height" to 180.0, "age" to 35.0, "sex" to 1.0, "male" to true, "female" to false,
        )
        val results = model.evaluate(inputs)
        val cards = indicatorCards(model, results, cards = listOf("bf_deurenberg", "gone", "bmi"))
        assertEquals(listOf("bf_deurenberg", "bmi"), cards.map { it.id })
    }

    @Test
    fun aCardMovesPastCardsThatAreNotShownAndTheyKeepTheirPlaces() {
        // whtr is hidden by a set: it stays in the list, unseen, where it was
        val cards = listOf("bmi", "whtr", "bf", "bmr")
        val visible = setOf("bmi", "bf", "bmr")
        assertEquals(listOf("bf", "whtr", "bmi", "bmr"), ProfileEditViewModel.moveAmongVisible(cards, visible, "bf", -1))
        assertEquals(listOf("bmi", "whtr", "bmr", "bf"), ProfileEditViewModel.moveAmongVisible(cards, visible, "bf", 1))
        // At the ends, and for a card that is not shown, nothing changes
        assertEquals(cards, ProfileEditViewModel.moveAmongVisible(cards, visible, "bmi", -1))
        assertEquals(cards, ProfileEditViewModel.moveAmongVisible(cards, visible, "bmr", 1))
        assertEquals(cards, ProfileEditViewModel.moveAmongVisible(cards, visible, "whtr", 1))
    }
}
