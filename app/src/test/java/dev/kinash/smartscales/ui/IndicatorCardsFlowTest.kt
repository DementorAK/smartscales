package dev.kinash.smartscales.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dev.kinash.smartscales.MainActivity
import dev.kinash.smartscales.core.data.Measurement
import dev.kinash.smartscales.core.data.Profile
import dev.kinash.smartscales.core.data.repository.MeasurementRepository
import dev.kinash.smartscales.core.data.repository.ProfileRepository
import dev.kinash.smartscales.core.data.repository.ScaleBindingRepository
import dev.kinash.smartscales.core.model.Impedance
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
 * Indicator cards on the main screen for the man of FORMULAS_REVIEW: 180 cm, 80 kg, exactly 35 years old,
 * 450 Ω, so Wu 2015 gives 23.6 % body fat.
 */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
class IndicatorCardsFlowTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    @Inject
    lateinit var profiles: ProfileRepository

    @Inject
    lateinit var measurements: MeasurementRepository

    @Inject
    lateinit var scales: ScaleBindingRepository

    private var profileId = 0L

    @Before
    fun setUp() {
        hilt.inject()
        profileId = runBlocking {
            profiles.add(
                Profile(
                    name = "Dmitry",
                    sex = Sex.MALE,
                    birthDate = LocalDate.parse("1990-11-02"),
                    heightCm = 180.0,
                    color = 0xFF3F51B5.toInt(),
                    createdAt = Instant.parse("2026-01-01T00:00:00Z"),
                ),
            )
        }
    }

    @Test
    fun bodyFatFromImpedanceWithItsZone() {
        weighing(day = 2, ohms = 450.0)
        waitFor(hasText("23.6 %"))
        compose.onNodeWithText("Body fat").assertExists()
        compose.onNodeWithText("Elevated").assertExists()
        compose.onAllNodesWithText("Body fat (from fat-free mass)").assertCountEquals(3)
        compose.onNodeWithText("24.7 kg/m²").assertExists()
        // The weight comes first, with the impedance the body composition comes from
        compose.onNodeWithText("Impedance: 450 Ω").assertExists()
    }

    @Test
    fun withoutImpedanceBodyFatComesFromBmi() {
        weighing(day = 2, ohms = null)
        waitFor(hasText("Body fat (Gallagher 2000, from BMI)"))
        compose.onNodeWithText("19.9 %").assertExists()
        // Body water comes from height and weight; muscle needs impedance, and its card says so instead of disappearing
        compose.onNodeWithText("Body water (Watson 1980, from height and weight)").assertExists()
        compose.onNodeWithText("56.4 %").assertExists()
        compose.onAllNodesWithText("Needs impedance, and this weighing has none").assertCountEquals(1)
        compose.onNodeWithText("No impedance").assertExists()
    }

    @Test
    fun impedanceTheScaleSendsEveryTimeIsTreatedAsMissing() {
        val scale = runBlocking { scales.bind("AA:BB:CC:00:11:22", "AA:BB:CC:00:11:22", "OKOK_V2", null, 1) }
        weighing(day = 1, ohms = 600.0, scale = scale)
        weighing(day = 2, ohms = 600.0, scale = scale)
        // Two equal values may be a coincidence: still measured
        waitFor(hasText("Body fat (from fat-free mass)"))
        weighing(day = 3, ohms = 600.0, scale = scale)
        waitFor(hasText("Body fat (Gallagher 2000, from BMI)"))
        // The fat mass card names the method too
        compose.onAllNodesWithText("Body fat (Gallagher 2000, from BMI)").assertCountEquals(3)
        compose.onAllNodesWithText("Needs impedance: the scale sent a placeholder instead").assertCountEquals(1)
        compose.onNodeWithText("Impedance 600 Ω: a placeholder from the scale, not used").assertExists()

        // The profile shows that its default equations by impedance were not used, and why
        compose.onNodeWithContentDescription("Edit profile").performClick()
        waitFor(hasText("Formulas"))
        compose.onNodeWithText("The last weighing used: Gallagher 2000, from BMI. The scale sent a placeholder instead of impedance")
            .performScrollTo().assertExists()
        compose.onNodeWithText("The last weighing used: Mifflin–St Jeor. The scale sent a placeholder instead of impedance")
            .performScrollTo().assertExists()
    }

    @Test
    fun aRealValueAmongPlaceholdersKeepsItsImpedance() {
        val scale = runBlocking { scales.bind("AA:BB:CC:00:11:22", "AA:BB:CC:00:11:22", "OKOK_V2", null, 1) }
        weighing(day = 1, ohms = 600.0, scale = scale)
        weighing(day = 2, ohms = 600.0, scale = scale)
        weighing(day = 3, ohms = 600.0, scale = scale)
        weighing(day = 4, ohms = 450.0, scale = scale)
        waitFor(hasText("23.6 %"))
    }

    @Test
    fun profileChoosesTheBodyFatEquation() {
        weighing(day = 2, ohms = null)
        waitFor(hasText("Body fat (Gallagher 2000, from BMI)"))

        chooseBodyFat("Deurenberg 1991, from BMI")
        // Deurenberg 1991 for the review's man: 21.5 %
        waitFor(hasText("21.5 %"))
        compose.onAllNodesWithText("Body fat (Deurenberg 1991, from BMI)").assertCountEquals(3)
        assertEquals(mapOf("bf" to "bf_deurenberg"), runBlocking { profiles.observeMainVariants(profileId).first() })

        // Back to the first variant: the choice is cleared, not stored
        chooseBodyFat("From fat-free mass, needs impedance (default)")
        waitFor(hasText("19.9 %"))
        assertEquals(emptyMap<String, String>(), runBlocking { profiles.observeMainVariants(profileId).first() })
    }

    @Test
    fun choiceTheSetNoLongerOffersIsDroppedInsteadOfFailing() {
        weighing(day = 2, ohms = null)
        runBlocking {
            profiles.setMainVariant(profileId, "bf", "bf_gone")
            // Equations for women, chosen before the profile's sex was set to male
            profiles.setMainVariant(profileId, "tbw", "tbw_bell")
        }
        waitFor(hasText("Body fat (Gallagher 2000, from BMI)"))
        compose.onNodeWithContentDescription("Edit profile").performClick()
        waitFor(hasText("Formulas"))
        compose.onNodeWithText("From fat-free mass, needs impedance (default)").performScrollTo().assertExists()
        compose.onNodeWithText("Save").performClick()
        waitFor(hasText("Body mass index"))
        compose.waitUntil(timeoutMillis = 5_000) {
            runBlocking { profiles.observeMainVariants(profileId).first() }.isEmpty()
        }
    }

    private fun chooseBodyFat(option: String) {
        compose.onNodeWithContentDescription("Edit profile").performClick()
        waitFor(hasText("Formulas"))
        compose.onNodeWithText("Body fat").performScrollTo().performClick()
        compose.onNodeWithText(option).performClick()
        compose.onNodeWithText("Save").performClick()
        waitFor(hasText("Body mass index"))
    }

    private fun weighing(day: Int, ohms: Double?, scale: Long? = null) = runBlocking {
        measurements.add(
            Measurement(
                profileId = profileId,
                takenAt = Instant.parse("2025-11-0${day}T08:00:00Z"),
                zone = ZoneOffset.UTC,
                source = if (scale != null) MeasurementSource.SCALE else MeasurementSource.MANUAL,
                weightKg = 80.0,
                person = profiles.get(profileId)!!.snapshot(),
                impedances = listOfNotNull(ohms?.let { Impedance(Impedance.FOOT_TO_FOOT, it) }),
                scaleBindingId = scale,
            ),
        )
    }

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntilAtLeastOneExists(matcher, timeoutMillis = 5_000)
}
