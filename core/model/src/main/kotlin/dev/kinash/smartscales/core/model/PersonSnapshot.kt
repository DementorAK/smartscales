package dev.kinash.smartscales.core.model

import java.time.LocalDate
import java.time.Period
import java.time.temporal.ChronoUnit

/**
 * Person data stored with each measurement as it was at the time of weighing.
 *
 * The birth date is stored instead of the age, so the age can be computed with any precision:
 * whole years for adult equations, fractional months for children's growth percentiles.
 */
data class PersonSnapshot(
    val heightCm: Double,
    val sex: Sex,
    val birthDate: LocalDate,
) {
    init {
        require(heightCm > 0) { "Height must be positive: $heightCm" }
    }

    /** Completed years on [date]. */
    fun ageYearsAt(date: LocalDate): Int {
        require(!date.isBefore(birthDate)) { "Date $date is before birth date $birthDate" }
        return Period.between(birthDate, date).years
    }

    /** Age on [date] in months, with the current month as a fraction of its length (CDC LMS tables use months). */
    fun ageMonthsAt(date: LocalDate): Double {
        require(!date.isBefore(birthDate)) { "Date $date is before birth date $birthDate" }
        val wholeMonths = ChronoUnit.MONTHS.between(birthDate, date)
        val monthStart = birthDate.plusMonths(wholeMonths)
        val monthLength = ChronoUnit.DAYS.between(monthStart, monthStart.plusMonths(1))
        return wholeMonths + ChronoUnit.DAYS.between(monthStart, date).toDouble() / monthLength
    }
}
