package dev.kinash.smartscales.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
import dev.kinash.smartscales.core.data.repository.MeasurementRepository
import dev.kinash.smartscales.core.data.repository.ProfileRepository
import dev.kinash.smartscales.core.data.repository.SettingsRepository
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
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import javax.inject.Inject

/** A goal is set on the indicator's chart and shows on its card. */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
class GoalFlowTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    @Inject
    lateinit var profiles: ProfileRepository

    @Inject
    lateinit var measurements: MeasurementRepository

    @Inject
    lateinit var settings: SettingsRepository

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
            // Losing about a kilogram in ten days
            listOf(20L to 82.0, 10L to 81.0, 2L to 80.0).forEach { (days, kg) ->
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
    fun goalSetOnTheChartShowsOnTheCardAndIsRemoved() {
        waitFor(hasText("No impedance"))
        compose.onNodeWithText("Weight").performClick()
        waitFor(hasText("Set a goal"))
        compose.onNodeWithText("Set a goal").performScrollTo().performClick()
        // The latest weight says nothing of which way to go
        compose.onNodeWithText("Target").performTextInput("80")
        compose.onNodeWithText("Save").performClick()
        waitFor(hasText("This is the latest value", substring = true))
        compose.onNodeWithText("Target").performTextClearance()
        compose.onNodeWithText("Target").performTextInput("78")
        compose.onNodeWithText("Save").performClick()

        // Counted from the last weighing, 80 kg
        waitFor(hasText("Goal: 78.0 kg"))
        compose.onNodeWithText("0% of the way, 2.0 kg to go").assertExists()
        compose.onNodeWithText("At the current trend, by", substring = true).assertExists()

        compose.onNodeWithContentDescription("Back").performClick()
        waitFor(hasText("Goal 78.0 kg, 2.0 kg to go"))

        compose.onNodeWithText("Weight").performClick()
        waitFor(hasText("Goal: 78.0 kg"))
        compose.onNodeWithText("Remove").performScrollTo().performClick()
        waitFor(hasText("Set a goal"))
    }

    @Test
    fun aChildHasNoGoals() {
        runBlocking {
            val kid = profiles.add(
                Profile(
                    name = "Kid",
                    sex = Sex.FEMALE,
                    birthDate = LocalDate.now().minusYears(10),
                    heightCm = 140.0,
                    color = 0xFF3F51B5.toInt(),
                    createdAt = Instant.parse("2026-01-01T00:00:00Z"),
                ),
            )
            // A goal kept from before is not shown, nor dropped
            profiles.setGoal(kid, ProfileRepository.WEIGHT_GOAL, 30.0)
            measurements.add(
                Measurement(
                    profileId = kid,
                    takenAt = Instant.now() - Duration.ofDays(1),
                    zone = ZoneOffset.UTC,
                    source = MeasurementSource.MANUAL,
                    weightKg = 32.0,
                    person = profiles.get(kid)!!.snapshot(),
                ),
            )
            settings.selectProfile(kid)
        }
        waitFor(hasText("Height"))
        compose.onAllNodes(hasText("Goal", substring = true)).assertCountEquals(0)
        compose.onNodeWithText("Weight").performClick()
        waitFor(hasText("Weight, kg"))
        compose.onNodeWithText("Set a goal").assertDoesNotExist()
        compose.onNodeWithText("Goal: ", substring = true).assertDoesNotExist()

        compose.onNodeWithContentDescription("Back").performClick()
        compose.openProfile("Kid")
        compose.onNodeWithText("Goal weight (optional)").assertDoesNotExist()
        compose.onNodeWithText("Save").performClick()
        compose.backFromSettings()
        assertEquals(30.0, runBlocking { profiles.getGoal(profiles.observeProfiles().first().first { it.name == "Kid" }.id, ProfileRepository.WEIGHT_GOAL) }!!, 0.0)
    }

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntilAtLeastOneExists(matcher, timeoutMillis = 5_000)
}
