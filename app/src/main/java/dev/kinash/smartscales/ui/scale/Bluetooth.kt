package dev.kinash.smartscales.ui.scale

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.kinash.smartscales.R
import dev.kinash.smartscales.core.ble.AdvertisementScanner
import dev.kinash.smartscales.core.ble.ScanFilters
import dev.kinash.smartscales.core.ble.BluetoothUnavailableException
import dev.kinash.smartscales.core.ble.ScanFailedException
import dev.kinash.smartscales.core.ble.ScannedAdvertisement
import dev.kinash.smartscales.ui.history.EmptyState
import kotlinx.coroutines.CancellationException

/**
 * Permissions BLE scanning needs on this Android version. From Android 12 connecting to scales read over GATT
 * needs its own permission; Android asks for both at once as "Nearby devices".
 */
internal val scanPermissions: Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
} else {
    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
}

internal fun Context.hasScanPermission() =
    scanPermissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

/** Before Android 12 scanning finds nothing while location is off. */
private fun Context.locationReady(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ||
    getSystemService(LocationManager::class.java)?.let(LocationManagerCompat::isLocationEnabled) == true

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Shows [content] only when scanning can work: Bluetooth LE exists, the scan permission is granted,
 * Bluetooth is on and, before Android 12, location is on. Otherwise explains what is missing and offers
 * the way to fix it. Checks again whenever the screen comes back from settings, and follows location
 * switched from the quick settings.
 */
@Composable
fun BluetoothGate(scanner: AdvertisementScanner, content: @Composable () -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(context.hasScanPermission()) }
    var locationReady by remember { mutableStateOf(context.locationReady()) }
    var asked by rememberSaveable { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        granted = context.hasScanPermission()
        locationReady = context.locationReady()
        onPauseOrDispose { }
    }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
        DisposableEffect(context) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    locationReady = context.locationReady()
                }
            }
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            onDispose { context.unregisterReceiver(receiver) }
        }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        granted = result.values.all { it }
        asked = true
    }
    val enabledFlow = remember(scanner) { scanner.observeEnabled() }
    val enabled by enabledFlow.collectAsStateWithLifecycle(initialValue = true)

    when {
        !scanner.isSupported -> EmptyState(stringResource(R.string.bt_unsupported))
        !granted -> {
            val activity = context.findActivity()
            // After a refusal with "don't ask again" Android shows no dialog; only app settings help
            val blocked = asked && activity != null &&
                scanPermissions.none { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
            EmptyState(
                text = stringResource(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) R.string.bt_permission_text else R.string.bt_permission_text_legacy,
                ),
                action = stringResource(if (blocked) R.string.bt_permission_settings else R.string.bt_permission_grant),
                onAction = {
                    if (blocked) {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                        )
                    } else {
                        launcher.launch(scanPermissions)
                    }
                },
            )
        }
        !enabled -> EmptyState(
            text = stringResource(R.string.bt_off),
            action = stringResource(R.string.bt_settings),
            onAction = { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
        )
        !locationReady -> EmptyState(
            text = stringResource(R.string.bt_location_off),
            action = stringResource(R.string.bt_location_settings),
            onAction = { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
        )
        else -> content()
    }
}

/** Runs [scan] while the screen is visible, so the phone does not scan in the background. */
@Composable
fun ScanWhileVisible(key: Any, scan: suspend () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, key) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { scan() }
    }
}

/** Scan error code shown when Bluetooth went away or the permission was revoked during the scan. */
const val SCAN_UNAVAILABLE = 0

/**
 * Collects advertisements until cancelled; returns the error code when the scan fails instead of throwing,
 * or null when it was cancelled.
 */
suspend fun AdvertisementScanner.scanUntilError(
    filters: ScanFilters? = null,
    saveBattery: Boolean = false,
    onAdvertisement: suspend (ScannedAdvertisement) -> Unit,
): Int? = try {
    scan(filters, saveBattery).collect(onAdvertisement)
    null
} catch (e: CancellationException) {
    throw e
} catch (e: ScanFailedException) {
    e.errorCode
} catch (_: BluetoothUnavailableException) {
    SCAN_UNAVAILABLE
} catch (_: SecurityException) {
    SCAN_UNAVAILABLE
}
