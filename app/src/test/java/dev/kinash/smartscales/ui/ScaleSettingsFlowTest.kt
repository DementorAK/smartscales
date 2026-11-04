package dev.kinash.smartscales.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dev.kinash.smartscales.MainActivity
import dev.kinash.smartscales.core.data.Profile
import dev.kinash.smartscales.core.data.ScaleDisplayUnit
import dev.kinash.smartscales.core.data.repository.ProfileRepository
import dev.kinash.smartscales.core.data.repository.ScaleBindingRepository
import dev.kinash.smartscales.core.model.Sex
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject

/** The settings a GATT scale's driver honours: the unit on its display, its clock and the profiles it knows. */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
class ScaleSettingsFlowTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    @Inject
    lateinit var scales: ScaleBindingRepository

    @Inject
    lateinit var profiles: ProfileRepository

    @Before
    fun setUp() {
        hilt.inject()
    }

    @Test
    fun unitClockAndProfilesAreSaved() {
        val scaleId = runBlocking { scales.bind("AA:BB:CC:00:11:22", "AA:BB:CC:00:11:22", "OKOK_CLOUD_V4", "0x10", 1) }
        addProfile("Dmitry")
        val guest = addProfile("Guest")
        openScale("Chipsea OKOKCloud V4")

        compose.onNodeWithText("As in the profile on the main screen").performScrollTo().performClick()
        compose.onNodeWithText("Pounds").performClick()
        compose.onNodeWithText("Set the scale clock").performScrollTo().performClick()
        compose.onNodeWithText("Guest").performScrollTo().performClick()
        compose.onNodeWithText("Save").performClick()
        waitFor(hasText("My scales"))

        val saved = runBlocking { scales.get(scaleId)!! }
        assertEquals(ScaleDisplayUnit.POUND, saved.displayUnit)
        assertFalse(saved.syncTime)
        assertEquals(setOf(guest), runBlocking { scales.excludedProfiles(scaleId) })
    }

    @Test
    fun aScaleOnlyShowsWhatItsDriverHonours() {
        // OKOKCloud of another version takes no unit, and no OKOKCloud takes a person
        runBlocking { scales.bind("AA:BB:CC:00:11:22", "AA:BB:CC:00:11:22", "OKOK_CLOUD", "0x01", 1) }
        addProfile("Dmitry")
        openScale("Chipsea OKOKCloud")

        compose.onNodeWithText("Set the scale clock").assertExists()
        compose.onNodeWithText("Unit on the scale display").assertDoesNotExist()
        compose.onNodeWithText("Profiles the scale knows").assertDoesNotExist()
    }

    private fun openScale(title: String) {
        compose.onNodeWithText("Weigh", useUnmergedTree = true).performClick()
        waitFor(hasText("My scales"))
        compose.onNodeWithText("My scales").performClick()
        waitFor(hasText(title))
        compose.onNodeWithText(title).performClick()
        waitFor(hasText("On the scale"))
    }

    private fun addProfile(name: String): Long = runBlocking {
        profiles.add(
            Profile(
                name = name,
                sex = Sex.MALE,
                birthDate = LocalDate.parse("1990-10-28"),
                heightCm = 180.0,
                color = 0xFF3F51B5.toInt(),
                createdAt = Instant.parse("2026-01-01T00:00:00Z"),
            ),
        )
    }

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntilAtLeastOneExists(matcher, timeoutMillis = 5_000)
}
