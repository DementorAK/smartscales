package dev.kinash.smartscales.core.data

import dev.kinash.smartscales.core.data.db.entity.ProfileReminderEntity
import java.time.DayOfWeek
import java.time.LocalTime

/**
 * A profile's reminder to weigh (requirements §6.5): on its [days] at [time], skipped on a day the profile has a
 * weighing already ([onSchedule]); and at the same time once [gapDays] days have passed since the last weighing
 * ([onGap]), for those who only keep an eye on swings. Off by default.
 */
data class Reminder(
    val profileId: Long,
    val time: LocalTime = DEFAULT_TIME,
    val onSchedule: Boolean = false,
    val days: Set<DayOfWeek> = DayOfWeek.entries.toSet(),
    val onGap: Boolean = false,
    val gapDays: Int = DEFAULT_GAP_DAYS,
) {
    val enabled: Boolean get() = (onSchedule && days.isNotEmpty()) || onGap

    companion object {
        val DEFAULT_TIME: LocalTime = LocalTime.of(7, 30)
        const val DEFAULT_GAP_DAYS = 7
        val GAP_DAYS = 1..90
    }
}

internal fun ProfileReminderEntity.toReminder() = Reminder(
    profileId = profileId,
    time = LocalTime.ofSecondOfDay(minuteOfDay.coerceIn(0, 24 * 60 - 1) * 60L),
    onSchedule = onSchedule,
    days = DayOfWeek.entries.filter { days and (1 shl it.ordinal) != 0 }.toSet(),
    onGap = onGap,
    gapDays = gapDays.coerceIn(Reminder.GAP_DAYS),
)

internal fun Reminder.toEntity() = ProfileReminderEntity(
    profileId = profileId,
    minuteOfDay = time.hour * 60 + time.minute,
    onSchedule = onSchedule,
    days = days.fold(0) { bits, day -> bits or (1 shl day.ordinal) },
    onGap = onGap,
    gapDays = gapDays,
)
