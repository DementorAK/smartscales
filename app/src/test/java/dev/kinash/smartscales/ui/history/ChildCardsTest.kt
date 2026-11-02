package dev.kinash.smartscales.ui.history

import dev.kinash.smartscales.core.data.Measurement
import dev.kinash.smartscales.core.formula.BuiltinFormulas
import dev.kinash.smartscales.core.formula.ChildGrowth
import dev.kinash.smartscales.core.formula.FormulaLayer
import dev.kinash.smartscales.core.formula.FormulaModel
import dev.kinash.smartscales.core.formula.FormulaSetFile
import dev.kinash.smartscales.core.formula.MeasurementInputs
import dev.kinash.smartscales.core.model.MeasurementSource
import dev.kinash.smartscales.core.model.PersonSnapshot
import dev.kinash.smartscales.core.model.Sex
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class ChildCardsTest {
    private val child = Measurement(
        profileId = 1,
        takenAt = Instant.parse("2025-11-02T08:00:00Z"),
        zone = ZoneOffset.UTC,
        source = MeasurementSource.MANUAL,
        weightKg = 35.0,
        person = PersonSnapshot(140.0, Sex.MALE, LocalDate.parse("2015-11-02")),
    )

    private fun cards(model: FormulaModel): List<String> {
        val raw = child.toRawMeasurement()!!
        val results = model.evaluate(MeasurementInputs.of(raw))
        val growth = ChildGrowth.of(Sex.MALE, raw.ageMonths, raw.weightKg, raw.person.heightCm, results.value("bmi"), 1)!!
        return childCards(model, results, growth, child).map { it.id }
    }

    @Test
    fun childGetsBmiWeightAndHeight() {
        assertEquals(listOf("bmi", CHILD_WEIGHT_CARD, CHILD_HEIGHT_CARD), cards(FormulaModel.build(listOf(BuiltinFormulas.layer))))
    }

    @Test
    fun hiddenBmiIsHiddenForChildrenToo() {
        val hiding = FormulaSetFile.parse("""{"format": 1, "id": "quiet", "name": {"en": "Quiet"}, "hidden": ["bmi"]}""")
        val model = FormulaModel.build(listOf(BuiltinFormulas.layer, FormulaLayer(hiding)))
        assertEquals(listOf(CHILD_WEIGHT_CARD, CHILD_HEIGHT_CARD), cards(model))
    }
}
