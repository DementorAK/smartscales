package dev.kinash.smartscales.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
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

/** Two weighings picked in the history by a long press open side by side. */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
class CompareFlowTest {

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
            listOf(30L to 82.4, 10L to 81.0, 2L to 80.0).forEach { (days, kg) ->
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
    fun twoWeighingsPickedInTheHistoryOpenSideBySide() {
        waitFor(hasText("No impedance"))
        // The history tells how, until the first long press
        historyRow("To compare two weighings, touch and hold one of them", clickable = false)
        // The later one picked first: the comparison goes from the earlier one all the same
        historyRow("81.0 kg").performTouchInput { longClick() }
        waitFor(hasText("Choose another weighing"))
        historyRow("82.4 kg").performClick()
        waitFor(hasText("Two weighings chosen"))
        compose.onNodeWithText("Compare").performClick()

        waitFor(hasText("20 days apart"))
        compose.onNodeWithText("−1.4 kg").assertExists()
        compose.onNodeWithText("Body mass index").assertExists()
        // The hint is gone after the first long press
        compose.onNodeWithContentDescription("Back").performClick()
        waitFor(hasText("No impedance"))
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("To compare two weighings", substring = true)).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun backCancelsTheChoice() {
        waitFor(hasText("No impedance"))
        historyRow("81.0 kg").performTouchInput { longClick() }
        waitFor(hasText("Choose another weighing"))
        compose.onNodeWithContentDescription("Cancel the choice").performClick()
        // A tap opens the weighing again
        historyRow("81.0 kg").performClick()
        waitFor(hasText("Height at that time"))
    }

    /** The history's row, scrolled to below the cards; the weight card is not one of them. */
    private fun historyRow(text: String, clickable: Boolean = true): SemanticsNodeInteraction {
        val matcher = if (clickable) hasText(text) and hasClickAction() and !hasText("Weight") else hasText(text)
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(matcher)
        return compose.onNode(matcher)
    }

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntilAtLeastOneExists(matcher, timeoutMillis = 5_000)
}
