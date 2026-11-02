package dev.kinash.smartscales.core.formula

import dev.kinash.smartscales.core.model.Sex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChildGrowthTest {
    /** Every row of the shipped CDC files: its L, M and S give back its own percentile columns. */
    @Test
    fun shippedTablesAgreeWithTheirOwnPercentiles() {
        for (name in listOf("bmiagerev.csv", "wtage.csv", "statage.csv")) {
            val text = CdcGrowthCharts::class.java.getResourceAsStream("cdc/$name")!!.use { it.readBytes().decodeToString() }
            val chart = GrowthChart.parseCdc(text)
            var header = emptyList<String>()
            var rows = 0
            for (line in text.lines().filter { it.isNotBlank() }) {
                val cells = line.split(',').map { it.trim() }
                if (cells[0] == "Sex") {
                    header = cells
                    continue
                }
                val sex = if (cells[0] == "1") Sex.MALE else Sex.FEMALE
                val age = cells[1].toDouble()
                for (i in 5 until cells.size) {
                    val expected = header[i].removePrefix("P").toDouble()
                    assertEquals("$name $sex $age ${header[i]}", expected, chart.percentile(sex, age, cells[i].toDouble())!!, 1e-4)
                }
                rows++
            }
            assertTrue(name, rows > 400)
            assertEquals(name, 24.0, chart.ageRange(Sex.MALE)!!.start, 0.0)
        }
    }

    @Test
    fun aChildAtTheMedianIsAtTheFiftiethPercentile() {
        val age = 10 * 12.0
        val height = CdcGrowthCharts.statureForAge.valueAt(Sex.FEMALE, age, 0.0)!!
        val bmi = CdcGrowthCharts.bmiForAge.valueAt(Sex.FEMALE, age, 0.0)!!
        val weight = bmi * (height / 100) * (height / 100)
        val growth = ChildGrowth.of(Sex.FEMALE, age, weight, height, bmi, 1)!!
        // BMI goes on the chart as shown, to one decimal, so it is near the median rather than exactly at it
        assertEquals(50.0, growth.bmiPercentile!!, 1.0)
        assertEquals(50.0, growth.heightPercentile!!, 1e-4)
        assertEquals(ZoneLevel.NORMAL, growth.bmiLevel)
    }

    @Test
    fun cdcWeightStatusByBmiPercentile() {
        val age = 8 * 12.0
        val height = 128.0
        fun levelAt(percentile: Double): ZoneLevel? {
            val z = inverseNormal(percentile / 100)
            val bmi = CdcGrowthCharts.bmiForAge.valueAt(Sex.MALE, age, z)!!
            return ChildGrowth.of(Sex.MALE, age, bmi * 1.28 * 1.28, height, bmi, 1)!!.bmiLevel
        }
        assertEquals(ZoneLevel.LOW, levelAt(4.0))
        assertEquals(ZoneLevel.NORMAL, levelAt(50.0))
        assertEquals(ZoneLevel.ELEVATED, levelAt(90.0))
        assertEquals(ZoneLevel.HIGH, levelAt(97.0))
    }

    @Test
    fun bmiComesFromTheFormulaSetAtItsPrecision() {
        val age = 10 * 12.0
        // The same weight and height, but the percentile follows the BMI the set gives and the decimals it shows
        val tenths = ChildGrowth.of(Sex.MALE, age, 35.0, 140.0, 17.86, 1)!!.bmiPercentile!!
        val whole = ChildGrowth.of(Sex.MALE, age, 35.0, 140.0, 17.86, 0)!!.bmiPercentile!!
        assertEquals(CdcGrowthCharts.bmiForAge.percentile(Sex.MALE, age, 17.9)!!, tenths, 1e-12)
        assertEquals(CdcGrowthCharts.bmiForAge.percentile(Sex.MALE, age, 18.0)!!, whole, 1e-12)
        // No BMI from the set: no BMI percentile, weight and height still placed
        val noBmi = ChildGrowth.of(Sex.MALE, age, 35.0, 140.0, null, 1)!!
        assertNull(noBmi.bmiPercentile)
        assertTrue(noBmi.heightPercentile != null)
    }

    @Test
    fun ageLimits() {
        // Adults get the adult indicators
        assertNull(ChildGrowth.of(Sex.MALE, 18 * 12.0, 70.0, 175.0, 22.9, 1))
        // Under 2: weight and height only, no percentiles
        val baby = ChildGrowth.of(Sex.FEMALE, 18.0, 10.0, 80.0, null, 1)!!
        assertNull(baby.bmiPercentile)
        assertNull(baby.weightPercentile)
        assertNull(baby.bmiLevel)
    }

    /** z for a probability, by bisection on [GrowthChart.normalCdf]: enough for picking test values. */
    private fun inverseNormal(p: Double): Double {
        var low = -6.0
        var high = 6.0
        repeat(100) {
            val mid = (low + high) / 2
            if (GrowthChart.normalCdf(mid) < p) low = mid else high = mid
        }
        return (low + high) / 2
    }
}
