package dev.kinash.smartscales.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dev.kinash.smartscales.MainActivity
import dev.kinash.smartscales.core.data.Measurement
import dev.kinash.smartscales.core.data.Profile
import dev.kinash.smartscales.core.data.repository.FormulaSetRepository
import dev.kinash.smartscales.core.data.repository.MeasurementRepository
import dev.kinash.smartscales.core.data.repository.ProfileRepository
import dev.kinash.smartscales.core.formula.FormulaSetFile
import dev.kinash.smartscales.core.model.MeasurementSource
import dev.kinash.smartscales.core.model.Sex
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import javax.inject.Inject

/**
 * The set editor: a set made on the phone is checked as it is typed, tries its formulas on the last weighing and
 * is saved as an ordinary set; a built-in indicator's coefficient is changed by a patch.
 */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
class FormulaSetEditFlowTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    @Inject
    lateinit var profiles: ProfileRepository

    @Inject
    lateinit var measurements: MeasurementRepository

    @Inject
    lateinit var sets: FormulaSetRepository

    @Before
    fun setUp() {
        hilt.inject()
        runBlocking {
            val id = profiles.add(
                Profile(
                    name = "Dmitry", sex = Sex.MALE, birthDate = LocalDate.parse("1990-11-02"), heightCm = 180.0,
                    color = 0xFF3F51B5.toInt(), createdAt = Instant.parse("2026-01-01T00:00:00Z"),
                ),
            )
            measurements.add(
                Measurement(
                    profileId = id, takenAt = Instant.parse("2025-11-02T08:00:00Z"), zone = ZoneOffset.UTC,
                    source = MeasurementSource.MANUAL, weightKg = 80.0, person = profiles.get(id)!!.snapshot(),
                ),
            )
        }
    }

    @Test
    fun aNewSetIsCheckedAsItIsTypedAndSaved() {
        openSets()
        compose.onNodeWithText("Create").performClick()
        waitFor(hasText("New set"))
        compose.onNodeWithTag("set_name").performTextInput("Mine")
        compose.onNodeWithText("Add an indicator").performScrollTo().performClick()
        waitFor(hasText("Formula"))
        compose.onNodeWithTag("indicator_name").performTextInput("Half weight")
        compose.onNodeWithTag("indicator_formula").performScrollTo().performTextInput("weight / 2 + a")
        waitFor(hasText("Unknown name 'a'", substring = true))
        compose.onNodeWithText("Fix the errors to see the value.").assertExists()

        // The coefficient as a parameter: the formula is tried on the 80 kg weighing
        compose.onNodeWithText("Add a parameter").performScrollTo().performClick()
        // The last field called Name is the parameter's, under the indicator's own
        typeIntoLast("Name", "a")
        typeIntoLast("Value", "1")
        waitFor(hasText("Weighing of", substring = true) and hasText("41.0", substring = true))

        compose.onNodeWithContentDescription("Back").performClick()
        waitFor(hasText("Half weight"))
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("No errors: the set can be saved.")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Save").performClick()
        waitFor(hasText("Import"))
        waitFor(hasText("Mine"))

        val stored = runBlocking { sets.observeAll().first() }.single()
        val file = FormulaSetFile.parse(stored.json)
        assertEquals("my_formulas", file.id)
        assertEquals("weight / 2 + a", file.indicators.single().formula)
        assertEquals(mapOf("a" to 1.0), file.indicators.single().params)
    }

    @Test
    fun aBuiltInCoefficientIsChangedAndAnErrorKeepsTheSetFromSaving() {
        openSets()
        compose.onNodeWithText("Create").performClick()
        waitFor(hasText("New set"))
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithTag("set_name").performTextInput("Leaner")
        compose.onNodeWithText("Change a built-in indicator").performScrollTo().performClick()
        waitFor(hasText("Search"))
        compose.onNode(hasSetTextAction() and hasText("Search")).performTextInput("Normal weight")
        compose.onNode(hasText("Normal weight from") and hasSetTextAction().not()).performClick()
        waitFor(hasText("bmi_low"))
        compose.onNodeWithTag("param_bmi_low").performTextInput("20")
        // 20 × 1.8²
        waitFor(hasText("64.8 kg", substring = true))

        compose.onNodeWithContentDescription("Back").performClick()
        // An id the built-in set has is not one's own
        compose.onNodeWithTag("id_field").performTextClearance()
        compose.onNodeWithTag("id_field").performTextInput("builtin")
        waitFor(hasText("Errors to fix before saving:"))
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithTag("id_field").performTextClearance()
        compose.onNodeWithTag("id_field").performTextInput("leaner")
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("No errors: the set can be saved.")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Save").performClick()
        waitFor(hasText("Leaner"))

        // The set is in the model at once
        val stored = runBlocking { sets.observeAll().first() }.single()
        assertEquals(mapOf("bmi_low" to 20.0), FormulaSetFile.parse(stored.json).patches.single().params)
    }

    @Test
    fun anEditedSetKeepsItsPlaceAndWhatTheEditorDoesNotShow() {
        val json = """{"format": 1, "id": "frame", "name": {"en": "Frame", "de": "Rahmen"},
            "inputs": [{"id": "wrist", "name": {"en": "Wrist"}, "unit": "cm"}],
            "indicators": [{"id": "frame", "name": {"en": "Frame size"}, "formula": "height / wrist"}],
            "patches": [{"id": "bmi", "precision": 2, "when": "age >= 20"}]}"""
        val id = runBlocking { sets.add("Frame", json, 1) }
        openSets()
        waitFor(hasText("Frame"))
        compose.onNodeWithText("Edit").performClick()
        waitFor(hasText("Id: frame"))
        compose.onNodeWithText("Frame size").performScrollTo().performClick()
        waitFor(hasText("Formula"))
        compose.onNodeWithTag("indicator_formula").performTextClearance()
        compose.onNodeWithTag("indicator_formula").performTextInput("height / wrist / 2")
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("No errors: the set can be saved.")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Save").performClick()
        waitFor(hasText("Import"))

        val stored = runBlocking { sets.observeAll().first() }.single()
        assertEquals(id, stored.id)
        val file = FormulaSetFile.parse(stored.json)
        assertEquals("height / wrist / 2", file.indicators.single().formula)
        assertEquals("Rahmen", file.name["de"])
        assertEquals("age >= 20", file.patches.single().applies)
    }

    private fun typeIntoLast(label: String, text: String) {
        val nodes = compose.onAllNodes(hasSetTextAction() and hasText(label))
        nodes[nodes.fetchSemanticsNodes().size - 1].performScrollTo().performTextInput(text)
    }

    private fun openSets() {
        compose.waitUntilAtLeastOneExists(hasContentDescription("Settings"), timeoutMillis = 5_000)
        compose.onNodeWithContentDescription("Settings").performClick()
        waitFor(hasText("Formula sets"))
        compose.onNodeWithText("Formula sets").performScrollTo().performClick()
        waitFor(hasText("Import"))
    }

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntilAtLeastOneExists(matcher, timeoutMillis = 5_000)
}
