package dev.kinash.smartscales.core.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import dev.kinash.smartscales.core.protocol.manufacturerData
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

/**
 * What a scan in the background looks for: devices at [addresses], and advertisements whose company id, the first
 * two bytes of the manufacturer data, is one of [manufacturerIds]. Android scans with the screen off only with a
 * filter. Chipsea scales of `CA` and `FF F0` advertisements have a fixed company id; `C0` ones put their weighing
 * number there, so they are only found at their address.
 */
data class ScanFilters(val addresses: Set<String>, val manufacturerIds: Set<Int>) {
    val isEmpty: Boolean get() = addresses.isEmpty() && manufacturerIds.isEmpty()

    fun matches(advertisement: ScannedAdvertisement): Boolean =
        advertisement.address.uppercase() in addresses || manufacturerIdOf(advertisement.record) in manufacturerIds
}

/** The company id of the manufacturer data in [record], little-endian as in Bluetooth, or null. */
fun manufacturerIdOf(record: ByteArray): Int? = manufacturerData(record)?.takeIf { it.size >= 2 }?.let {
    (it[0].toInt() and 0xFF) or ((it[1].toInt() and 0xFF) shl 8)
}

/** Source of advertisements of nearby devices; tests replace the Bluetooth one. */
interface AdvertisementScanner {
    /** Whether the device has Bluetooth LE at all. */
    val isSupported: Boolean

    /** Whether Bluetooth is on, updated when the user switches it. */
    fun observeEnabled(): Flow<Boolean>

    /**
     * Advertisements while collected; fails with [ScanFailedException] or [BluetoothUnavailableException]. With
     * [filters] only what they let through. [saveBattery] scans in short windows rather than all the time, as
     * the background does.
     */
    fun scan(filters: ScanFilters? = null, saveBattery: Boolean = false): Flow<ScannedAdvertisement>
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
    override fun scan(filters: ScanFilters?, saveBattery: Boolean): Flow<ScannedAdvertisement> = callbackFlow {
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
        val scanFilters = filters?.let { wanted ->
            wanted.addresses.map { ScanFilter.Builder().setDeviceAddress(it.uppercase()).build() } +
                wanted.manufacturerIds.map { ScanFilter.Builder().setManufacturerData(it, ByteArray(0)).build() }
        }
        val mode = if (saveBattery) ScanSettings.SCAN_MODE_LOW_POWER else ScanSettings.SCAN_MODE_LOW_LATENCY
        val settings = ScanSettings.Builder().setScanMode(mode).build()
        val restarts = launch {
            // Android throws when Bluetooth goes off between the state check and the call
            try {
                while (true) {
                    scanner.startScan(scanFilters, settings, callback)
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
