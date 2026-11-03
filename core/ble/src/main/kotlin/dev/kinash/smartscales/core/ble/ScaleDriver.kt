package dev.kinash.smartscales.core.ble

import dev.kinash.smartscales.core.model.Impedance
import dev.kinash.smartscales.core.protocol.ScaleWeight

/** One advertisement as received: [record] is the raw scan record, kept as is for parsing and the log. */
class ScannedAdvertisement(
    val address: String,
    val name: String?,
    val rssi: Int,
    val record: ByteArray,
    /** Elapsed realtime of reception in milliseconds. */
    val receivedAtMs: Long,
)

/** Features a scale can have, after `DeviceCapability` in openScale. */
enum class DeviceCapability {
    LIVE_WEIGHT,
    BODY_COMPOSITION,
    HEART_RATE,
    USER_SYNC,
    HISTORY_READ,
    TIME_SYNC,
    UNIT_CONFIG,
}

/** How the app talks to the scale. */
enum class LinkMode { BROADCAST_ONLY, CONNECT_GATT }

/**
 * A protocol a driver handles, after `DeviceSupport` in openScale: the scale may have more [capabilities]
 * than the driver has [implemented] so far, and the app says so honestly.
 */
data class ProtocolDescription(
    val displayName: String,
    val capabilities: Set<DeviceCapability>,
    val implemented: Set<DeviceCapability>,
    val linkMode: LinkMode,
    /**
     * The driver was checked on a real scale. Drivers written from the protocol description alone say so, and
     * the app tells the person: their Bluetooth log helps to fix what is read wrong.
     */
    val testedOnRealScale: Boolean = true,
) {
    /** Weighings can be saved from such a scale already; otherwise only a preview of the weight is shown. */
    val canWeigh: Boolean get() = DeviceCapability.LIVE_WEIGHT in implemented
}

/**
 * A device a driver recognised.
 *
 * @property protocol stable protocol identifier stored in the scale binding.
 * @property scaleMac the scale's own MAC: the binding key. It may differ from the address Android sees.
 */
data class DeviceSupport(
    val protocol: String,
    val description: ProtocolDescription,
    val scaleMac: String,
    val protocolVersion: String?,
    val productId: Int?,
)

/**
 * Weight read from one advertisement.
 *
 * @property weight null while the scale sends no weight (measuring, or encrypted without a key).
 * @property locked the weight is final; a weighing is saved from a locked reading.
 * @property measureSeqNo number of the weighing, the same in all its frames; null when unknown.
 * @property propertyBits the raw property byte, stored with the measurement to re-check parsing later.
 * @property decrypted the weight was decrypted with the scale's key. A wrong key gives a plausible but
 *   wrong weight, so such readings are saved only after the person confirmed the key once.
 */
data class ScaleReading(
    val scaleMac: String,
    val weight: ScaleWeight?,
    val impedances: List<Impedance> = emptyList(),
    val locked: Boolean = false,
    val measureSeqNo: Int? = null,
    val propertyBits: Int? = null,
    val needsKey: Boolean = false,
    val decrypted: Boolean = false,
    val heartRateBpm: Int? = null,
    /** The person the scale took the weighing for, by the user id the app gave them; null when it did not say. */
    val userId: Int? = null,
    /** What the scale worked out itself, by `ScaleValues` identifiers; kept as the scale's data. */
    val scaleValues: Map<String, Double> = emptyMap(),
)

/** Keys that unlock encrypted advertisements, looked up by the scale's own MAC. */
fun interface KeyProvider {
    fun keyFor(scaleMac: String): ByteArray?
}

/**
 * A scale protocol driver, modelled on `ScaleDeviceHandler` in openScale: it recognises its devices
 * ([supportFor]) and turns their advertisements into readings ([onAdvertisement]). Scales read over a
 * connection have a [GattScaleDriver] as well.
 */
interface ScaleDriver {
    /** The protocol with this identifier, or null when it is not this driver's. */
    fun describe(protocol: String): ProtocolDescription?

    fun supportFor(advertisement: ScannedAdvertisement): DeviceSupport?

    /** The reading in [advertisement], or null when it carries none from a scale of this driver. */
    fun onAdvertisement(advertisement: ScannedAdvertisement, keys: KeyProvider): ScaleReading?
}

/**
 * All drivers, after `ScaleFactory` in openScale: the first one that recognises a device handles it. Scales
 * read over a connection are recognised by their advertisements too, and [gattDrivers] then talk to them.
 */
class ScaleDrivers(private val drivers: List<ScaleDriver>, private val gattDrivers: List<GattScaleDriver> = emptyList()) {
    /** The protocol of a new connection to a scale bound with [protocol], or null when no driver connects to it. */
    fun gattProtocol(protocol: String, scaleMac: String, version: String?): GattProtocol? =
        gattDrivers.firstNotNullOfOrNull { it.gattProtocol(protocol, scaleMac, version) }

    fun describe(protocol: String): ProtocolDescription? = drivers.firstNotNullOfOrNull { it.describe(protocol) }

    /** The driver of the device that sent [advertisement] with what it says about it, or null for other devices. */
    fun recognise(advertisement: ScannedAdvertisement): Pair<ScaleDriver, DeviceSupport>? =
        drivers.firstNotNullOfOrNull { driver -> driver.supportFor(advertisement)?.let { driver to it } }
}
