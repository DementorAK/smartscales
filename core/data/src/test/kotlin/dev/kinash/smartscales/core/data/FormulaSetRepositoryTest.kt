package dev.kinash.smartscales.core.data

import dev.kinash.smartscales.core.data.repository.FormulaSetRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FormulaSetRepositoryTest : DatabaseTestBase() {
    private val sets by lazy { FormulaSetRepository(database, clock) }

    private suspend fun names() = sets.observeAll().first().map { it.name }

    @Test
    fun newSetsGoOnTopAndMoveOneLayerAtATime() = runTest {
        val a = sets.add("A", "{}", 1)
        val b = sets.add("B", "{}", 1)
        val c = sets.add("C", "{}", 1)
        assertEquals(listOf("A", "B", "C"), names())

        sets.move(a, up = true)
        assertEquals(listOf("B", "A", "C"), names())
        sets.move(c, up = true)
        assertEquals(listOf("B", "A", "C"), names())
        sets.move(b, up = false)
        assertEquals(listOf("B", "A", "C"), names())
        sets.move(c, up = false)
        assertEquals(listOf("B", "C", "A"), names())

        sets.delete(c)
        assertEquals(listOf("B", "A"), names())
        // After a deletion the next one still goes on top
        sets.add("D", "{}", 1)
        assertEquals(listOf("B", "A", "D"), names())
    }

    @Test
    fun replacingKeepsThePlaceAndTheSwitch() = runTest {
        val a = sets.add("A", "{\"v\":1}", 1)
        sets.add("B", "{}", 1)
        sets.setEnabled(a, false)
        sets.replace(a, "A2", "{\"v\":2}", 1)
        val stored = sets.get(a)!!
        assertEquals("A2", stored.name)
        assertEquals("{\"v\":2}", stored.json)
        assertFalse(stored.enabled)
        assertEquals(listOf("A2", "B"), names())
    }
}
