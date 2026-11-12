package dev.kinash.smartscales.ui.profile

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.kinash.smartscales.R
import dev.kinash.smartscales.ui.common.FieldSpacing
import dev.kinash.smartscales.ui.common.TimeField
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.time.temporal.WeekFields

/**
 * The profile's reminders to weigh (requirements §6.5): on days of the week, for a diary of weighings, and after a
 * number of days without one, for keeping an eye on swings; both at one time of day. Turning one on asks for
 * notifications on Android 13+.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReminderSection(form: ProfileForm, gapError: Boolean, onChange: ((ProfileForm) -> ProfileForm) -> Unit) {
    val context = LocalContext.current
    fun allowed() = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    var granted by remember { mutableStateOf(allowed()) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    // Notifications may be allowed in the system settings meanwhile
    LifecycleResumeEffect(Unit) {
        granted = allowed()
        onPauseOrDispose { }
    }
    fun turnOn() {
        if (!allowed() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) ask.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    SwitchRow(stringResource(R.string.reminder_schedule), stringResource(R.string.reminder_schedule_hint), form.reminderOnSchedule, "reminder_schedule") { on ->
        if (on) turnOn()
        onChange { it.copy(reminderOnSchedule = on) }
    }
    if (form.reminderOnSchedule) {
        val locale = LocalConfiguration.current.locales[0]
        // The week as the phone's region starts it
        val first = WeekFields.of(locale).firstDayOfWeek
        FlowRow(horizontalArrangement = Arrangement.spacedBy(FieldSpacing)) {
            (0L..6L).map { first.plus(it) }.forEach { day ->
                val on = day in form.reminderDays
                FilterChip(
                    selected = on,
                    onClick = { onChange { f -> f.copy(reminderDays = if (on) f.reminderDays - day else f.reminderDays + day) } },
                    label = { Text(day.getDisplayName(TextStyle.SHORT, locale)) },
                )
            }
        }
        if (form.reminderDays.isEmpty()) Hint(stringResource(R.string.reminder_no_days), error = true)
    }
    SwitchRow(stringResource(R.string.reminder_gap), stringResource(R.string.reminder_gap_hint), form.reminderOnGap, "reminder_gap") { on ->
        if (on) turnOn()
        onChange { it.copy(reminderOnGap = on) }
    }
    if (form.reminderOnGap) {
        OutlinedTextField(
            value = form.reminderGapDays,
            onValueChange = { text -> onChange { it.copy(reminderGapDays = text.filter(Char::isDigit).take(2)) } },
            modifier = Modifier.fillMaxWidth().testTag("reminder_gap_days"),
            singleLine = true,
            label = { Text(stringResource(R.string.reminder_gap_days)) },
            isError = gapError,
            supportingText = if (gapError) ({ Text(stringResource(R.string.reminder_gap_days_error)) }) else null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
    }
    if (form.reminderOnSchedule || form.reminderOnGap) {
        TimeField(form.reminderTime, { time -> onChange { it.copy(reminderTime = time) } }, stringResource(R.string.reminder_time), Modifier.fillMaxWidth())
        if (!granted) Hint(stringResource(R.string.reminder_not_allowed), error = true)
        Autostart.settings()?.let { open ->
            Hint(stringResource(R.string.reminder_autostart))
            TextButton(onClick = { Autostart.open(context, open) }) { Text(stringResource(R.string.reminder_autostart_open)) }
        }
    }
}

/**
 * Some makers keep an app from starting on the phone's events until autostart is allowed for it, and then the
 * alarm is not set again after a restart (seen on Xiaomi HyperOS). Their screens of autostart, by maker; a phone
 * without one gets the app's details instead.
 */
internal object Autostart {
    private val SCREENS = mapOf(
        "xiaomi" to ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        "huawei" to ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        "honor" to ComponentName("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        "oppo" to ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
        "vivo" to ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
    )
    private val SAME = mapOf("redmi" to "xiaomi", "poco" to "xiaomi", "realme" to "oppo", "oneplus" to "oppo", "iqoo" to "vivo")

    /** The screen to allow autostart on, or null for a phone that does not ask for it. */
    fun settings(): Intent? {
        val maker = Build.MANUFACTURER.lowercase().let { SAME[it] ?: it }
        val screen = SCREENS[maker] ?: return null
        return Intent().setComponent(screen).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun open(context: Context, screen: Intent) {
        try {
            context.startActivity(screen)
        } catch (_: ActivityNotFoundException) {
            details(context)
        } catch (_: SecurityException) {
            details(context)
        }
    }

    private fun details(context: Context) = context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

@Composable
private fun SwitchRow(title: String, hint: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Hint(hint)
        }
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.testTag(tag))
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
