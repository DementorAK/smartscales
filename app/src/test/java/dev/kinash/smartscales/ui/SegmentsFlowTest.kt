package dev.kinash.smartscales.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dev.kinash.smartscales.MainActivity
import dev.kinash.smartscales.core.data.Measurement
import dev.kinash.smartscales.core.data.Profile
import dev.kinash.smartscales.core.data.repository.MeasurementRepository
import dev.kinash.smartscales.core.data.repository.ProfileRepository
import dev.kinash.smartscales.core.model.Impedance
import dev.kinash.smartscales.core.model.MeasurementSource
import dev.kinash.smartscales.core.model.Sex
import kotlinx.coroutines.flow.first
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

/** An 8-electrode weighing's segments open from the main screen on a body, and each on its chart. */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
class SegmentsFlowTest {

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
            // Two CB weighings a week apart: the right arm from 330.0 to 320.5 Ω at the first frequency
            listOf(7L to 330.0, 1L to 320.5).forEach { (days, rightArm) ->
                measurements.add(
                    Measurement(
                        profileId = id,
                        takenAt = Instant.now() - Duration.ofDays(days),
                        zone = ZoneOffset.UTC,
                        source = MeasurementSource.MANUAL,
                        weightKg = 80.0,
                        person = profiles.get(id)!!.snapshot(),
                        impedances = (0..1).flatMap { f ->
                            (1..5).map { Impedance(it, if (it == 1) rightArm + 100 * f else 400.0 + 10 * it + 100 * f, frequencyIndex = f) }
                        },
                    ),
                )
            }
        }
    }

    @Test
    fun segmentsOpenOnABodyAndEachOnItsChart() {
        // Below the cards, which may fill the screen
        compose.waitUntil(5_000) { compose.onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Impedance of the arms, legs and trunk at two frequencies"))
        compose.onNodeWithText("Body segments").performClick()

        waitFor(hasText("Right arm"))
        compose.onNodeWithText("−9.5 Ω since the weighing before").performScrollTo()
        compose.onNodeWithText("Frequency 2").performScrollTo().performClick()
        waitFor(hasText("420.5 Ω"))

        compose.onNodeWithText("Right arm, frequency 2").performScrollTo().performClick()
        waitFor(hasContentDescription("Right arm, frequency 2: 2 weighings, the last 420.5 Ω"))
        // No goal for an impedance
        compose.onNodeWithText("Set a goal").assertDoesNotExist()
    }

    @Test
    fun aLaterWeighingWithoutSegmentsKeepsTheCardWithItsDate() {
        runBlocking {
            val id = profiles.observeProfiles().first().single().id
            measurements.add(
                Measurement(
                    profileId = id,
                    takenAt = Instant.now(),
                    zone = ZoneOffset.UTC,
                    source = MeasurementSource.MANUAL,
                    weightKg = 79.5,
                    person = profiles.get(id)!!.snapshot(),
                ),
            )
        }
        compose.waitUntil(5_000) { compose.onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("From the weighing of", substring = true))
        compose.onNodeWithText("Body segments").performClick()
        waitFor(hasText("Right arm"))
    }

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntilAtLeastOneExists(matcher, timeoutMillis = 5_000)
}
