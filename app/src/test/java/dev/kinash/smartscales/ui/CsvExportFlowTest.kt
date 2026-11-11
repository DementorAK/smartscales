package dev.kinash.smartscales.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
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
import dev.kinash.smartscales.core.data.repository.FormulaSetRepository
import dev.kinash.smartscales.core.data.repository.MeasurementRepository
import dev.kinash.smartscales.core.data.repository.ProfileRepository
import dev.kinash.smartscales.core.model.Impedance
import dev.kinash.smartscales.core.model.ManualFields
import dev.kinash.smartscales.core.model.MeasurementSource
import dev.kinash.smartscales.core.model.Sex
import dev.kinash.smartscales.core.model.WeightUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/** A profile's weighings saved as a table, in the phone's language and number format. */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
class CsvExportFlowTest {

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
                    weightUnit = WeightUnit.POUND, color = 0xFF3F51B5.toInt(), createdAt = Instant.parse("2026-01-01T00:00:00Z"),
                ),
            )
            val person = profiles.get(id)!!.snapshot()
            // A set from someone else, whose indicator is named like a spreadsheet formula
            sets.add("Odd", """{"format": 1, "id": "odd", "name": {"en": "Odd"}, "indicators": [{"id": "odd", "name": {"en": "=SUM(1)"}, "formula": "weight"}]}""", 1)
            measurements.add(
                Measurement(
                    profileId = id, takenAt = Instant.parse("2026-11-02T05:30:00Z"), zone = ZoneId.of("Europe/Kyiv"),
                    source = MeasurementSource.SCALE, weightKg = 80.0, person = person,
                    impedances = listOf(Impedance(Impedance.FOOT_TO_FOOT, 480.0)),
                    manualValues = mapOf(ManualFields.WAIST_CM to 84.0), note = "after a run; \"easy\"", tags = listOf("morning", "=1+2"),
                ),
            )
            measurements.add(
                Measurement(
                    profileId = id, takenAt = Instant.parse("2026-11-01T05:30:00Z"), zone = ZoneId.of("Europe/Kyiv"),
                    source = MeasurementSource.MANUAL, weightKg = 80.5, person = person,
                ),
            )
        }
    }

    @Test
    fun inEnglishCommasSeparateTheColumns() {
        val lines = export()
        val header = cells(lines.first(), ',')
        assertEquals("Date and time", header[0])
        // The profile weighs in pounds; a header with the separator is quoted
        assertEquals("Weight, lb", header[1])
        assertTrue(lines.first().startsWith("Date and time,\"Weight, lb\","))
        assertTrue(header[2].startsWith("Impedance, "))
        assertTrue("Waist, cm" in header)
        assertTrue("Body mass index, kg/m²" in header)
        // Oldest first, in the time zone of the weighing
        val older = cells(lines[1], ',')
        val newer = cells(lines[2], ',')
        assertEquals(listOf("2026-11-01 07:30:00", "177.47", ""), older.take(3))
        assertEquals(listOf("2026-11-02 07:30:00", "176.37", "480.0"), newer.take(3))
        assertEquals("84.0", newer[header.indexOf("Waist, cm")])
        // A note with quotes is quoted, its quotes doubled, and reads back as written
        assertTrue("\"after a run; \"\"easy\"\"\"" in lines[2])
        assertEquals("after a run; \"easy\"", newer[header.indexOf("Note")])
        // Text that a spreadsheet would run as a formula gets an apostrophe; so does a header a set named so
        // (the cell starts with the formula only when that tag comes first)
        val tags = newer[header.indexOf("Tags")]
        assertTrue(tags, tags == "'=1+2, morning" || tags == "morning, =1+2")
        assertTrue("'=SUM(1), kg" !in header)
        assertTrue("'=SUM(1)" in header)
        // The group is named after itself, not after the variant of the oldest weighing
        assertTrue("Body fat, %" in header)
        assertEquals("Scale", newer.last())
        assertEquals("By hand", older.last())
    }

    @Test
    @Config(qualifiers = "ru")
    fun inRussianSemicolonsAndDecimalCommas() {
        val lines = export()
        assertTrue(lines.first().startsWith("Дата и время;Вес, фунты;"))
        assertTrue(lines[1].startsWith("2026-11-01 07:30:00;177,47;"))
        assertTrue(lines[2].endsWith(";Весы"))
    }

    /** Saves the table through the screen and gives its lines, without the byte order mark. */
    private fun export(): List<String> {
        compose.waitUntilAtLeastOneExists(hasContentDescription(text("Settings", "Настройки")), timeoutMillis = 5_000)
        compose.onNodeWithContentDescription(text("Settings", "Настройки")).performClick()
        val title = text("Weighings as a table", "Взвешивания таблицей")
        waitFor(hasText(title))
        compose.onNodeWithText(title).performScrollTo().performClick()
        val save = text("Save as CSV", "Сохранить в CSV")
        waitFor(hasText(save))
        compose.onNodeWithText(save).performClick()
        compose.waitForIdle()
        val uri = Uri.parse("content://test.files/table.csv")
        val file = ByteArrayOutputStream()
        shadowOf(compose.activity.contentResolver).registerOutputStream(uri, file)
        val activity = shadowOf(compose.activity)
        val request = activity.nextStartedActivityForResult
        activity.receiveResult(request.intent, Activity.RESULT_OK, Intent().setData(uri))
        waitFor(hasText(text("Table saved", "Таблица сохранена")))
        val content = file.toByteArray().decodeToString()
        assertTrue(content.startsWith("\uFEFF"))
        return content.removePrefix("\uFEFF").split("\r\n").filter { it.isNotEmpty() }
    }

    /** The cells of one CSV line, quotes undone. */
    private fun cells(line: String, separator: Char): List<String> {
        val out = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quoted && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { cell.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == separator && !quoted -> { out += cell.toString(); cell.clear() }
                else -> cell.append(c)
            }
            i++
        }
        out += cell.toString()
        return out
    }

    private fun text(english: String, russian: String) =
        if (compose.activity.resources.configuration.locales[0].language == "ru") russian else english

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntilAtLeastOneExists(matcher, timeoutMillis = 10_000)
}
