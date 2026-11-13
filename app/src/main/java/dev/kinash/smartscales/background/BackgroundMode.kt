package dev.kinash.smartscales.background

import android.app.ForegroundServiceStartNotAllowedException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.kinash.smartscales.core.ble.AdvertisementScanner
import dev.kinash.smartscales.core.data.repository.SettingsRepository
import dev.kinash.smartscales.ui.scale.hasScanPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The background mode (requirements §2.3): the app listens to the paired scales with the screen off and the app
 * closed, in a foreground service with its notification, from turning it on and after a restart of the phone.
 * Off unless turned on in the settings.
 *
 * Not on Android 10–11: a scan from a service there needs the location, a service of the location type and its
 * declaration for Google Play, which the app does not ask for.
 */
@Singleton
class BackgroundMode @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val scanner: AdvertisementScanner,
) {
    /** Whether this phone can have the background mode at all. */
    val available: Boolean get() = scanner.isSupported && Build.VERSION.SDK_INT !in Build.VERSION_CODES.Q..Build.VERSION_CODES.R

    /**
     * The service runs when the mode is on and the scan is allowed, and stops otherwise. [seen]: the app is on
     * the screen, so the last weighing in the notification has been seen and it goes back to waiting.
     */
    suspend fun sync(seen: Boolean = false) {
        val on = available && settings.backgroundMode.first() && context.hasScanPermission()
        val service = Intent(context, WeighingService::class.java).putExtra(WeighingService.EXTRA_SEEN, seen)
        if (!on) {
            context.stopService(service)
            return
        }
        // Android 12+ starts it only from the app on the screen, a restart of the phone and an update
        try {
            ContextCompat.startForegroundService(context, service)
        } catch (e: IllegalStateException) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || e !is ForegroundServiceStartNotAllowedException) throw e
        }
    }
}

/** The phone restarted or the app was updated: the background mode, if on, starts again. */
@AndroidEntryPoint
class BackgroundStartReceiver : BroadcastReceiver() {
    @Inject
    lateinit var mode: BackgroundMode

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Main.immediate).launch {
            try {
                mode.sync()
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val ACTIONS = setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)
    }
}
