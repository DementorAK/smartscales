package dev.kinash.smartscales.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
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
import kotlinx.coroutines.runBlocking
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
    }

    @Test
    fun withoutImpedanceBodyFatComesFromBmi() {
        weighing(day = 2, ohms = null)
        waitFor(hasText("Body fat (Gallagher 2000, from BMI)"))
        compose.onNodeWithText("19.9 %").assertExists()
        // Body water and muscle need impedance
        compose.onNodeWithText("Body water").assertDoesNotExist()
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
