package dev.kinash.smartscales.ui

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
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
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject

/**
 * WHO tables imported in the settings: a child's cards go by them, with WHO's cut-offs, until CDC is chosen again.
 * The tables are small made-up ones of WHO's shape: the real ones are not in the repository (CC BY-NC-SA).
 */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
class ChildNormsFlowTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    @Inject
    lateinit var profiles: ProfileRepository

    @Inject
    lateinit var measurements: MeasurementRepository

    @Before
    fun setUp() = hilt.inject()

    @Test
    fun importedWhoTablesPlaceAChildUntilCdcIsChosen() {
        // 10 years, 140 cm, 35 kg: BMI 17.9
        weighing(birthDate = "2015-11-02", heightCm = 140.0, weightKg = 35.0)
        waitFor(hasText("Percentile:", substring = true))
        openNorms()
        compose.onNodeWithText("Import the WHO tables first.").assertExists()
        compose.onNodeWithText("WHO").assertIsNotEnabled()

        // BMI with a median of 16 and S of 0.1: 17.9 is z +1.2, overweight by WHO from 5 years
        pickFiles(
            "bmi-boys-z-who-2007-exp.xlsx" to xlsx("bmi_boys_z_WHO 2007_exp", "Month", 61..228, l = 1.0, m = 16.0, s = 0.1),
            "notes.xlsx" to xlsx("Sheet1", "Month", 61..228, l = 1.0, m = 16.0, s = 0.1),
        )
        waitFor(hasText("Tables imported"))
        compose.onNodeWithText("• BMI, boys 5–19 years").assertExists()
        compose.onNodeWithText("notes.xlsx", substring = true).assertExists()
        compose.onNodeWithText("OK").performClick()
        waitFor(hasText("BMI: boys 5–19 years, girls none"))

        backToCards()
        waitFor(hasText("Percentile: 88, z +1.2 (WHO)"))
        compose.onNodeWithText("Overweight").assertExists()
        // No WHO weight and height tables: those stay on the CDC charts
        compose.onAllNodes(hasText("(CDC)", substring = true)).assertCountEquals(2)

        openNorms()
        compose.onNodeWithText("CDC 2000").performClick()
        backToCards()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("(WHO)", substring = true)).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun underTwoTheWhoTablesGivePercentiles() {
        weighing(birthDate = "2024-05-02", heightCm = 82.0, weightKg = 11.0)
        waitFor(hasText("Weight"))
        compose.onNodeWithText("Percentile:", substring = true).assertDoesNotExist()
        openNorms()
        // By day, as WHO's 0-5 tables are: a median of 11 kg is the 50th percentile
        pickFiles("wfa-boys-zscore-expanded-tables.xlsx" to xlsx("WFA_boys_z_exp", "Day", 0..1856, l = 1.0, m = 11.0, s = 0.1))
        waitFor(hasText("• Weight, boys 0–5 years"))
        compose.onNodeWithText("OK").performClick()
        backToCards()
        waitFor(hasText("Percentile: 50, z 0.0 (WHO)"))
    }

    private fun openNorms() {
        compose.waitUntilAtLeastOneExists(hasContentDescription("Settings"), timeoutMillis = 5_000)
        compose.onNodeWithContentDescription("Settings").performClick()
        waitFor(hasText("Children's norms"))
        compose.onNodeWithText("Children's norms").performScrollTo().performClick()
        waitFor(hasText("Import WHO files"))
    }

    private fun backToCards() {
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitUntilAtLeastOneExists(hasContentDescription("Settings"), timeoutMillis = 5_000)
    }

    /** Taps Import and answers the picker with [files], each a name and its bytes. */
    private fun pickFiles(vararg files: Pair<String, ByteArray>) {
        compose.onNodeWithText("Import WHO files").performScrollTo().performClick()
        compose.waitForIdle()
        val activity = shadowOf(compose.activity)
        val uris = files.map { (name, bytes) ->
            Uri.parse("content://test.files/$name").also { shadowOf(compose.activity.contentResolver).registerInputStream(it, bytes.inputStream()) }
        }
        val clip = ClipData.newRawUri(null, uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        val request = activity.nextStartedActivityForResult
        activity.receiveResult(request.intent, Activity.RESULT_OK, Intent().apply { clipData = clip })
    }

    /** An `.xlsx` in the shape of WHO's tables, with the same L, M and S at every age. */
    private fun xlsx(sheet: String, ageColumn: String, ages: IntRange, l: Double, m: Double, s: Double): ByteArray {
        val rows = ages.joinToString("") { age ->
            val r = age - ages.first + 2
            """<row r="$r"><c r="A$r"><v>$age</v></c><c r="B$r"><v>$l</v></c><c r="C$r"><v>$m</v></c><c r="D$r"><v>$s</v></c></row>"""
        }
        val header = listOf(ageColumn, "L", "M", "S")
        val parts = mapOf(
            "xl/workbook.xml" to """<workbook xmlns:r="r"><sheets><sheet name="$sheet" sheetId="1" r:id="rId1"/></sheets></workbook>""",
            "xl/_rels/workbook.xml.rels" to """<Relationships><Relationship Id="rId1" Target="worksheets/sheet1.xml"/></Relationships>""",
            "xl/sharedStrings.xml" to "<sst>" + header.joinToString("") { "<si><t>$it</t></si>" } + "</sst>",
            "xl/worksheets/sheet1.xml" to """<worksheet><sheetData><row r="1">""" +
                header.indices.joinToString("") { """<c r="${'A' + it}1" t="s"><v>$it</v></c>""" } + "</row>$rows</sheetData></worksheet>",
        )
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            parts.forEach { (name, xml) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(xml.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun weighing(birthDate: String, heightCm: Double, weightKg: Double) = runBlocking {
        val id = profiles.add(
            Profile(
                name = "Kid", sex = Sex.MALE, birthDate = LocalDate.parse(birthDate), heightCm = heightCm,
                color = 0xFF3F51B5.toInt(), createdAt = Instant.parse("2025-01-01T00:00:00Z"),
            ),
        )
        measurements.add(
            Measurement(
                profileId = id, takenAt = Instant.parse("2025-11-02T08:00:00Z"), zone = ZoneOffset.UTC,
                source = MeasurementSource.MANUAL, weightKg = weightKg, person = profiles.get(id)!!.snapshot(),
            ),
        )
    }

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntilAtLeastOneExists(matcher, timeoutMillis = 5_000)
}
