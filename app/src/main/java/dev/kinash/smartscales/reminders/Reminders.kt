package dev.kinash.smartscales.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.kinash.smartscales.MainActivity
import dev.kinash.smartscales.R
import dev.kinash.smartscales.core.data.Reminder
import dev.kinash.smartscales.core.data.repository.MeasurementRepository
import dev.kinash.smartscales.core.data.repository.ProfileRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reminders to weigh (requirements §6.5), at their time give or take ten minutes, which needs no permission for
 * exact alarms. One alarm serves all profiles: it goes to the nearest time of any reminder, so that a phone rarely
 * opened, whose app Android allows few alarms a day, and Android 8–11, which allows one in Doze every nine minutes
 * or so, spend them on one way there rather than one per profile. The alarm is allowed in Doze, where a phone left
 * overnight is by the morning, and comes closer in steps ([ReminderSchedule.alarmAt]). When it reaches the time,
 * each reminder whose time it is looks at the weighings and says what is due, if anything, and the alarm goes on to
 * the next time. It follows the reminders as they change (a profile saved or deleted, a backup restored), and a
 * reminder already shown goes away once the profile weighs.
 */
@Singleton
class Reminders @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val profiles: ProfileRepository,
    private val measurements: MeasurementRepository,
    private val clock: Clock,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val alarms get() = context.getSystemService(AlarmManager::class.java)
    private val zone: ZoneId get() = ZoneId.systemDefault()

    /** Started with the app: keeps the alarm as the reminders are, and clears a reminder once the profile weighs. */
    fun start() {
        scope.launch { profiles.observeReminders().collect(::schedule) }
        scope.launch { measurements.observeLastWeighings().collect(::dismissWeighed) }
    }

    /** After a restart of the phone or a change of its clock or zone, when the alarm is gone or due elsewhere. */
    suspend fun rescheduleAll() = schedule(profiles.reminders())

    /** Sets the alarm on the way to the nearest time of [reminders], or none when all are off. */
    fun schedule(reminders: List<Reminder>) {
        val now = Instant.now(clock)
        val target = ReminderSchedule.nearest(now.atZone(zone), reminders)?.toInstant()
        if (target == null) {
            alarms.cancel(alarmIntent(0))
            return
        }
        val at = ReminderSchedule.alarmAt(now, target)
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), alarmIntent(target.toEpochMilli()))
    }

    /**
     * The alarm on the way to [target] went off: short of it, the alarm comes closer; at it, each reminder whose
     * time came since says what is due, if anything, and the alarm goes on to the next time.
     */
    suspend fun fire(target: Instant) {
        val now = Instant.now(clock)
        val reminders = profiles.reminders()
        if (!now.isBefore(target)) {
            val today = now.atZone(zone).toLocalDate()
            val last = measurements.observeLastWeighings().first()
            ReminderSchedule.reached(target.atZone(zone), now.atZone(zone), reminders).forEach { reminder ->
                val profile = profiles.get(reminder.profileId) ?: return@forEach
                val weighed = last[reminder.profileId]?.atZone(zone)?.toLocalDate()
                ReminderSchedule.due(today, reminder, weighed)?.let { due -> notify(profile.id, profile.name, due) }
            }
        }
        schedule(reminders)
    }

    private fun notify(profileId: Long, name: String, due: ReminderDue) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        createChannel()
        val text = when (due) {
            ReminderDue.Schedule -> context.getString(R.string.reminder_text, name)
            is ReminderDue.Gap -> context.resources.getQuantityString(R.plurals.reminder_gap_text, due.days.toInt(), name, due.days.toInt())
        }
        val open = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_PROFILE, profileId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_scale)
            .setContentTitle(context.getString(R.string.reminder_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(context, profileId.toInt(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .build()
        NotificationManagerCompat.from(context).notify(TAG, profileId.toInt(), notification)
    }

    /** A reminder in the shade goes away once its profile has weighed today. */
    private fun dismissWeighed(last: Map<Long, Instant>) {
        val today = Instant.now(clock).atZone(zone).toLocalDate()
        val manager = NotificationManagerCompat.from(context)
        last.forEach { (profileId, at) -> if (!at.atZone(zone).toLocalDate().isBefore(today)) manager.cancel(TAG, profileId.toInt()) }
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL, context.getString(R.string.reminder_channel), NotificationManager.IMPORTANCE_DEFAULT)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun alarmIntent(target: Long): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java)
            .setAction(ACTION_REMIND)
            .setData(ALARM)
            .putExtra(EXTRA_TARGET, target)
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    companion object {
        const val ACTION_REMIND = "dev.kinash.smartscales.REMIND"
        val ALARM: Uri = Uri.parse("smartscales://reminder")
        const val CHANNEL = "reminders"
        const val EXTRA_TARGET = "target"
        const val TAG = "reminder"
    }
}

/** The alarms of reminders. */
@AndroidEntryPoint
class ReminderReceiver : BroadcastReceiver() {
    @Inject
    lateinit var reminders: Reminders

    override fun onReceive(context: Context, intent: Intent) {
        // An alarm of a profile from a build before one alarm served all is let go
        if (intent.action != Reminders.ACTION_REMIND || intent.data != Reminders.ALARM) return
        val target = Instant.ofEpochMilli(intent.getLongExtra(Reminders.EXTRA_TARGET, 0))
        async { reminders.fire(target) }
    }
}

/** The phone restarted, its clock or zone changed, or the app was updated: the alarms are set again. */
@AndroidEntryPoint
class RescheduleReceiver : BroadcastReceiver() {
    @Inject
    lateinit var reminders: Reminders

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return
        async { reminders.rescheduleAll() }
    }

    private companion object {
        val ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED,
        )
    }
}

/** Work of a receiver past [BroadcastReceiver.onReceive], which the system lets finish. */
private fun BroadcastReceiver.async(work: suspend () -> Unit) {
    val pending = goAsync()
    CoroutineScope(Dispatchers.Default).launch {
        try {
            work()
        } finally {
            pending.finish()
        }
    }
}
