package dev.kinash.smartscales.background

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.kinash.smartscales.MainActivity
import dev.kinash.smartscales.R
import dev.kinash.smartscales.core.ble.AdvertisementScanner
import dev.kinash.smartscales.core.data.repository.SettingsRepository
import dev.kinash.smartscales.ui.common.scaleWeightText
import dev.kinash.smartscales.ui.weighing.SavedWeighing
import dev.kinash.smartscales.ui.weighing.WeighingSession
import dev.kinash.smartscales.ui.weighing.WeighingState
import dev.kinash.smartscales.ui.weighing.WhoQuestion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject

/**
 * Listens to the scales in the background (requirements §2.3) with the app's [WeighingSession], while the mode
 * is on and Bluetooth is on. Its notification says the app listens and shows the last weighing saved; a weighing
 * the app could not tell whose it is gets a notification of its own that asks, with a button per likely profile,
 * unless a screen of the app is open and asks there.
 */
@AndroidEntryPoint
class WeighingService : Service() {
    @Inject
    lateinit var session: WeighingSession

    @Inject
    lateinit var scanner: AdvertisementScanner

    @Inject
    lateinit var settings: SettingsRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var started = false

    /** The last weighing saved for a profile, for the notification. */
    private var last: SavedWeighing? = null
    private var lastAt: Instant? = null

    /** Questions in the shade, by the weighing's id. */
    private val asked = mutableSetOf<Long>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannels(this)
        try {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
            ServiceCompat.startForeground(this, LISTENING_ID, listening(), type)
        } catch (_: SecurityException) {
            // The Bluetooth permission was taken away
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.getBooleanExtra(EXTRA_SEEN, false) == true && last != null) {
            last = null
            notifyIfAllowed(NotificationManagerCompat.from(this), LISTENING_ID, listening())
        }
        if (!started) {
            started = true
            scope.launch { settings.backgroundMode.collect { on -> if (!on) stopSelf() } }
            scope.launch {
                scanner.observeEnabled().collectLatest { on ->
                    // Bluetooth off ends the scan; on again, it starts again. A failed scan is tried again later
                    while (on) {
                        session.listen(onScreen = false)
                        delay(RETRY_MS)
                    }
                }
            }
            scope.launch { session.state.collect(::show) }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun show(state: WeighingState) {
        val manager = NotificationManagerCompat.from(this)
        val saved = state.saved
        if (saved?.profile != null && saved.measurementId != last?.measurementId) {
            last = saved
            lastAt = Instant.now()
            notifyIfAllowed(manager, LISTENING_ID, listening())
        }
        // Answered here or on the screen: no longer asked
        val open = state.questions.mapTo(HashSet()) { it.measurementId }
        (asked - open).forEach { manager.cancel(TAG_WHOSE, it.toInt()) }
        asked.retainAll(open)
        if (session.onScreen.value) return
        state.questions.filter { it.measurementId !in asked }.forEach { question ->
            asked += question.measurementId
            notifyIfAllowed(manager, question.measurementId.toInt(), whose(question), TAG_WHOSE)
        }
    }

    @Suppress("MissingPermission")
    private fun notifyIfAllowed(manager: NotificationManagerCompat, id: Int, notification: Notification, tag: String? = null) {
        if (manager.areNotificationsEnabled()) manager.notify(tag, id, notification)
    }

    /** The service's own notification: listening, and the last weighing saved. */
    private fun listening(): Notification {
        val saved = last
        val at = lastAt
        val text = if (saved?.profile != null && at != null) {
            getString(
                R.string.background_last, saved.profile.name, scaleWeightText(saved.weight, resources),
                at.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)),
            )
        } else {
            getString(R.string.background_waiting)
        }
        return NotificationCompat.Builder(this, CHANNEL_LISTENING)
            .setSmallIcon(R.drawable.ic_scale)
            .setContentTitle(getString(R.string.background_title))
            .setContentText(text)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(openApp(0, saved?.profile?.id))
            .build()
    }

    /** "Whose weighing is it?", with a button for each of the likely profiles. */
    private fun whose(question: WhoQuestion): Notification {
        val weight = scaleWeightText(question.weight, resources)
        val builder = NotificationCompat.Builder(this, CHANNEL_WHOSE)
            .setSmallIcon(R.drawable.ic_scale)
            .setContentTitle(getString(R.string.background_whose_title, weight))
            .setContentText(getString(R.string.background_whose_text))
            .setAutoCancel(true)
            .setContentIntent(openApp(question.measurementId.toInt()))
        question.choices.take(MAX_ACTIONS).forEach { profile ->
            val answer = Intent(this, WhoseAnswerReceiver::class.java)
                .setAction(ACTION_ANSWER)
                .setData(Uri.parse("smartscales://whose/${question.measurementId}/${profile.id}"))
                .putExtra(EXTRA_MEASUREMENT, question.measurementId)
                .putExtra(EXTRA_PROFILE, profile.id)
            val pending = PendingIntent.getBroadcast(this, 0, answer, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            builder.addAction(0, profile.name, pending)
        }
        return builder.build()
    }

    /** The app, on the profile of [profileId] if given. */
    private fun openApp(requestCode: Int, profileId: Long? = null): PendingIntent {
        val open = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        profileId?.let { open.putExtra(MainActivity.EXTRA_PROFILE, it) }
        return PendingIntent.getActivity(this, requestCode, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    companion object {
        const val CHANNEL_LISTENING = "background"
        const val CHANNEL_WHOSE = "whose"
        const val TAG_WHOSE = "whose"
        const val LISTENING_ID = 1
        const val ACTION_ANSWER = "dev.kinash.smartscales.ANSWER_WHOSE"
        const val EXTRA_MEASUREMENT = "measurement"
        const val EXTRA_PROFILE = "profile"

        /** The app was opened: the notification goes back to waiting. */
        const val EXTRA_SEEN = "seen"

        /** Android shows up to three buttons on a notification. */
        private const val MAX_ACTIONS = 3

        /** After a scan failed, before it is tried again. */
        private const val RETRY_MS = 60_000L

        fun createChannels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            // Quiet: it is there all the time
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_LISTENING, context.getString(R.string.background_channel), NotificationManager.IMPORTANCE_MIN),
            )
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_WHOSE, context.getString(R.string.background_whose_channel), NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
    }
}

/** A button of "whose weighing is it?": the weighing goes to that profile. */
@AndroidEntryPoint
class WhoseAnswerReceiver : BroadcastReceiver() {
    @Inject
    lateinit var session: WeighingSession

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != WeighingService.ACTION_ANSWER) return
        val measurementId = intent.getLongExtra(WeighingService.EXTRA_MEASUREMENT, -1)
        val profileId = intent.getLongExtra(WeighingService.EXTRA_PROFILE, -1)
        if (measurementId < 0 || profileId < 0) return
        NotificationManagerCompat.from(context).cancel(WeighingService.TAG_WHOSE, measurementId.toInt())
        val pending = goAsync()
        CoroutineScope(Dispatchers.Main.immediate).launch {
            try {
                session.answer(measurementId, profileId)
            } finally {
                pending.finish()
            }
        }
    }
}
