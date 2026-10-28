package dev.kinash.smartscales.core.model

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class PersonSnapshotTest {

    private fun person(birthDate: String) = PersonSnapshot(170.0, Sex.FEMALE, LocalDate.parse(birthDate))

    @Test
    fun ageInYearsChangesOnBirthday() {
        val person = person("1990-06-15")
        assertEquals(35, person.ageYearsAt(LocalDate.parse("2026-06-14")))
        assertEquals(36, person.ageYearsAt(LocalDate.parse("2026-06-15")))
    }

    @Test
    fun leapDayBirthday() {
        val person = person("2008-02-29")
        assertEquals(17, person.ageYearsAt(LocalDate.parse("2026-02-28")))
        assertEquals(18, person.ageYearsAt(LocalDate.parse("2026-03-01")))
    }

    @Test
    fun ageInMonthsIsFractional() {
        val person = person("2020-01-10")
        assertEquals(0.0, person.ageMonthsAt(LocalDate.parse("2020-01-10")), 1e-9)
        assertEquals(24.0, person.ageMonthsAt(LocalDate.parse("2022-01-10")), 1e-9)
        // 2022-01-10 + 15 days of a 31-day month
        assertEquals(24 + 15.0 / 31, person.ageMonthsAt(LocalDate.parse("2022-01-25")), 1e-9)
    }

    @Test
    fun ageInMonthsAtEndOfMonth() {
        val person = person("2020-01-31")
        // 2020-02-29 is one whole month after 2020-01-31
        assertEquals(1.0, person.ageMonthsAt(LocalDate.parse("2020-02-29")), 1e-9)
    }

    @Test(expected = IllegalArgumentException::class)
    fun dateBeforeBirthIsRejected() {
        person("2020-01-10").ageYearsAt(LocalDate.parse("2019-12-31"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonPositiveHeightIsRejected() {
        PersonSnapshot(0.0, Sex.MALE, LocalDate.parse("1990-01-01"))
    }

    @Test
    fun sexFormulaValue() {
        assertEquals(1, Sex.MALE.formulaValue)
        assertEquals(0, Sex.FEMALE.formulaValue)
    }
}
