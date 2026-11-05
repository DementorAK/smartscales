package dev.kinash.smartscales.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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

/** A tag picked on the main screen filters the history and the charts until it is turned off. */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
class TagFilterFlowTest {

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
            // Two mornings, and an evening after the gym before them
            listOf(Triple(20L, 82.6, "After gym"), Triple(9L, 81.4, "Morning"), Triple(2L, 80.8, "Morning")).forEach { (days, kg, tag) ->
                measurements.add(
                    Measurement(
                        profileId = id,
                        takenAt = Instant.now() - Duration.ofDays(days),
                        zone = ZoneOffset.UTC,
                        source = MeasurementSource.MANUAL,
                        weightKg = kg,
                        person = profiles.get(id)!!.snapshot(),
                        tags = listOf(tag),
                    ),
                )
            }
        }
    }

    @Test
    fun tagFiltersTheHistoryAndTheChart() {
        waitFor(hasText("No impedance"))
        // Below the cards: scrolled to, as the list composes what is on the screen only
        scrollTo(hasText("Only weighings with all the tags chosen"))
        // The chip, not a row that has the tag under its weight
        compose.onNode(hasText("Morning") and hasClickAction() and !hasText("kg", substring = true)).performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("82.6 kg")).fetchSemanticsNodes().isEmpty() }
        scrollTo(hasText("81.4 kg"))

        // The chart has the same filter, and only the mornings
        scrollTo(hasText("Weight") and hasText("No impedance"))
        compose.onNodeWithText("Weight").performClick()
        waitFor(hasContentDescription("Weight: 2 weighings, the last 80.8 kg"))
        compose.onNodeWithText("Morning").assertIsSelected()
        compose.onNodeWithText("After gym").performClick()
        waitFor(hasText("No weighings with all these tags"))
        // After the gym only: none this week, but one earlier, so not "none with these tags"
        compose.onNodeWithText("Morning").performClick()
        waitFor(hasContentDescription("Weight: 1 weighing, the last 82.6 kg"))
        compose.onNodeWithText("Week").performClick()
        waitFor(hasText("No weighings in this period"))
        compose.onNodeWithText("No weighings with all these tags").assertDoesNotExist()
        compose.onNodeWithText("Month").performClick()
        compose.onNodeWithText("After gym").performClick()
        waitFor(hasContentDescription("Weight: 3 weighings, the last 80.8 kg"))

        compose.onNodeWithContentDescription("Back").performClick()
        waitFor(hasText("No impedance"))
        scrollTo(hasText("82.6 kg"))
    }

    private fun scrollTo(matcher: SemanticsMatcher) {
        compose.waitUntil(5_000) { compose.onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(matcher)
    }

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntilAtLeastOneExists(matcher, timeoutMillis = 5_000)
}
