package dev.kinash.smartscales.ui

import android.Manifest
import android.app.Application
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dev.kinash.smartscales.MainActivity
import dev.kinash.smartscales.core.data.Profile
import dev.kinash.smartscales.core.data.Reminder
import dev.kinash.smartscales.core.data.repository.ProfileRepository
import dev.kinash.smartscales.core.model.Sex
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject

/** A reminder turned on in the profile and kept with it. */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
class ReminderFlowTest {
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    @Inject
    lateinit var profiles: ProfileRepository

    private var profileId = 0L

    @Before
    fun setUp() {
        hilt.inject()
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        profileId = runBlocking {
            profiles.add(
                Profile(
                    name = "Dmitry", sex = Sex.MALE, birthDate = LocalDate.parse("1990-11-02"), heightCm = 180.0,
                    color = 0, createdAt = Instant.parse("2026-01-01T00:00:00Z"),
                ),
            )
        }
    }

    @Test
    fun aReminderOfAGapIsSavedWithTheProfile() {
        compose.waitUntilAtLeastOneExists(hasContentDescription("Settings"), timeoutMillis = 5_000)
        compose.openProfile("Dmitry")
        // Off to begin with
        assertEquals(Reminder(profileId), runBlocking { profiles.getReminder(profileId) })
        compose.onNodeWithTag("reminder_gap").performScrollTo().performClick()
        compose.waitUntilAtLeastOneExists(hasText("Days without weighing"), timeoutMillis = 5_000)
        compose.onNodeWithTag("reminder_gap_days").performScrollTo().performTextClearance()
        compose.onNodeWithTag("reminder_gap_days").performTextInput("0")
        compose.onNodeWithText("Save").performClick()
        // Out of range: the form stays and says so
        compose.waitUntilAtLeastOneExists(hasText("From 1 to 90 days"), timeoutMillis = 5_000)
        compose.onNodeWithTag("reminder_gap_days").performTextClearance()
        compose.onNodeWithTag("reminder_gap_days").performTextInput("5")
        compose.onNodeWithText("Save").performClick()
        compose.backFromSettings()
        val saved = runBlocking { profiles.getReminder(profileId) }
        assertEquals(true, saved.onGap)
        assertEquals(5, saved.gapDays)
        assertEquals(false, saved.onSchedule)
    }
}
