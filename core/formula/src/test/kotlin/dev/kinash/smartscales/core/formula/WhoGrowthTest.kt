package dev.kinash.smartscales.core.formula

import dev.kinash.smartscales.core.model.Sex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * WHO tables are not in the repository (CC BY-NC-SA), so these build small `.xlsx` files of the same shape: a sheet
 * named like WHO's, `Day` or `Month`, `L`, `M`, `S` and then the z-score columns, which the app does not read.
 */
class WhoGrowthTest {
    @Test
    fun aTableByDayIsReadInMonths() {
        val file = xlsx("BFA_boys_z_exp", "Day", (0..1856 step 7).map { it.toDouble() to Lms(-0.3, 15.0, 0.09) })
        val table = WhoTable.readXlsx(file, "bfa-boys-zscore-expanded-tables.xlsx")
        assertEquals(GrowthIndicator.BMI, table.indicator)
        assertEquals(Sex.MALE, table.sex)
        assertEquals(0.0, table.rows.first().ageMonths, 0.0)
        assertEquals(1855 / WhoTable.DAYS_PER_MONTH, table.rows.last().ageMonths, 1e-9)
        assertEquals(15.0, table.rows.last().m, 0.0)
    }

    @Test
    fun aRenamedFileIsKnownByItsSheet() {
        // Excel saving it again writes the strings into the cells instead of a table of strings
        val file = xlsx("WFA_girls_z_exp", "Month", months(61..120, Lms(-0.5, 25.0, 0.13)), inlineStrings = true)
        val table = WhoTable.readXlsx(file, "download (3).xlsx")
        assertEquals(GrowthIndicator.WEIGHT to Sex.FEMALE, table.indicator to table.sex)
        assertEquals(60, table.rows.size)
    }

    @Test
    fun otherFilesAreNotTaken() {
        fun problem(bytes: ByteArray, name: String) = assertThrows(WhoTableException::class.java) { WhoTable.readXlsx(bytes, name) }.problem
        val bmi = months(61..228, Lms(-0.8, 18.0, 0.12))
        assertEquals(WhoTableProblem.NOT_XLSX, problem("Month,L,M,S".toByteArray(), "bmi-boys.csv"))
        // Weight-for-length is another indicator, and a name without the sex says too little
        assertEquals(WhoTableProblem.UNKNOWN_TABLE, problem(xlsx("WFL_boys_z_exp", "Day", bmi), "wfl-boys-zscore-expanded-tables.xlsx"))
        assertEquals(WhoTableProblem.UNKNOWN_TABLE, problem(xlsx("Sheet1", "Month", bmi), "bmi-z-who-2007-exp.xlsx"))
        assertEquals(WhoTableProblem.NO_LMS, problem(xlsx("bmi_boys", "Year", bmi), "bmi-boys-z-who-2007-exp.xlsx"))
        // Heights in a file named BMI
        assertEquals(
            WhoTableProblem.IMPLAUSIBLE,
            problem(xlsx("bmi_boys", "Month", months(61..228, Lms(1.0, 150.0, 0.04))), "bmi-boys-z-who-2007-exp.xlsx"),
        )
    }

    @Test
    fun partsThatUnpackTooBigAreNotRead() {
        val bmi = months(61..228, Lms(-0.8, 18.0, 0.12))
        val name = "bmi-boys-z-who-2007-exp.xlsx"
        // Parts the table does not need are skipped, however big they unpack
        val pictures = (1..3).map { "xl/media/image$it.xml" to 40L * 1024 * 1024 }
        assertEquals(168, WhoTable.readXlsx(xlsx("bmi_boys", "Month", bmi, padding = pictures), name).rows.size)
        // Sheets which together unpack past the limit are refused, though none is too big alone
        val sheets = (2..4).map { "xl/worksheets/sheet$it.xml" to 30L * 1024 * 1024 }
        val problem = assertThrows(WhoTableException::class.java) { WhoTable.readXlsx(xlsx("bmi_boys", "Month", bmi, padding = sheets), name) }
        assertEquals(WhoTableProblem.NOT_XLSX, problem.problem)
    }

    @Test
    fun aDocumentTypeIsRefused() {
        val file = xlsx("bmi_boys", "Month", months(61..228, Lms(-0.8, 18.0, 0.12)), doctype = """<!DOCTYPE sst [<!ENTITY x "M">]>""")
        val problem = assertThrows(WhoTableException::class.java) { WhoTable.readXlsx(file, "bmi-boys-z-who-2007-exp.xlsx") }
        assertEquals(WhoTableProblem.NOT_XLSX, problem.problem)
    }

    @Test
    fun aPhoneticReadingIsNotPartOfTheText() {
        val file = xlsx("bmi_boys", "Month", months(61..228, Lms(-0.8, 18.0, 0.12)), phonetic = true)
        assertEquals(168, WhoTable.readXlsx(file, "bmi-boys-z-who-2007-exp.xlsx").rows.size)
    }

    @Test
    fun whereTablesPickedTogetherMeetTheLaterOneKeepsTheAge() {
        // WHO's older BMI tables: 0-2 years by length and 2-5 by height, both with 24 months
        val byLength = WhoTable(GrowthIndicator.BMI, Sex.FEMALE, months(0..24, Lms(1.0, 16.0, 0.08)).toLms())
        val byHeight = WhoTable(GrowthIndicator.BMI, Sex.FEMALE, months(24..60, Lms(1.0, 15.5, 0.08)).toLms())
        for (picked in listOf(listOf(byLength, byHeight), listOf(byHeight, byLength))) {
            val chart = WhoGrowthCharts.EMPTY.with(picked).chart(GrowthIndicator.BMI)!!
            assertEquals(15.5, chart.lmsAt(Sex.FEMALE, 24.0)!!.m, 0.0)
            assertEquals(16.0, chart.lmsAt(Sex.FEMALE, 23.0)!!.m, 0.0)
        }
    }

    @Test
    fun theStandardsAndTheReferenceJoinAndALaterTableTakesItsAges() {
        val standards = WhoTable(GrowthIndicator.HEIGHT, Sex.MALE, months(0..60, Lms(1.0, 80.0, 0.04)).toLms())
        val reference = WhoTable(GrowthIndicator.HEIGHT, Sex.MALE, months(61..228, Lms(1.0, 140.0, 0.04)).toLms())
        val newer = WhoTable(GrowthIndicator.HEIGHT, Sex.MALE, months(0..12, Lms(1.0, 70.0, 0.04)).toLms())
        val charts = WhoGrowthCharts.EMPTY.with(standards).with(reference).with(newer)
        assertEquals(0.0..228.0, charts.ages(GrowthIndicator.HEIGHT, Sex.MALE))
        assertNull(charts.ages(GrowthIndicator.HEIGHT, Sex.FEMALE))
        val chart = charts.chart(GrowthIndicator.HEIGHT)!!
        assertEquals(70.0, chart.lmsAt(Sex.MALE, 6.0)!!.m, 0.0)
        assertEquals(80.0, chart.lmsAt(Sex.MALE, 30.0)!!.m, 0.0)
        assertEquals(140.0, chart.lmsAt(Sex.MALE, 100.0)!!.m, 0.0)
        // Kept as text and read back the same
        assertEquals(charts.tables, WhoGrowthCharts.parseCsv(charts.toCsv()).tables)
    }

    @Test
    fun weightAndBmiTailsAreRestrictedAsWhoComputesThem() {
        val rows = mapOf(Sex.MALE to months(61..228, Lms(-1.0, 18.0, 0.12)).toLms())
        val restricted = GrowthChart(rows, restrictedTails = true)
        val plain = GrowthChart(rows)
        val sd2 = plain.valueAt(Sex.MALE, 100.0, 2.0)!!
        val sd3 = plain.valueAt(Sex.MALE, 100.0, 3.0)!!
        // One step of the 2-to-3 distance past +3 is +4, however skewed the distribution is there
        assertEquals(4.0, restricted.zScore(Sex.MALE, 100.0, sd3 + (sd3 - sd2))!!, 1e-9)
        assertEquals(plain.zScore(Sex.MALE, 100.0, 20.0)!!, restricted.zScore(Sex.MALE, 100.0, 20.0)!!, 1e-12)
    }

    @Test
    fun childrenArePlacedOnTheWhoTablesWhereTheyCoverTheAge() {
        val who = WhoGrowthCharts.EMPTY
            .with(WhoTable(GrowthIndicator.BMI, Sex.MALE, months(0..228, Lms(1.0, 16.0, 0.1)).toLms()))
            .with(WhoTable(GrowthIndicator.WEIGHT, Sex.MALE, months(0..120, Lms(1.0, 10.0, 0.1)).toLms()))
            .with(WhoTable(GrowthIndicator.HEIGHT, Sex.MALE, months(0..228, Lms(1.0, 75.0, 0.05)).toLms()))
        // Under 2 there are percentiles now, by weight and length
        val baby = ChildGrowth.of(Sex.MALE, 12.0, 10.0, 75.0, null, 1, who)!!
        assertEquals(GrowthReference.WHO, baby.weight!!.reference)
        assertEquals(0.0, baby.weight!!.z, 1e-12)
        assertEquals(50.0, baby.weightPercentile!!, 1e-6)
        assertEquals(50.0, baby.heightPercentile!!, 1e-6)
        assertNull(baby.bmi)

        // BMI z of +1.5: a risk of overweight under 5, overweight from 5
        assertEquals(BmiStatus.RISK_OF_OVERWEIGHT, ChildGrowth.of(Sex.MALE, 30.0, 14.0, 90.0, 18.4, 1, who)!!.bmiStatus)
        assertEquals(BmiStatus.OVERWEIGHT, ChildGrowth.of(Sex.MALE, 100.0, 30.0, 130.0, 18.4, 1, who)!!.bmiStatus)
        assertEquals(BmiStatus.OVERWEIGHT_UNDER_5, ChildGrowth.of(Sex.MALE, 30.0, 14.0, 90.0, 20.0, 1, who)!!.bmiStatus)
        assertEquals(BmiStatus.THINNESS, ChildGrowth.of(Sex.MALE, 100.0, 30.0, 130.0, 12.5, 1, who)!!.bmiStatus)

        // WHO has no weight after 10 years: the CDC chart places it
        val older = ChildGrowth.of(Sex.MALE, 150.0, 40.0, 150.0, 17.8, 1, who)!!
        assertEquals(GrowthReference.CDC, older.weight!!.reference)
        assertEquals(GrowthReference.WHO, older.height!!.reference)
        // A girl has no WHO tables here: CDC for all of hers
        assertEquals(GrowthReference.CDC, ChildGrowth.of(Sex.FEMALE, 100.0, 25.0, 128.0, 15.3, 1, who)!!.bmi!!.reference)
    }

    private data class Lms(val l: Double, val m: Double, val s: Double)

    private fun months(range: IntRange, lms: Lms) = range.map { it.toDouble() to lms }

    private fun List<Pair<Double, Lms>>.toLms() = map { (age, lms) -> LmsRow(age, lms.l, lms.m, lms.s) }

    /**
     * An `.xlsx` with one sheet: the header, then the rows with a z-score column after L, M and S. [padding] adds parts
     * of that many spaces, [doctype] goes before the shared strings, and [phonetic] gives each a phonetic reading.
     */
    private fun xlsx(
        sheet: String,
        ageColumn: String,
        rows: List<Pair<Double, Lms>>,
        inlineStrings: Boolean = false,
        padding: List<Pair<String, Long>> = emptyList(),
        doctype: String = "",
        phonetic: Boolean = false,
    ): ByteArray {
        val header = listOf(ageColumn, "L", "M", "S", "SD0")
        fun text(column: Char, row: Int, index: Int) = if (inlineStrings) {
            """<c r="$column$row" t="inlineStr"><is><t>${header[index]}</t></is></c>"""
        } else {
            """<c r="$column$row" t="s"><v>$index</v></c>"""
        }
        val sheetXml = buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
            append("""<row r="1">""")
            header.indices.forEach { append(text('A' + it, 1, it)) }
            append("</row>")
            rows.forEachIndexed { i, (age, lms) ->
                val r = i + 2
                append("""<row r="$r"><c r="A$r"><v>$age</v></c><c r="B$r"><v>${lms.l}</v></c><c r="C$r"><v>${lms.m}</v></c>""")
                append("""<c r="D$r"><v>${lms.s}</v></c><c r="E$r"><v>${lms.m}</v></c></row>""")
            }
            append("</sheetData></worksheet>")
        }
        val parts = mapOf(
            "xl/workbook.xml" to """<?xml version="1.0"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" """ +
                """xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""" +
                """<sheet name="$sheet" sheetId="1" r:id="rId1"/></sheets></workbook>""",
            "xl/_rels/workbook.xml.rels" to """<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
                """<Relationship Id="rId1" Type="worksheet" Target="worksheets/sheet1.xml"/></Relationships>""",
            "xl/sharedStrings.xml" to """<?xml version="1.0"?>$doctype<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""" +
                header.joinToString("") { "<si><t>$it</t>" + (if (phonetic) "<rPh sb=\"0\" eb=\"1\"><t>yomi</t></rPh>" else "") + "</si>" } +
                "</sst>",
            "xl/worksheets/sheet1.xml" to sheetXml,
        )
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, xml) in parts) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(xml.toByteArray())
                zip.closeEntry()
            }
            val spaces = ByteArray(1024 * 1024) { ' '.code.toByte() }
            for ((name, size) in padding) {
                zip.putNextEntry(ZipEntry(name))
                repeat((size / spaces.size).toInt()) { zip.write(spaces) }
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
