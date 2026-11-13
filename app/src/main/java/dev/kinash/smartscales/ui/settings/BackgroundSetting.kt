package dev.kinash.smartscales.ui.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.kinash.smartscales.R
import dev.kinash.smartscales.ui.profile.Autostart
import dev.kinash.smartscales.ui.scale.hasScanPermission
import dev.kinash.smartscales.ui.scale.scanPermissions

/**
 * The switch of the background mode (requirements §2.3). Turning it on asks for Bluetooth, which it cannot work
 * without, and for notifications on Android 13+, where its notification and its questions would not show.
 */
@Composable
internal fun BackgroundSetting(available: Boolean, on: Boolean, onChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    fun notificationsAllowed() = NotificationManagerCompat.from(context).areNotificationsEnabled()
    var notifications by remember { mutableStateOf(notificationsAllowed()) }
    // Allowed or taken away in the system settings meanwhile
    LifecycleResumeEffect(Unit) {
        notifications = notificationsAllowed()
        onPauseOrDispose { }
    }
    var refused by rememberSaveable { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        notifications = notificationsAllowed()
        refused = !context.hasScanPermission()
        if (!refused) onChange(true)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_background), style = MaterialTheme.typography.bodyLarge)
            Hint(stringResource(if (available) R.string.settings_background_hint else R.string.settings_background_unavailable))
        }
        Switch(
            checked = on && available,
            enabled = available,
            onCheckedChange = { turnOn ->
                if (!turnOn) {
                    onChange(false)
                } else {
                    val notify = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()
                    ask.launch(scanPermissions + notify)
                }
            },
            modifier = Modifier.testTag("background_mode"),
        )
    }
    if (refused && !on) Hint(stringResource(R.string.settings_background_no_bluetooth), error = true)
    if (on && available) {
        if (!notifications) Hint(stringResource(R.string.settings_background_no_notifications), error = true)
        Autostart.settings()?.let { open ->
            Hint(stringResource(R.string.settings_background_autostart))
            TextButton(onClick = { Autostart.open(context, open) }) { Text(stringResource(R.string.reminder_autostart_open)) }
        }
    }
}

@Composable
private fun Hint(text: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
