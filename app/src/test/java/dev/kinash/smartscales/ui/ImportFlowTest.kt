package dev.kinash.smartscales.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
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
import dev.kinash.smartscales.core.protocol.ScaleValues
import kotlinx.coroutines.flow.first
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
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject

/** Weighings imported from OKOK's `.xls` and from a table whose column is set by hand. */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
class ImportFlowTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    @Inject
    lateinit var profiles: ProfileRepository

    @Inject
    lateinit var measurements: MeasurementRepository

    private var profileId = 0L

    @Before
    fun setUp() {
        hilt.inject()
        runBlocking {
            profileId = profiles.add(
                Profile(
                    name = "Dmitry", sex = Sex.MALE, birthDate = LocalDate.parse("1990-11-02"), heightCm = 180.0,
                    color = 0xFF3F51B5.toInt(), createdAt = Instant.parse("2026-01-01T00:00:00Z"),
                ),
            )
            // The same weighing as the file's second row: it came from the scale before
            val zone = ZoneId.systemDefault()
            measurements.add(
                Measurement(
                    profileId = profileId, takenAt = LocalDateTime.parse("2025-12-31T07:20:20").atZone(zone).toInstant(), zone = zone,
                    source = MeasurementSource.SCALE, weightKg = 80.3, person = profiles.get(profileId)!!.snapshot(),
                ),
            )
        }
    }

    @Test
    fun okokExportAddsWhatIsNotThereYet() {
        openImport()
        pick(javaClass.classLoader!!.getResourceAsStream("import/okok_ru.xls")!!.readBytes())
        waitFor(hasText("okok_ru.xls · OKOK International", substring = true))
        waitFor(hasText("2 weighings will be added", substring = true))
        compose.onNodeWithText("1 is in the profile already and is left out").assertExists()
        compose.onNodeWithText("Import").performScrollTo().performClick()
        waitFor(hasText("2 weighings added"))

        val stored = runBlocking { measurements.observeHistory(profileId).first() }.sortedBy { it.takenAt }
        assertEquals(3, stored.size)
        val first = stored.first()
        assertEquals(MeasurementSource.IMPORT, first.source)
        assertEquals(80.5, first.weightKg, 1e-9)
        assertEquals(21.3, first.importedValues[ScaleValues.FAT_PERCENT])
        assertEquals(LocalDate.parse("2026-01-02"), stored.last().localDate)
    }

    @Test
    fun aColumnTheAppDoesNotKnowIsSetByHand() {
        openImport()
        pick("Datum;Gewicht;Kommentar\n03.01.2026 07:45;80,4;nach dem Laufen\n".toByteArray())
        waitFor(hasText("1 weighing will be added", substring = true))
        compose.onNodeWithTag("import_column_2").performScrollTo().performClick()
        waitFor(hasText("Note") and hasClickAction())
        compose.onNode(hasText("Note") and hasClickAction()).performScrollTo().performClick()
        // The menu closed, and no column is left out any more
        compose.waitUntilDoesNotExist(hasText("Do not import"), timeoutMillis = 10_000)
        compose.onNodeWithText("Import").performScrollTo().performClick()
        waitFor(hasText("1 weighing added"))
        val stored = runBlocking { measurements.observeHistory(profileId).first() }.first { it.source == MeasurementSource.IMPORT }
        assertEquals("nach dem Laufen", stored.note)
        assertEquals(LocalDateTime.parse("2026-01-03T07:45"), stored.takenAt.atZone(stored.zone).toLocalDateTime())
    }

    private fun openImport() {
        compose.waitUntilAtLeastOneExists(hasContentDescription("Settings"), timeoutMillis = 5_000)
        compose.onNodeWithContentDescription("Settings").performClick()
        waitFor(hasText("Import weighings"))
        compose.onNodeWithText("Import weighings").performScrollTo().performClick()
        waitFor(hasText("Choose a file"))
    }

    private fun pick(bytes: ByteArray) {
        compose.onNodeWithText("Choose a file").performClick()
        compose.waitForIdle()
        val name = if (bytes[0] == 0xD0.toByte()) "okok_ru.xls" else "table.csv"
        val uri = Uri.parse("content://test.files/$name")
        shadowOf(compose.activity.contentResolver).registerInputStream(uri, bytes.inputStream())
        val activity = shadowOf(compose.activity)
        val request = activity.nextStartedActivityForResult
        activity.receiveResult(request.intent, Activity.RESULT_OK, Intent().setData(uri))
    }

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntilAtLeastOneExists(matcher, timeoutMillis = 10_000)
}
