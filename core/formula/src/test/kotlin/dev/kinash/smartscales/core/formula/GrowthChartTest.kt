package dev.kinash.smartscales.core.formula

import dev.kinash.smartscales.core.model.Sex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The LMS method on made-up tables; the CDC tables themselves are checked against their own percentile columns. */
class GrowthChartTest {
    private val chart = GrowthChart(
        mapOf(
            Sex.MALE to listOf(LmsRow(24.0, -2.0, 16.0, 0.08), LmsRow(36.0, -1.0, 15.0, 0.10)),
            Sex.FEMALE to listOf(LmsRow(24.0, 0.0, 15.5, 0.09), LmsRow(36.0, 0.0, 15.0, 0.09)),
        ),
    )

    @Test
    fun medianIsTheFiftiethPercentile() {
        assertEquals(0.0, chart.zScore(Sex.MALE, 24.0, 16.0)!!, 1e-12)
        assertEquals(50.0, chart.percentile(Sex.MALE, 24.0, 16.0)!!, 1e-6)
    }

    @Test
    fun zScoreAndValueAreInverse() {
        for (z in listOf(-2.0, -1.645, 0.5, 1.036, 1.645, 2.5)) {
            val value = chart.valueAt(Sex.MALE, 30.0, z)!!
            assertEquals(z, chart.zScore(Sex.MALE, 30.0, value)!!, 1e-9)
            // L = 0 uses the logarithm
            val female = chart.valueAt(Sex.FEMALE, 30.0, z)!!
            assertEquals(z, chart.zScore(Sex.FEMALE, 30.0, female)!!, 1e-9)
        }
    }

    @Test
    fun knownPercentilesOfTheNormalDistribution() {
        assertEquals(95.0, GrowthChart.normalCdf(1.6448536) * 100, 1e-4)
        assertEquals(85.0, GrowthChart.normalCdf(1.0364334) * 100, 1e-4)
        assertEquals(5.0, GrowthChart.normalCdf(-1.6448536) * 100, 1e-4)
        assertEquals(50.0, GrowthChart.normalCdf(0.0) * 100, 1e-6)
    }

    @Test
    fun lmsBetweenAgesIsInterpolated() {
        val middle = chart.lmsAt(Sex.MALE, 30.0)!!
        assertEquals(-1.5, middle.l, 1e-12)
        assertEquals(15.5, middle.m, 1e-12)
        assertEquals(0.09, middle.s, 1e-12)
    }

    @Test
    fun outsideTheChartThereIsNoValue() {
        assertNull(chart.zScore(Sex.MALE, 23.9, 16.0))
        assertNull(chart.zScore(Sex.MALE, 36.1, 16.0))
        assertNull(chart.zScore(Sex.MALE, 30.0, 0.0))
    }

    @Test
    fun cdcFileFormat() {
        // The layout of the CDC files with made-up numbers: a header, rows by sex, a repeated header in between
        val csv = """
            Sex,Agemos,L,M,S,P3,P5,P10,P25,P50,P75,P85,P90,P95,P97
            1,24,-2.0,16.0,0.08,0,0,0,0,0,0,0,0,0,0
            1,24.5,-1.9,15.9,0.081,0,0,0,0,0,0,0,0,0,0
            Sex,Agemos,L,M,S,P3,P5,P10,P25,P50,P75,P85,P90,P95,P97
            2,24,-1.0,15.5,0.085,0,0,0,0,0,0,0,0,0,0
            2,24.5,-1.1,15.4,0.086,0,0,0,0,0,0,0,0,0,0
        """.trimIndent()
        val parsed = GrowthChart.parseCdc(csv)
        assertEquals(24.0..24.5, parsed.ageRange(Sex.MALE))
        assertEquals(15.4, parsed.lmsAt(Sex.FEMALE, 24.5)!!.m, 0.0)
        assertEquals(-1.95, parsed.lmsAt(Sex.MALE, 24.25)!!.l, 1e-12)
    }
}
