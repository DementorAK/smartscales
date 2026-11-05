package dev.kinash.smartscales.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dev.kinash.smartscales.MainActivity
import dev.kinash.smartscales.core.data.Measurement
import dev.kinash.smartscales.core.data.Profile
import dev.kinash.smartscales.core.data.repository.FormulaSetRepository
import dev.kinash.smartscales.core.data.repository.MeasurementRepository
import dev.kinash.smartscales.core.data.repository.ProfileRepository
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
import org.robolectric.Shadows.shadowOf
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import javax.inject.Inject

/** Importing a formula set in the settings: the cards follow it at once, a broken file is not taken. */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
class FormulaSetsFlowTest {

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
    fun anImportedPatchChangesTheCardsUntilSwitchedOff() {
        waitFor(hasText("24.7 kg/m²"))
        openSets()
        pickFile(PRECISE_BMI)
        waitFor(hasText("Add “Precise BMI”?"))
        compose.onNodeWithText("Changes: 1").assertExists()
        compose.onNodeWithText("Add").performClick()
        waitFor(hasText("Set added"))
        assertEquals(listOf("Precise BMI"), runBlocking { sets.observeAll().first().map { it.name } })

        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        waitFor(hasText("24.69 kg/m²"))

        // Off: the built-in precision is back, and the set stays for later
        openSets()
        compose.onNode(isToggleable()).performClick()
        waitFor(hasText("Off"))
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        waitFor(hasText("24.7 kg/m²"))
    }

    @Test
    fun aFileWithAnErrorIsNotTakenAndSaysWhere() {
        openSets()
        pickFile(PRECISE_BMI.replace("\"precision\": 2", "\"precision\": 9"))
        waitFor(hasText("The set was not imported"))
        compose.onNodeWithText("patches[0].precision", substring = true).assertExists()
        compose.onNodeWithText("OK").performClick()
        assertEquals(emptyList<Any>(), runBlocking { sets.observeAll().first() })
    }

    @Test
    fun importingTheSameIdAgainReplacesTheSet() {
        runBlocking { sets.add("Precise BMI", PRECISE_BMI, 1) }
        openSets()
        waitFor(hasText("Precise BMI"))
        pickFile(PRECISE_BMI.replace("\"precision\": 2", "\"precision\": 3"))
        waitFor(hasText("Replace “Precise BMI”?"))
        compose.onNodeWithText("Replace").performClick()
        waitFor(hasText("Set replaced"))
        val stored = runBlocking { sets.observeAll().first() }
        assertEquals(1, stored.size)
        assertEquals(true, "\"precision\": 3" in stored.single().json)
    }

    private fun openSets() {
        compose.waitUntilAtLeastOneExists(hasContentDescription("Settings"), timeoutMillis = 5_000)
        compose.onNodeWithContentDescription("Settings").performClick()
        waitFor(hasText("Formula sets"))
        compose.onNodeWithText("Formula sets").performScrollTo().performClick()
        waitFor(hasText("Import"))
    }

    /** Taps Import and answers the picker with a file holding [text]. */
    private fun pickFile(text: String) {
        compose.onNodeWithText("Import").performClick()
        compose.waitForIdle()
        val uri = Uri.parse("content://test.files/set.json")
        val activity = shadowOf(compose.activity)
        shadowOf(compose.activity.contentResolver).registerInputStream(uri, text.byteInputStream())
        val request = activity.nextStartedActivityForResult
        activity.receiveResult(request.intent, Activity.RESULT_OK, Intent().setData(uri))
    }

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntilAtLeastOneExists(matcher, timeoutMillis = 5_000)

    private companion object {
        const val PRECISE_BMI = """{
            "format": 1, "id": "precise_bmi", "name": {"en": "Precise BMI"},
            "patches": [{"id": "bmi", "precision": 2}]
        }"""
    }
}
