package dev.kinash.smartscales.importing

import dev.kinash.smartscales.core.model.LengthUnit
import dev.kinash.smartscales.core.model.ManualFields
import dev.kinash.smartscales.core.model.ScaleValues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale

class WeighingImportTest {
    private fun resource(name: String) = javaClass.classLoader!!.getResourceAsStream("import/$name")!!.readBytes()

    private fun csv(text: String) = Tables.read(text.toByteArray())

    @Test
    fun okokByItsLayoutWithTheYearFromThePeriod() {
        val table = Tables.read(resource("okok_ru.xls"))
        val layout = ImportLayout.detect(table)!!
        assertEquals(TableSource.OKOK, layout.source)
        assertEquals(DateFormat.MONTH_DAY, layout.dateFormat)
        assertEquals(LocalDate.parse("2025-12-30")..LocalDate.parse("2026-01-02"), layout.period)
        val read = layout.rows(table)
        assertEquals(listOf<Int>(), read.unreadable)
        assertEquals(
            listOf("2025-12-30T07:15", "2025-12-31T07:20", "2026-01-02T06:58").map(LocalDateTime::parse),
            read.rows.map { it.takenAt },
        )
        val first = read.rows.first()
        assertEquals(80.5, first.weightKg, 1e-9)
        assertNull(first.impedance)
        assertEquals(
            mapOf(
                ScaleValues.FAT_PERCENT to 21.3, ScaleValues.PROTEIN_PERCENT to 16.9, ScaleValues.MUSCLE_PERCENT to 44.1,
                ScaleValues.MUSCLE_KG to 35.5, ScaleValues.WATER_PERCENT to 55.2, ScaleValues.BMR_KCAL to 1702.0,
                ScaleValues.BONE_KG to 3.1, ScaleValues.SKELETAL_MUSCLE_PERCENT to 38.2, ScaleValues.BODY_AGE to 34.0,
                ScaleValues.LEAN_MASS_KG to 63.35,
            ),
            first.values,
        )
        // "--" where OKOK had no composition
        assertEquals(emptyMap<String, Double>(), read.rows[1].values)
    }

    @Test
    fun okokNewestFirstOverTwoYears() {
        val rows = listOf(
            listOf("Name"), listOf("Sex"),
            // Newest first, the period named from its first row
            listOf("2026-01-05 ~ 2024-11-20(412)"),
            List(22) { "h$it" }.toMutableList().also { it[2] = "Weight(lb)" },
            listOf("01-05", "08:00", "176.4"), listOf("12-25", "08:00", "177.0"), listOf("11-20", "08:00", "178.2"),
            listOf("12-01", "08:00", "179.0"), listOf("11-20", "08:00", "180.0"),
        )
        val table = Table(rows, Table.Kind.XLS)
        val layout = ImportLayout.detect(table)!!
        assertEquals(ImportWeightUnit.LB, layout.weightUnit)
        val read = layout.rows(table)
        assertEquals(
            listOf("2026-01-05", "2025-12-25", "2025-11-20", "2024-12-01", "2024-11-20").map(LocalDate::parse),
            read.rows.map { it.takenAt.toLocalDate() },
        )
        assertEquals(80.0, read.rows.first().weightKg, 0.02)
    }

    @Test
    fun openScaleByItsColumnKeys() {
        val table = csv(
            "\"DATE\",\"TIME\",\"WEIGHT\",\"BODY_FAT\",\"WATER\",\"MUSCLE\",\"LBM\",\"BONE\",\"WAIST\",\"HIPS\",\"VISCERAL_FAT\",\"BMR\",\"COMMENT\"\n" +
                "\"2026-01-03\",\"07:05\",\"81.2\",\"22.5\",\"55.0\",\"40.1\",\"62.9\",\"3.2\",\"86\",\"98\",\"9\",\"1720\",\"after run\"\n",
        )
        val layout = ImportLayout.detect(table)!!
        assertEquals(TableSource.OPENSCALE, layout.source)
        val row = layout.rows(table).rows.single()
        assertEquals(LocalDateTime.parse("2026-01-03T07:05"), row.takenAt)
        assertEquals(mapOf(ManualFields.WAIST_CM to 86.0, ManualFields.HIP_CM to 98.0), row.manualValues)
        assertEquals(22.5, row.values[ScaleValues.FAT_PERCENT])
        assertEquals(62.9, row.values[ScaleValues.LEAN_MASS_KG])
        assertEquals(9.0, row.values[ScaleValues.VISCERAL_FAT])
        assertEquals("after run", row.note)
    }

    @Test
    fun ourOwnTableTakesItsRawDataOnly() {
        val table = csv(
            "﻿Дата и время;Вес, фунты;Импеданс, Ом;Талия, дюймы;Жир, %;Метки;Заметка;Источник\r\n" +
                "2026-11-01 07:30:00;177,47;480,0;33,1;21,3;утро, натощак;бег;Весы\r\n",
        )
        val layout = ImportLayout.detect(table)!!
        assertEquals(TableSource.SMARTSCALES, layout.source)
        assertEquals(ImportWeightUnit.LB, layout.weightUnit)
        assertEquals(LengthUnit.INCH, layout.lengthUnit)
        val row = layout.rows(table).rows.single()
        assertEquals(80.5, row.weightKg, 0.01)
        assertEquals(480.0, row.impedance)
        assertEquals(33.1 * 2.54, row.manualValues.getValue(ManualFields.WAIST_CM), 1e-9)
        // Its indicators are worked out again here
        assertEquals(emptyMap<String, Double>(), row.values)
        assertEquals(listOf("утро", "натощак"), row.tags)
    }

    @Test
    fun anyTableWithDaysFirstAndBadRows() {
        val table = csv("Notes\nDatum;Gewicht\n03.01.2026;80,4\n13.01.2026 7:45;80,1\nnot a date;80\n14.01.2026;\n")
        val layout = ImportLayout.detect(table, Locale.GERMANY)!!
        assertEquals(TableSource.TABLE, layout.source)
        assertEquals(1, layout.headerRow)
        assertEquals(DateFormat.DAY_MONTH_DOT, layout.dateFormat)
        val read = layout.rows(table)
        assertEquals(listOf("2026-01-03T00:00", "2026-01-13T07:45").map(LocalDateTime::parse), read.rows.map { it.takenAt })
        assertEquals(listOf(4, 5), read.unreadable)
    }

    @Test
    fun monthFirstOnlyWhereNoOtherReading() {
        val table = csv("date,weight\n01/02/2026,80\n01/13/2026,80\n")
        assertEquals(DateFormat.MONTH_DAY_SLASH, ImportLayout.detect(table, Locale.UK)!!.dateFormat)
        val ambiguous = csv("date,weight\n01/02/2026,80\n")
        assertEquals(DateFormat.DAY_MONTH_SLASH, ImportLayout.detect(ambiguous, Locale.UK)!!.dateFormat)
        assertEquals(DateFormat.MONTH_DAY_SLASH, ImportLayout.detect(ambiguous, Locale.US)!!.dateFormat)
    }

    @Test
    fun aTableWithoutDateOrWeightIsNotWeighings() {
        assertNull(ImportLayout.detect(csv("name,age\nDmitry,35\n")))
    }
}
