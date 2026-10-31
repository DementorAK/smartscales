package dev.kinash.smartscales.core.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Scanning failed to start; [errorCode] is one of the `ScanCallback.SCAN_FAILED_*` codes. */
class ScanFailedException(val errorCode: Int) : Exception("BLE scan failed: $errorCode")

/** Bluetooth is off or the device has no Bluetooth LE. */
class BluetoothUnavailableException : Exception("Bluetooth LE is not available")

/** Source of advertisements of nearby devices; tests replace the Bluetooth one. */
interface AdvertisementScanner {
    /** Whether the device has Bluetooth LE at all. */
    val isSupported: Boolean

    /** Whether Bluetooth is on, updated when the user switches it. */
    fun observeEnabled(): Flow<Boolean>

    /** Advertisements while collected; fails with [ScanFailedException] or [BluetoothUnavailableException]. */
    fun scan(): Flow<ScannedAdvertisement>
}

/** BLE advertisements of nearby devices, without filters: Chipsea scales are recognised by their data. */
class BleScanner(private val context: Context) : AdvertisementScanner {
    private val adapter: BluetoothAdapter?
        get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    override val isSupported: Boolean get() = adapter != null

    override fun observeEnabled(): Flow<Boolean> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                trySend(isEnabled())
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        trySend(isEnabled())
        awaitClose { context.unregisterReceiver(receiver) }
    }.distinctUntilChanged()

    private fun isEnabled(): Boolean = adapter?.isEnabled == true

    /**
     * Advertisements while collected. The caller must hold the scan permission (`BLUETOOTH_SCAN` on
     * Android 12+, location before) and Bluetooth must be on, otherwise the flow fails.
     *
     * The scan restarts every [RESTART_INTERVAL_MS]: some phones report a device only once per scan, and
     * a scale keeps the same address while its weight changes. Android allows 5 starts in 30 seconds.
     */
    @SuppressLint("MissingPermission")
    override fun scan(): Flow<ScannedAdvertisement> = callbackFlow {
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (scanner == null) {
            close(BluetoothUnavailableException())
            return@callbackFlow
        }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                result.toAdvertisement()?.let { trySend(it) }
            }

            override fun onBatchScanResults(results: List<ScanResult>) {
                results.forEach { result -> result.toAdvertisement()?.let { trySend(it) } }
            }

            override fun onScanFailed(errorCode: Int) {
                close(ScanFailedException(errorCode))
            }
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val restarts = launch {
            // Android throws when Bluetooth goes off between the state check and the call
            try {
                while (true) {
                    scanner.startScan(null, settings, callback)
                    delay(RESTART_INTERVAL_MS)
                    scanner.stopScan(callback)
                }
            } catch (_: IllegalStateException) {
                close(BluetoothUnavailableException())
            }
        }
        awaitClose {
            restarts.cancel()
            // Stopping fails with an exception when Bluetooth was switched off meanwhile
            runCatching { scanner.stopScan(callback) }
        }
    }

    private fun ScanResult.toAdvertisement(): ScannedAdvertisement? {
        val record = scanRecord ?: return null
        return ScannedAdvertisement(
            address = device.address,
            // The name from the advertisement needs no connect permission, unlike BluetoothDevice.name
            name = record.deviceName,
            rssi = rssi,
            record = record.bytes,
            receivedAtMs = timestampNanos / 1_000_000,
        )
    }

    companion object {
        const val RESTART_INTERVAL_MS = 10_000L
    }
}
