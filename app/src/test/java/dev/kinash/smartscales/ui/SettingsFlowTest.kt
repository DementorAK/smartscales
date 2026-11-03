package dev.kinash.smartscales.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dev.kinash.smartscales.MainActivity
import dev.kinash.smartscales.core.data.Profile
import dev.kinash.smartscales.core.data.repository.ProfileRepository
import dev.kinash.smartscales.core.data.repository.SettingsRepository
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
import javax.inject.Inject

@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
class SettingsFlowTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    @Inject
    lateinit var settings: SettingsRepository

    @Inject
    lateinit var profiles: ProfileRepository

    @Before
    fun setUp() = hilt.inject()

    @Test
    fun thresholdIsChangedWithTheSlider() {
        openSettings()
        waitFor(hasText("±3.0 kg"))

        val slider = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)
        compose.onNode(slider).performSemanticsAction(SemanticsActions.SetProgress) { it(1.5f) }
        waitFor(hasText("±1.5 kg"))
        compose.waitUntil(timeoutMillis = 5_000) { runBlocking { settings.matchThresholdKg.first() } == 1.5 }
    }

    @Test
    fun thresholdIsAlsoShownInPoundsForAProfileInPounds() {
        runBlocking {
            profiles.add(
                Profile(
                    name = "Anna",
                    sex = Sex.FEMALE,
                    birthDate = LocalDate.parse("1992-05-01"),
                    heightCm = 165.0,
                    weightUnit = WeightUnit.POUND,
                    color = 0xFF3F51B5.toInt(),
                    createdAt = Instant.parse("2026-01-01T00:00:00Z"),
                ),
            )
        }
        openSettings()
        waitFor(hasText("±3.0 kg (±6.6 lb)"))
    }

    @Test
    fun bluetoothLogOpensFromSettings() {
        openSettings()
        compose.onNodeWithText("Bluetooth log").performScrollTo().performClick()
        waitFor(hasText("No frames recorded."))
        compose.onNodeWithContentDescription("Back").performClick()
        waitFor(hasText("Profiles"))
        assertEquals(3.0, runBlocking { settings.matchThresholdKg.first() }, 0.0)
    }

    @Test
    fun profilesAndScalesOpenFromSettings() {
        runBlocking {
            profiles.add(
                Profile(
                    name = "Anna",
                    sex = Sex.FEMALE,
                    birthDate = LocalDate.parse("1992-05-01"),
                    heightCm = 165.0,
                    color = 0xFF3F51B5.toInt(),
                    createdAt = Instant.parse("2026-01-01T00:00:00Z"),
                ),
            )
        }
        openSettings()
        // The profile's own settings, with its formulas
        compose.onNodeWithText("Anna").performClick()
        waitFor(hasText("Formulas"))
        compose.onNodeWithContentDescription("Back").performClick()
        waitFor(hasText("Profiles"))
        compose.onNodeWithText("My scales").performScrollTo().performClick()
        waitFor(hasText("My scales"))
        compose.onNodeWithContentDescription("Back").performClick()
        waitFor(hasText("New profile"))
    }

    private fun openSettings() {
        compose.onNodeWithContentDescription("Settings").performClick()
        waitFor(hasText("Profiles"))
    }

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntilAtLeastOneExists(matcher, timeoutMillis = 5_000)
}
