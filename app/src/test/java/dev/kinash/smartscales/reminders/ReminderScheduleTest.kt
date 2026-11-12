package dev.kinash.smartscales.reminders

import dev.kinash.smartscales.core.data.Reminder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class ReminderScheduleTest {
    private val zone = ZoneId.of("Europe/Kyiv")

    /** Wednesday, 11 November 2026. */
    private fun at(date: String, time: String) = ZonedDateTime.of(LocalDate.parse(date), LocalTime.parse(time), zone)

    @Test
    fun theNextAlarmIsTheReminderTimeOnADayItLooks() {
        val weekdays = Reminder(1, time = LocalTime.of(7, 30), onSchedule = true, days = setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY))
        // Wednesday morning: Friday
        assertEquals(at("2026-11-13", "07:30"), ReminderSchedule.next(at("2026-11-11", "06:00"), weekdays))
        // Friday after the time: next Monday
        assertEquals(at("2026-11-16", "07:30"), ReminderSchedule.next(at("2026-11-13", "07:30"), weekdays))
        // A reminder of a gap looks every day
        val gap = Reminder(1, time = LocalTime.of(20, 0), onGap = true)
        assertEquals(at("2026-11-11", "20:00"), ReminderSchedule.next(at("2026-11-11", "19:59"), gap))
        assertEquals(at("2026-11-12", "20:00"), ReminderSchedule.next(at("2026-11-11", "20:01"), gap))
        // Off, or on with no days: no alarm
        assertNull(ReminderSchedule.next(at("2026-11-11", "06:00"), Reminder(1)))
        assertNull(ReminderSchedule.next(at("2026-11-11", "06:00"), Reminder(1, onSchedule = true, days = emptySet())))
    }

    @Test
    fun theAlarmComesCloserInStepsThatAreEarlyHoweverLateTheyCome() {
        val now = Instant.parse("2026-11-11T20:00:00Z")
        val target = now.plus(Duration.ofDays(6))
        var at = now
        var steps = 0
        while (true) {
            val alarm = ReminderSchedule.alarmAt(at, target)
            if (alarm == target) break
            // As late as the system may deliver it: three quarters of the way to it past it
            at = alarm.plus(Duration.between(at, alarm).multipliedBy(3).dividedBy(4))
            assertTrue(at.isBefore(target))
            steps++
        }
        assertTrue(Duration.between(at, target) <= ReminderSchedule.LAST_STEP)
        assertTrue("steps $steps", steps in 1..20)
    }

    @Test
    fun oneAlarmServesAllAndRemindsEachWhoseTimeCame() {
        val early = Reminder(1, time = LocalTime.of(7, 30), onGap = true)
        val late = Reminder(2, time = LocalTime.of(7, 32), onGap = true)
        val evening = Reminder(3, time = LocalTime.of(20, 0), onGap = true)
        val all = listOf(evening, late, early)
        assertEquals(at("2026-11-11", "07:30"), ReminderSchedule.nearest(at("2026-11-11", "06:00"), all))
        assertNull(ReminderSchedule.nearest(at("2026-11-11", "06:00"), listOf(Reminder(4))))
        // Gone to 07:30 and come at 07:35: both morning ones
        assertEquals(listOf(late, early), ReminderSchedule.reached(at("2026-11-11", "07:30"), at("2026-11-11", "07:35"), all))
        assertEquals(listOf(early), ReminderSchedule.reached(at("2026-11-11", "07:30"), at("2026-11-11", "07:30"), all))
    }

    @Test
    fun aScheduledReminderIsNotForADayWithAWeighing() {
        val everyDay = Reminder(1, onSchedule = true)
        val today = LocalDate.parse("2026-11-11")
        assertEquals(ReminderDue.Schedule, ReminderSchedule.due(today, everyDay, LocalDate.parse("2026-11-10")))
        assertNull(ReminderSchedule.due(today, everyDay, today))
        // Never weighed: the schedule still reminds
        assertEquals(ReminderDue.Schedule, ReminderSchedule.due(today, everyDay, null))
        // Not one of its days
        assertNull(ReminderSchedule.due(today, everyDay.copy(days = setOf(DayOfWeek.MONDAY)), null))
    }

    @Test
    fun aGapRemindsOnceItIsLongEnoughAndIsSaidFirst() {
        val today = LocalDate.parse("2026-11-11")
        val gap = Reminder(1, onGap = true, gapDays = 7)
        assertNull(ReminderSchedule.due(today, gap, LocalDate.parse("2026-11-05")))
        assertEquals(ReminderDue.Gap(7), ReminderSchedule.due(today, gap, LocalDate.parse("2026-11-04")))
        assertEquals(ReminderDue.Gap(9), ReminderSchedule.due(today, gap.copy(onSchedule = true), LocalDate.parse("2026-11-02")))
        // A profile that never weighed has no gap to be reminded of
        assertNull(ReminderSchedule.due(today, gap, null))
    }
}
