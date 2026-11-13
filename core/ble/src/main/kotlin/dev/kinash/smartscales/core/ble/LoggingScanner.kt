package dev.kinash.smartscales.core.ble

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach

/** Passes on what [scanner] receives and writes it to [log], marking the scales [drivers] recognise. */
class LoggingScanner(
    private val scanner: AdvertisementScanner,
    private val log: BluetoothLog,
    private val drivers: ScaleDrivers,
) : AdvertisementScanner by scanner {
    override fun scan(filters: ScanFilters?, saveBattery: Boolean): Flow<ScannedAdvertisement> = scanner.scan(filters, saveBattery).onEach { advertisement ->
        if (log.recording.value) log.record(advertisement, drivers.recognise(advertisement)?.second?.protocol)
    }
}
