package dev.kinash.smartscales.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dev.kinash.smartscales.MainActivity
import dev.kinash.smartscales.core.data.Measurement
import dev.kinash.smartscales.core.data.Profile
import dev.kinash.smartscales.core.data.repository.FormulaSetRepository
import dev.kinash.smartscales.core.data.repository.MeasurementRepository
import dev.kinash.smartscales.core.data.repository.ProfileRepository
import dev.kinash.smartscales.core.model.LengthUnit
import dev.kinash.smartscales.core.model.ManualFields
import dev.kinash.smartscales.core.model.MeasurementSource
import dev.kinash.smartscales.core.model.PersonSnapshot
import dev.kinash.smartscales.core.model.Sex
import dev.kinash.smartscales.core.model.WeightUnit
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
import java.time.ZoneId
import javax.inject.Inject

/** Whole-app scenarios of 0.2 on Robolectric with an in-memory database. */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
class AppFlowTest {

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
    fun inject() {
        hilt.inject()
    }

    @Test
    fun createProfileAndAddManualMeasurement() {
        waitFor(hasText("Create a profile", substring = true))
        compose.onNodeWithText("New profile").performClick()

        // Saving an empty form keeps the screen open
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithTag("profile_name").assertExists()

        compose.onNodeWithTag("profile_name").performTextInput("Dmitry")
        compose.onNodeWithText("Male").performScrollTo().performClick()
        compose.onNodeWithText("Date of birth").performScrollTo().performClick()
        compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextInput("10281990")
        compose.onNodeWithText("OK").performClick()
        compose.onNodeWithTag("profile_height").performScrollTo().performTextInput("180")
        // The waist is kept in the profile, with a hint for men on how to measure
        compose.onNode(hasText("Waist") and hasSetTextAction()).performScrollTo().performTextInput("86")
        compose.onNodeWithText("Adam", substring = true).assertExists()
        compose.onNodeWithText("Save").performClick()

        waitFor(hasText("No measurements yet", substring = true))
        compose.onNodeWithText("Dmitry").assertExists()

        compose.onNodeWithContentDescription("New measurement").performClick()
        waitFor(hasText("Height at that time"))
        compose.onNodeWithTag("measurement_weight").performTextInput("80,5")
        compose.onNodeWithText("Save").performClick()

        waitForRow("80.5 kg")
        val profile = runBlocking { profiles.observeProfiles().first().single() }
        assertEquals(LocalDate.parse("1990-10-28"), profile.birthDate)
        val saved = runBlocking { measurements.observeHistory(profile.id).first().single() }
        assertEquals(PersonSnapshot(180.0, Sex.MALE, LocalDate.parse("1990-10-28")), saved.person)
        assertEquals(MeasurementSource.MANUAL, saved.source)
        // The manual entry came with the waist from the profile
        assertEquals(mapOf(ManualFields.WAIST_CM to 86.0), saved.manualValues)
    }

    @Test
    fun changedSexIsAppliedToPastMeasurementsOnRequest() {
        val profileId = addProfile("Dmitry")
        val measurementId = addMeasurement(profileId)

        waitForRow("80.0 kg")
        compose.openProfile("Dmitry")
        compose.onNodeWithText("Female").performScrollTo().performClick()
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("Apply to past measurements?").assertExists()
        compose.onNodeWithText("Update all").performClick()

        compose.backFromSettings()
        waitForRow("80.0 kg")
        assertEquals(Sex.FEMALE, runBlocking { measurements.get(measurementId) }!!.person!!.sex)
    }

    @Test
    fun deletingProfileWarnsWithNumberOfMeasurements() {
        val profileId = addProfile("Dmitry")
        repeat(3) { addMeasurement(profileId) }

        waitFor(hasText("Dmitry"))
        compose.openProfile("Dmitry")
        compose.onNodeWithContentDescription("Delete profile").performClick()
        waitFor(hasText("3 measurements will be deleted", substring = true))
        compose.onNodeWithText("Delete").performClick()

        compose.backFromSettings()
        waitFor(hasText("Create a profile", substring = true))
        assertEquals(0, runBlocking { profiles.countMeasurements(profileId) })
    }

    @Test
    fun measurementMovesToAnotherProfileWithItsSnapshot() {
        val dmitry = addProfile("Dmitry")
        val anna = addProfile("Anna", Sex.FEMALE, heightCm = 168.0)
        val measurementId = addMeasurement(dmitry)
        // Nothing is selected yet, so the history shows the first profile by name
        waitFor(hasText("No measurements yet", substring = true))

        compose.onNodeWithText("Anna").performClick()
        compose.onNodeWithText("Dmitry").performClick()
        waitForRow("80.0 kg")
        historyRow("80.0 kg").performClick()
        waitFor(hasText("Height at that time"))
        // An edit made before moving is saved, not lost
        compose.onNodeWithTag("measurement_weight").performTextClearance()
        compose.onNodeWithTag("measurement_weight").performTextInput("79.5")
        compose.onNodeWithContentDescription("Move to another profile").performClick()
        waitFor(hasText("Move to profile"))
        compose.onNode(hasText("Anna") and hasAnyAncestor(isDialog()), useUnmergedTree = true).performClick()

        waitFor(hasText("Moved to Anna", substring = true))
        val moved = runBlocking { measurements.get(measurementId) }!!
        assertEquals(anna, moved.profileId)
        assertEquals(79.5, moved.weightKg, 0.0)
        assertEquals(PersonSnapshot(168.0, Sex.FEMALE, LocalDate.parse("1992-03-15")), moved.person)
    }

    @Test
    fun inboxWeighingIsAssignedToProfile() {
        val profileId = addProfile("Dmitry")
        val measurementId = runBlocking {
            measurements.add(
                Measurement(
                    profileId = null,
                    takenAt = Instant.parse("2026-10-27T07:00:00Z"),
                    zone = ZoneId.systemDefault(),
                    source = MeasurementSource.SCALE,
                    weightKg = 81.0,
                    person = null,
                ),
            )
        }

        waitFor(hasText("No measurements yet", substring = true))
        compose.onNodeWithContentDescription("Inbox").performClick()
        waitFor(hasText("81.0 kg"))
        compose.onNodeWithText("Assign").performClick()
        compose.onNode(hasText("Dmitry") and hasAnyAncestor(isDialog()), useUnmergedTree = true).performClick()

        waitFor(hasText("No unassigned weighings."))
        val assigned = runBlocking { measurements.get(measurementId) }!!
        assertEquals(profileId, assigned.profileId)
        assertEquals(PersonSnapshot(180.0, Sex.MALE, LocalDate.parse("1990-10-28")), assigned.person)
    }

    @Test
    fun savingUnchangedFieldsKeepsExactValuesInStonesAndInches() {
        val profileId = addProfile("Dmitry", weightUnit = WeightUnit.STONE, lengthUnit = LengthUnit.INCH)
        runBlocking { profiles.setGoal(profileId, ProfileRepository.WEIGHT_GOAL, 70.123) }
        // Fields of a set too, a length and a weight (in pounds for stones), and a value no set declares now
        runBlocking { sets.add("Fields", FIELDS, 1) }
        val extra = mapOf("wrist" to 17.46, "load" to 3.333, "gone" to 1.0)
        val measurementId = addMeasurement(profileId, weightKg = 80.123, waistCm = 85.3, manualValues = extra)

        waitForRow("12 st 8.6 lb")
        historyRow("12 st 8.6 lb").performClick()
        waitFor(hasText("Height at that time"))
        waitFor(hasText("6.9"))
        waitFor(hasText("7.35"))
        compose.onNodeWithText("Note").performTextInput("only the note")
        compose.onNodeWithText("Save").performClick()
        waitFor(hasContentDescription("Settings"))
        compose.openProfile("Dmitry")
        // Saved without changes: height, goal and circumferences must not pick up the rounding of their text
        compose.onNodeWithText("Save").performClick()
        compose.backFromSettings()

        val saved = runBlocking { measurements.get(measurementId) }!!
        assertEquals("only the note", saved.note)
        assertEquals(80.123, saved.weightKg, 0.0)
        assertEquals(180.0, saved.person!!.heightCm, 0.0)
        assertEquals(85.3, saved.manualValues.getValue(ManualFields.WAIST_CM), 0.0)
        assertEquals(extra, saved.manualValues - ManualFields.WAIST_CM)
        assertEquals(180.0, runBlocking { profiles.get(profileId) }!!.heightCm, 0.0)
        assertEquals(70.123, runBlocking { profiles.getGoal(profileId, ProfileRepository.WEIGHT_GOAL) }!!, 0.0)
    }

    @Test
    fun personBornAfterMeasurementIsNotOfferedForMove() {
        val dmitry = addProfile("Dmitry")
        addProfile("Kid", birthDate = "2026-10-28")
        val measurementId = addMeasurement(dmitry, takenAt = "2026-10-27T07:00:00Z")
        waitForRow("80.0 kg")

        historyRow("80.0 kg").performClick()
        compose.onNodeWithContentDescription("Move to another profile").performClick()
        waitFor(hasText("Born after this measurement was taken"))
        compose.onNode(hasText("Kid") and hasAnyAncestor(isDialog()), useUnmergedTree = true).performClick()

        compose.onNodeWithText("Move to profile").assertExists()
        assertEquals(dmitry, runBlocking { measurements.get(measurementId) }!!.profileId)
    }

    @Test
    fun laterBirthDateIsNotAppliedToEarlierMeasurements() {
        val profileId = addProfile("Dmitry")
        val measurementId = addMeasurement(profileId, takenAt = "2020-06-01T07:00:00Z")

        waitForRow("80.0 kg")
        compose.openProfile("Dmitry")
        compose.onNodeWithText("Date of birth").performScrollTo().performClick()
        val input = compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog()))
        input.performTextClearance()
        input.performTextInput("01012021")
        compose.onNodeWithText("OK").performClick()
        compose.onNodeWithText("Save").performClick()
        waitFor(hasText("1 past measurement is dated before", substring = true))
        compose.onNodeWithText("Update all").assertDoesNotExist()
        compose.onNodeWithText("Only new ones").performClick()

        compose.backFromSettings()
        waitForRow("80.0 kg")
        assertEquals(LocalDate.parse("2021-01-01"), runBlocking { profiles.get(profileId) }!!.birthDate)
        assertEquals(LocalDate.parse("1990-10-28"), runBlocking { measurements.get(measurementId) }!!.person!!.birthDate)
    }

    /** Database work runs on Room's threads, so screens update a little later than the click. */
    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntilExactlyOneExists(matcher, timeoutMillis = 5_000)

    /**
     * Waits for a history row and scrolls the list to it: the indicator cards of the newest measurement come first
     * and can push the rows below the small test screen, where the lazy list does not compose them.
     */
    private fun waitForRow(text: String) {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes().isNotEmpty() &&
                runCatching { compose.onNode(hasScrollToNodeAction()).performScrollToNode(row(text)) }.isSuccess
        }
        waitFor(row(text))
    }

    /** A row of the history: the cards above it show the same weight, but only the row opens the measurement. */
    private fun row(text: String) = hasText(text) and hasClickAction()

    /**
     * The history's row, scrolled to: the cards above it may fill the screen, and the weight card, which opens
     * the chart, has the same text.
     */
    private fun historyRow(text: String): SemanticsNodeInteraction {
        val matcher = row(text) and !hasText("Weight")
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(matcher)
        return compose.onNode(matcher)
    }

    private fun addProfile(
        name: String,
        sex: Sex = Sex.MALE,
        heightCm: Double = 180.0,
        birthDate: String = if (sex == Sex.MALE) "1990-10-28" else "1992-03-15",
        weightUnit: WeightUnit = WeightUnit.KILOGRAM,
        lengthUnit: LengthUnit = LengthUnit.CENTIMETRE,
    ): Long = runBlocking {
        profiles.add(
            Profile(
                name = name,
                sex = sex,
                birthDate = LocalDate.parse(birthDate),
                heightCm = heightCm,
                weightUnit = weightUnit,
                lengthUnit = lengthUnit,
                color = 0xFF3F51B5.toInt(),
                createdAt = Instant.parse("2026-01-01T00:00:00Z"),
            ),
        )
    }

    private fun addMeasurement(
        profileId: Long,
        takenAt: String = "2026-10-27T07:00:00Z",
        weightKg: Double = 80.0,
        waistCm: Double? = null,
        manualValues: Map<String, Double> = emptyMap(),
    ): Long = runBlocking {
        val profile = profiles.get(profileId)!!
        measurements.add(
            Measurement(
                profileId = profileId,
                takenAt = Instant.parse(takenAt),
                zone = ZoneId.systemDefault(),
                source = MeasurementSource.MANUAL,
                weightKg = weightKg,
                person = profile.snapshot(),
                manualValues = waistCm?.let { mapOf(ManualFields.WAIST_CM to it) }.orEmpty() + manualValues,
            ),
        )
    }

    private companion object {
        const val FIELDS = """{
            "format": 1, "id": "fields", "name": {"en": "Fields"},
            "inputs": [
              {"id": "wrist", "name": {"en": "Wrist"}, "unit": "cm"},
              {"id": "load", "name": {"en": "Load"}, "unit": "kg"}
            ]
        }"""
    }
}
