package dev.kinash.smartscales.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dev.kinash.smartscales.MainActivity
import dev.kinash.smartscales.core.data.Measurement
import dev.kinash.smartscales.core.data.Profile
import dev.kinash.smartscales.core.data.repository.MeasurementRepository
import dev.kinash.smartscales.core.data.repository.ProfileRepository
import dev.kinash.smartscales.core.formula.CdcGrowthCharts
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
import kotlin.math.roundToInt

/** Children's cards: CDC percentiles from 2 years, weight and height only below 2, no body composition. */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
class ChildCardsFlowTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    @Inject
    lateinit var profiles: ProfileRepository

    @Inject
    lateinit var measurements: MeasurementRepository

    @Before
    fun setUp() = hilt.inject()

    @Test
    fun tenYearOldGetsPercentilesAndNoBodyComposition() {
        // Exactly 10 years old at the weighing, 140 cm, 35 kg
        weighing(birthDate = "2015-11-02", heightCm = 140.0, weightKg = 35.0)
        val age = 120.0
        val heightPercentile = CdcGrowthCharts.statureForAge.percentile(Sex.MALE, age, 140.0)!!.roundToInt()
        val bmiExact = CdcGrowthCharts.bmiForAge.percentile(Sex.MALE, age, 35.0 / 1.4 / 1.4)!!
        val bmiPercentile = bmiExact.roundToInt()

        waitFor(hasText("Weight"))
        compose.onNodeWithText("Height").assertExists()
        compose.onNodeWithText("Percentile: $heightPercentile").assertExists()
        compose.onNodeWithText("Percentile: $bmiPercentile").assertExists()
        compose.onNodeWithText("17.9 kg/m²").assertExists()
        // CDC weight status: below the 5th percentile low, to the 85th normal, to the 95th elevated, then high
        val status = when {
            bmiExact < 5 -> "Low"
            bmiExact < 85 -> "Normal"
            bmiExact < 95 -> "Elevated"
            else -> "High"
        }
        compose.onNodeWithText(status).assertExists()
        // Body composition is for adults, even with impedance
        compose.onNodeWithText("Body fat").assertDoesNotExist()
    }

    @Test
    fun underTwoOnlyWeightAndHeight() {
        weighing(birthDate = "2024-05-02", heightCm = 82.0, weightKg = 11.0)
        waitFor(hasText("Weight"))
        compose.onNodeWithText("Height").assertExists()
        compose.onNodeWithText("Body mass index").assertDoesNotExist()
        compose.onNodeWithText("Percentile:", substring = true).assertDoesNotExist()
    }

    private fun weighing(birthDate: String, heightCm: Double, weightKg: Double) = runBlocking {
        val id = profiles.add(
            Profile(
                name = "Kid",
                sex = Sex.MALE,
                birthDate = LocalDate.parse(birthDate),
                heightCm = heightCm,
                color = 0xFF3F51B5.toInt(),
                createdAt = Instant.parse("2025-01-01T00:00:00Z"),
            ),
        )
        measurements.add(
            Measurement(
                profileId = id,
                takenAt = Instant.parse("2025-11-02T08:00:00Z"),
                zone = ZoneOffset.UTC,
                source = MeasurementSource.MANUAL,
                weightKg = weightKg,
                person = profiles.get(id)!!.snapshot(),
                impedances = listOf(Impedance(Impedance.FOOT_TO_FOOT, 600.0)),
            ),
        )
    }

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntilAtLeastOneExists(matcher, timeoutMillis = 5_000)
}
