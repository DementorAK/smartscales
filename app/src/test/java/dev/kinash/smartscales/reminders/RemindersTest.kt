package dev.kinash.smartscales.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dev.kinash.smartscales.core.data.Measurement
import dev.kinash.smartscales.core.data.Profile
import dev.kinash.smartscales.core.data.Reminder
import dev.kinash.smartscales.core.data.repository.MeasurementRepository
import dev.kinash.smartscales.core.data.repository.ProfileRepository
import dev.kinash.smartscales.core.model.MeasurementSource
import dev.kinash.smartscales.core.model.Sex
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject

/** Alarms set by the reminders, and what goes to the shade when one goes off. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
class RemindersTest {
    @get:Rule
    val hilt = HiltAndroidRule(this)

    @Inject
    lateinit var reminders: Reminders

    @Inject
    lateinit var profiles: ProfileRepository

    @Inject
    lateinit var measurements: MeasurementRepository

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val notifications get() = shadowOf(context.getSystemService(NotificationManager::class.java))
    private var profileId = 0L
    private var otherId = 0L

    @Before
    fun setUp() {
        hilt.inject()
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        profileId = runBlocking {
            profiles.add(
                Profile(
                    name = "Dmitry", sex = Sex.MALE, birthDate = LocalDate.parse("1990-11-02"), heightCm = 180.0,
                    color = 0, createdAt = Instant.parse("2026-01-01T00:00:00Z"),
                ),
            )
        }
        otherId = runBlocking {
            profiles.add(
                Profile(
                    name = "Anna", sex = Sex.FEMALE, birthDate = LocalDate.parse("1992-03-04"), heightCm = 165.0,
                    color = 0, createdAt = Instant.parse("2026-01-01T00:00:00Z"),
                ),
            )
        }
    }

    private fun weighed(at: Instant, profileId: Long = this.profileId) = runBlocking {
        measurements.add(
            Measurement(
                profileId = profileId, takenAt = at, zone = ZoneId.systemDefault(), source = MeasurementSource.MANUAL,
                weightKg = 80.0, person = profiles.get(profileId)!!.snapshot(),
            ),
        )
    }

    @Test
    fun anAlarmIsSetAtTheReminderTimeAndRemovedWhenItIsOff() {
        val alarms = shadowOf(context.getSystemService(AlarmManager::class.java))
        val time = LocalTime.now().plusHours(1).withSecond(0).withNano(0)
        reminders.schedule(listOf(Reminder(profileId, time = time, onGap = true)))
        val alarm = alarms.scheduledAlarms.single()
        assertEquals(AlarmManager.RTC_WAKEUP, alarm.type)
        assertTrue(alarm.isAllowWhileIdle)
        // An hour away: halfway first, short of the time
        val at = Instant.ofEpochMilli(alarm.triggerAtTime).atZone(ZoneId.systemDefault()).toLocalTime()
        assertTrue(at.isBefore(time))
        reminders.schedule(listOf(Reminder(profileId)))
        assertTrue(alarms.scheduledAlarms.isEmpty())
    }

    @Test
    fun oneAlarmGoesToTheNearestTimeOfAllProfiles() {
        val alarms = shadowOf(context.getSystemService(AlarmManager::class.java))
        val soon = LocalTime.now().plusMinutes(10).withSecond(0).withNano(0)
        val later = soon.plusHours(2)
        reminders.schedule(listOf(Reminder(profileId, time = later, onGap = true), Reminder(otherId, time = soon, onGap = true)))
        val alarm = alarms.scheduledAlarms.single()
        // Within the last step: the time itself
        assertEquals(soon, Instant.ofEpochMilli(alarm.triggerAtTime).atZone(ZoneId.systemDefault()).toLocalTime())
    }

    @Test
    fun profilesWithOneTimeAreRemindedByOneAlarm() = runBlocking {
        val time = LocalTime.now().minusMinutes(3).withSecond(0).withNano(0)
        profiles.setReminder(Reminder(profileId, time = time, onGap = true, gapDays = 3))
        profiles.setReminder(Reminder(otherId, time = time, onGap = true, gapDays = 3))
        weighed(Instant.now().minus(Duration.ofDays(5)))
        weighed(Instant.now().minus(Duration.ofDays(4)), otherId)
        // Late by three minutes, as Doze has it
        reminders.fire(LocalDate.now().atTime(time).atZone(ZoneId.systemDefault()).toInstant())
        val texts = notifications.allNotifications.map { it.extras.getCharSequence("android.text").toString() }.sorted()
        assertEquals(listOf("Anna, no weighing for 4 days", "Dmitry, no weighing for 5 days"), texts)
    }

    @Test
    fun aDeletedProfileTakesItsAlarmAlong() = runBlocking {
        val alarms = shadowOf(context.getSystemService(AlarmManager::class.java))
        reminders.start()
        profiles.setReminder(Reminder(profileId, onGap = true))
        waitFor { alarms.scheduledAlarms.size == 1 }
        profiles.delete(profileId)
        waitFor { alarms.scheduledAlarms.isEmpty() }
    }

    private fun waitFor(condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < until) { "Timed out" }
            Thread.sleep(20)
        }
    }

    @Test
    fun aDueReminderGoesToTheShadeAndNothingWhenTheProfileWeighedToday() = runBlocking {
        val time = LocalTime.now().minusMinutes(1).withSecond(0).withNano(0)
        val target = LocalDate.now().atTime(time).atZone(ZoneId.systemDefault()).toInstant()
        profiles.setReminder(Reminder(profileId, time = time, onGap = true, gapDays = 3))
        weighed(Instant.now().minus(Duration.ofDays(5)))
        reminders.fire(target)
        val shown = notifications.allNotifications.single()
        val text = shown.extras.getCharSequence("android.text").toString()
        assertEquals("Dmitry, no weighing for 5 days", text)
        context.getSystemService(NotificationManager::class.java).cancelAll()

        weighed(Instant.now())
        reminders.fire(target)
        assertTrue(notifications.allNotifications.isEmpty())
        // The next alarm is set either way
        assertNotNull(shadowOf(context.getSystemService(AlarmManager::class.java)).nextScheduledAlarm)
    }
}
