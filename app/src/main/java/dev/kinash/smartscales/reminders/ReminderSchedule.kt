package dev.kinash.smartscales.reminders

import dev.kinash.smartscales.core.data.Reminder
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/** Why a reminder goes off today: the schedule, or [days] gone by without a weighing. */
sealed interface ReminderDue {
    data object Schedule : ReminderDue

    data class Gap(val days: Long) : ReminderDue
}

/** When reminders go off and whether they have something to say then (requirements §6.5). */
object ReminderSchedule {
    /**
     * The next time after [now] the reminder looks at the weighings: [Reminder.time] on a day of its schedule,
     * or on any day when it reminds of a gap; null when it is off.
     */
    fun next(now: ZonedDateTime, reminder: Reminder): ZonedDateTime? {
        if (!reminder.enabled) return null
        // A week ahead always has a day of the schedule; a day that falls in a clock change gets the shifted time
        for (offset in 0L..7L) {
            val day = now.toLocalDate().plusDays(offset)
            if (!looksOn(day, reminder)) continue
            val at = day.atTime(reminder.time).atZone(now.zone)
            if (at.isAfter(now)) return at
        }
        return null
    }

    /** The nearest time after [now] any of [reminders] looks at the weighings. */
    fun nearest(now: ZonedDateTime, reminders: List<Reminder>): ZonedDateTime? = reminders.mapNotNull { next(now, it) }.minOrNull()

    /**
     * The reminders whose time came from [since] to [now]: the time the alarm went to, and the time it came, which
     * may be later and past the time of another.
     */
    fun reached(since: ZonedDateTime, now: ZonedDateTime, reminders: List<Reminder>): List<Reminder> =
        reminders.filter { reminder -> next(since.minusNanos(1), reminder)?.isAfter(now) == false }

    /**
     * When to set the alarm on the way to [target]. An alarm allowed in Doze has no window of its own: the system
     * may deliver it as late as three quarters of the time to it past it, so one set a day ahead could come in the
     * evening. Halfway there it is still early, however late it comes, and looks again from closer; from
     * [LAST_STEP] away the alarm is the target itself, late by about ten minutes at most.
     */
    fun alarmAt(now: Instant, target: Instant): Instant {
        val left = Duration.between(now, target)
        return if (left <= LAST_STEP) target else now.plus(left.dividedBy(2))
    }

    val LAST_STEP: Duration = Duration.ofMinutes(13)

    private fun looksOn(day: LocalDate, reminder: Reminder): Boolean =
        reminder.onGap || (reminder.onSchedule && day.dayOfWeek in reminder.days)

    /**
     * What to remind of on [today], given the day of the profile's last weighing: nothing when it weighed today
     * already. A gap counts from the last weighing; a profile that never weighed has no gap to be reminded of, and
     * the gap is said rather than the schedule when both are due.
     */
    fun due(today: LocalDate, reminder: Reminder, lastWeighing: LocalDate?): ReminderDue? {
        if (lastWeighing != null && !lastWeighing.isBefore(today)) return null
        val gap = lastWeighing?.let { ChronoUnit.DAYS.between(it, today) }
        if (reminder.onGap && gap != null && gap >= reminder.gapDays) return ReminderDue.Gap(gap)
        if (reminder.onSchedule && today.dayOfWeek in reminder.days) return ReminderDue.Schedule
        return null
    }
}
