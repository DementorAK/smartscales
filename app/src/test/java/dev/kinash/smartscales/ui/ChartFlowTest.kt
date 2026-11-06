package dev.kinash.smartscales.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
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
import dev.kinash.smartscales.core.data.repository.MeasurementRepository
import dev.kinash.smartscales.core.data.repository.ProfileRepository
import dev.kinash.smartscales.core.model.MeasurementSource
import dev.kinash.smartscales.core.model.Sex
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import javax.inject.Inject

/** A card opens the chart of its indicator; the period and the series on it change there. */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
class ChartFlowTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    @Inject
    lateinit var profiles: ProfileRepository

    @Inject
    lateinit var measurements: MeasurementRepository

    @Before
    fun setUp() {
        hilt.inject()
        runBlocking {
            val id = profiles.add(
                Profile(
                    name = "Dmitry",
                    sex = Sex.MALE,
                    birthDate = LocalDate.parse("1990-11-02"),
                    heightCm = 180.0,
                    color = 0xFF3F51B5.toInt(),
                    createdAt = Instant.parse("2026-01-01T00:00:00Z"),
                ),
            )
            // Two weighings this month and one two months ago
            listOf(60L to 82.0, 10L to 81.0, 2L to 80.0).forEach { (days, kg) ->
                measurements.add(
                    Measurement(
                        profileId = id,
                        takenAt = Instant.now() - Duration.ofDays(days),
                        zone = ZoneOffset.UTC,
                        source = MeasurementSource.MANUAL,
                        weightKg = kg,
                        person = profiles.get(id)!!.snapshot(),
                    ),
                )
            }
        }
    }

    @Test
    fun cardOpensItsChartAndAnotherIndicatorGoesOnTheRightAxis() {
        waitFor(hasText("Body mass index"))
        compose.onNodeWithText("Body mass index").performClick()

        // The month has two weighings, so the chart opens on it
        waitFor(hasContentDescription("Body mass index: 2 weighings, the last 24.7 kg/m²"))
        compose.onNodeWithText("Coloured bands: the zones of the norm for the latest weighing").assertExists()
        compose.onNodeWithText("All").performClick()
        waitFor(hasContentDescription("Body mass index: 3 weighings, the last 24.7 kg/m²"))

        compose.onNodeWithText("Add to the chart").performScrollTo().performClick()
        compose.onNodeWithText("Weight").performClick()
        waitFor(hasText("Weight, kg"))
        compose.onNodeWithText("Right axis").assertExists()
        compose.onNodeWithContentDescription("Remove Weight from the chart").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Weight, kg")).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun weightCardOpensTheWeightChart() {
        waitFor(hasText("No impedance"))
        compose.onNodeWithText("Weight").performClick()
        waitFor(hasContentDescription("Weight: 2 weighings, the last 80.0 kg"))
        // No zones for the weight itself
        compose.onNodeWithText("Coloured bands: the zones of the norm for the latest weighing").assertDoesNotExist()
    }

    @Test
    fun indicatorWithoutValuesSaysSoInsteadOfNoWeighings() {
        // Muscle needs impedance, and these weighings are without it: its card stays to say so
        waitFor(hasText("Skeletal muscle mass (Janssen 2000)"))
        compose.onNodeWithText("Skeletal muscle mass (Janssen 2000)").performScrollTo().performClick()
        waitFor(hasText("No values in this period"))
        compose.onNodeWithText("No weighings in this period").assertDoesNotExist()
    }

    @Test
    fun aGroupsChartComparesItsFormulas() {
        waitFor(hasText("Body fat"))
        compose.onNodeWithText("Body fat").performScrollTo().performClick()
        // Without impedance the group goes by BMI, and the legend says so
        waitFor(hasText("Formula: Gallagher 2000, from BMI"))
        compose.onNodeWithText("Compare formulas").performScrollTo().performClick()
        // Only the variants with values: by impedance and by circumferences there are none
        waitFor(hasText("Body fat (Deurenberg 1991, from BMI), %"))
        compose.onNodeWithText("Body fat (from fat-free mass), %").assertDoesNotExist()
        compose.onNodeWithText("Compare formulas").assertDoesNotExist()
    }

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntilAtLeastOneExists(matcher, timeoutMillis = 5_000)
}
