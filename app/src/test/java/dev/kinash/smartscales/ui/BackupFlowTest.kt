package dev.kinash.smartscales.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
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
import dev.kinash.smartscales.core.data.repository.FormulaSetRepository
import dev.kinash.smartscales.core.data.repository.MeasurementRepository
import dev.kinash.smartscales.core.data.repository.ProfileRepository
import dev.kinash.smartscales.core.model.MeasurementSource
import dev.kinash.smartscales.core.model.Sex
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import javax.inject.Inject

/** A backup with a password written into a file, and the phone's data replaced with it again. */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
class BackupFlowTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    @Inject
    lateinit var profiles: ProfileRepository

    @Inject
    lateinit var measurements: MeasurementRepository

    @Inject
    lateinit var sets: FormulaSetRepository

    @Before
    fun setUp() {
        hilt.inject()
        runBlocking {
            val id = profiles.add(
                Profile(
                    name = "Dmitry", sex = Sex.MALE, birthDate = LocalDate.parse("1990-11-02"), heightCm = 180.0,
                    color = 0xFF3F51B5.toInt(), createdAt = Instant.parse("2026-01-01T00:00:00Z"),
                ),
            )
            measurements.add(
                Measurement(
                    profileId = id, takenAt = Instant.parse("2025-11-02T08:00:00Z"), zone = ZoneOffset.UTC,
                    source = MeasurementSource.MANUAL, weightKg = 80.0, person = profiles.get(id)!!.snapshot(),
                ),
            )
            sets.add("Frame", """{"format": 1, "id": "frame", "name": {"en": "Frame"}}""", 1)
        }
    }

    @Test
    fun aBackupWithAPasswordBringsTheDataBack() {
        openBackup()
        compose.onNodeWithTag("backup_password").performTextInput("secret")
        compose.onNodeWithTag("backup_password_repeat").performTextInput("secret")
        val file = ByteArrayOutputStream()
        val uri = Uri.parse("content://test.files/backup.json")
        clickButton("Create a backup")
        compose.waitForIdle()
        shadowOf(compose.activity.contentResolver).registerOutputStream(uri, file)
        answer(uri)
        waitFor(hasText("Backup saved"))
        val written = file.toByteArray()
        assertTrue(written.isNotEmpty())
        // Locked: the names are not in the file
        assertTrue("Dmitry" !in written.decodeToString())

        // The snackbar would take the tap meant for the button under it
        compose.waitUntilDoesNotExist(hasText("Backup saved"), timeoutMillis = 10_000)
        // The data changes after the backup
        runBlocking { sets.delete(sets.observeAll().first().single().id) }

        clickButton("Restore from a backup")
        compose.waitForIdle()
        shadowOf(compose.activity.contentResolver).registerInputStream(uri, written.inputStream())
        answer(uri)
        waitFor(hasText("Backup with a password"))
        compose.onNodeWithTag("backup_unlock").performTextInput("wrong")
        compose.onNodeWithText("Open").performClick()
        waitFor(hasText("The password does not open it"))
        compose.onNodeWithTag("backup_unlock").performTextClearance()
        compose.onNodeWithTag("backup_unlock").performTextInput("secret")
        compose.onNodeWithText("Open").performClick()
        waitFor(hasText("Replace all data?"))
        compose.onNodeWithText("1 profile · 1 weighing · 0 scales · 1 formula set").assertExists()
        compose.onNodeWithText("Replace").performClick()
        waitFor(hasText("Data restored"))
        assertEquals(listOf("Frame"), runBlocking { sets.observeAll().first().map { it.name } })
        assertEquals(listOf("Dmitry"), runBlocking { profiles.observeProfiles().first().map { it.name } })
    }

    @Test
    fun aFileThatIsNotABackupChangesNothing() {
        openBackup()
        val uri = Uri.parse("content://test.files/other.json")
        clickButton("Restore from a backup")
        compose.waitForIdle()
        shadowOf(compose.activity.contentResolver).registerInputStream(uri, """{"format": 1, "id": "frame"}""".byteInputStream())
        answer(uri)
        waitFor(hasText("This is not a SmartScales backup, or it is damaged"))
        assertEquals(listOf("Frame"), runBlocking { sets.observeAll().first().map { it.name } })
    }

    private fun openBackup() {
        compose.waitUntilAtLeastOneExists(hasContentDescription("Settings"), timeoutMillis = 5_000)
        compose.onNodeWithContentDescription("Settings").performClick()
        waitFor(hasText("Backup"))
        compose.onNodeWithText("Backup").performScrollTo().performClick()
        waitFor(hasText("Restore from a backup"))
    }

    /** The button, not the section title of the same text above it. */
    private fun clickButton(text: String) {
        compose.onAllNodesWithText(text).onLast().performScrollTo().performClick()
    }

    private fun answer(uri: Uri) {
        val activity = shadowOf(compose.activity)
        val request = activity.nextStartedActivityForResult
        activity.receiveResult(request.intent, Activity.RESULT_OK, Intent().setData(uri))
    }

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntilAtLeastOneExists(matcher, timeoutMillis = 10_000)
}
