package dev.kinash.smartscales.importing

import org.junit.Assert.assertEquals
import org.junit.Test

/** Tables from CSV and `.xls`; the `.xls` files are made by xlwt from made-up data, independently of the reader. */
class TablesTest {
    private fun resource(name: String) = javaClass.classLoader!!.getResourceAsStream("import/$name")!!.readBytes()

    @Test
    fun anOkokLikeWorkbookReadsInItsMiniStream() {
        val table = Tables.read(resource("okok_ru.xls"))
        assertEquals(Table.Kind.XLS, table.kind)
        assertEquals("Тест - Данные измерений", table.cell(0, 0))
        assertEquals("2025-12-30 ~ 2026-01-02(4)", table.cell(2, 0))
        assertEquals("Вес(kg)", table.cell(3, 2))
        assertEquals("Безжировая масса(kg)", table.cell(3, 21))
        assertEquals(listOf("12-30", "07:15", "80.5"), table.rows[4].take(3))
        assertEquals("--", table.cell(5, 5))
        assertEquals("63.28", table.cell(6, 21))
    }

    @Test
    fun sharedStringsOverManyRecordsAndNumbers() {
        val table = Tables.read(resource("many_strings.xls"))
        assertEquals(1201, table.rows.size)
        assertEquals(listOf("date", "weight", "note"), table.rows[0])
        for (i in 1..1200) {
            val note = if (i % 2 == 1) "заметка номер $i — " + "x".repeat(i % 40) else "note number $i ".repeat(2)
            assertEquals("row $i", note, table.cell(i, 2))
        }
        assertEquals("70.01", table.cell(1, 1))
        assertEquals("82", table.cell(1200, 1))
        // Only the first sheet
        assertEquals(0, table.rows.count { "not this sheet" in it })
    }

    @Test
    fun csvWithQuotesSemicolonsAndBom() {
        val text = "﻿Date;Weight, kg;Note\r\n2026-01-01 07:00;80,5;\"a; \"\"b\"\"\nc\"\r\n\r\n2026-01-02 07:00;80,4;\r\n"
        val table = Tables.read(text.toByteArray())
        assertEquals(Table.Kind.CSV, table.kind)
        assertEquals(listOf("Date", "Weight, kg", "Note"), table.rows[0])
        assertEquals(listOf("2026-01-01 07:00", "80,5", "a; \"b\"\nc"), table.rows[1])
        assertEquals(listOf("2026-01-02 07:00", "80,4", ""), table.rows[2])
        assertEquals(3, table.rows.size)
    }

    @Test
    fun csvInWindows1251AndWithCommas() {
        val table = Tables.read("Дата,Вес\n2026-01-01,80.5\n".toByteArray(charset("windows-1251")))
        assertEquals(listOf("Дата", "Вес"), table.rows[0])
        assertEquals(listOf("2026-01-01", "80.5"), table.rows[1])
    }
}
