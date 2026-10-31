package dev.kinash.smartscales.core.ble

import dev.kinash.smartscales.core.model.Impedance
import dev.kinash.smartscales.core.protocol.ChipseaAdvertisement
import dev.kinash.smartscales.core.protocol.ChipseaProtocol
import dev.kinash.smartscales.core.protocol.manufacturerData

/**
 * Chipsea / OKOK scales by their advertisements. Broadcast scales (`CA 20`, `C0`) are weighed from
 * advertisements alone; GATT scales (`CA 10/11`, `FF F0`) show a weight preview until their GATT drivers
 * come in 0.5.
 */
class ChipseaAdvertisementDriver : ScaleDriver {

    override fun describe(protocol: String): ProtocolDescription? =
        ChipseaProtocol.entries.firstOrNull { it.name == protocol }?.let(::describe)

    private fun describe(protocol: ChipseaProtocol): ProtocolDescription {
        val broadcast = protocol.isBroadcastOnly
        return ProtocolDescription(
            displayName = DISPLAY_NAMES.getValue(protocol),
            capabilities = capabilitiesOf(protocol),
            implemented = if (broadcast) setOf(DeviceCapability.LIVE_WEIGHT) else emptySet(),
            linkMode = if (broadcast) LinkMode.BROADCAST_ONLY else LinkMode.CONNECT_GATT,
        )
    }

    override fun supportFor(advertisement: ScannedAdvertisement): DeviceSupport? {
        if (IGNORED_NAME_PARTS.any { advertisement.name?.contains(it) == true }) return null
        val data = manufacturerData(advertisement.record) ?: return null
        val frame = ChipseaAdvertisement.parse(data) ?: return null
        return DeviceSupport(
            protocol = frame.protocol.name,
            description = describe(frame.protocol),
            scaleMac = frame.scaleMac ?: advertisement.address.uppercase(),
            protocolVersion = frame.version?.let { "0x%02X".format(it) },
            productId = frame.productId,
        )
    }

    override fun onAdvertisement(advertisement: ScannedAdvertisement, keys: KeyProvider): ScaleReading? {
        val data = manufacturerData(advertisement.record) ?: return null
        val plain = ChipseaAdvertisement.parse(data) ?: return null
        val scaleMac = plain.scaleMac ?: advertisement.address.uppercase()
        val key = if (plain.encrypted) keys.keyFor(scaleMac) else null
        val decrypted = key?.let { ChipseaAdvertisement.parse(data, it) }
        val frame = decrypted ?: plain
        val weight = frame.weight?.takeIf { it.kilograms in VALID_KG }
        return ScaleReading(
            scaleMac = scaleMac,
            weight = weight,
            impedances = listOfNotNull(
                frame.impedanceOhm?.takeIf { weight != null }?.let { Impedance(Impedance.FOOT_TO_FOOT, it) },
            ),
            // GATT scales confirm the final weight over the connection, so their advertisements never lock
            locked = frame.locked && weight != null && frame.protocol.isBroadcastOnly,
            measureSeqNo = frame.measureSeqNo,
            propertyBits = frame.property?.bits,
            needsKey = frame.needsKey,
            decrypted = decrypted != null,
        )
    }

    private fun capabilitiesOf(protocol: ChipseaProtocol): Set<DeviceCapability> = when (protocol) {
        ChipseaProtocol.OKOK_BROADCAST, ChipseaProtocol.OKOK_V2 ->
            setOf(DeviceCapability.LIVE_WEIGHT, DeviceCapability.BODY_COMPOSITION)
        ChipseaProtocol.OKOK -> setOf(
            DeviceCapability.LIVE_WEIGHT,
            DeviceCapability.BODY_COMPOSITION,
            DeviceCapability.USER_SYNC,
            DeviceCapability.HISTORY_READ,
            DeviceCapability.TIME_SYNC,
        )
        ChipseaProtocol.OKOK_CLOUD -> setOf(
            DeviceCapability.LIVE_WEIGHT,
            DeviceCapability.BODY_COMPOSITION,
            DeviceCapability.HISTORY_READ,
            DeviceCapability.TIME_SYNC,
            DeviceCapability.UNIT_CONFIG,
        )
        ChipseaProtocol.OKOK_CLOUD_V4 -> setOf(
            DeviceCapability.LIVE_WEIGHT,
            DeviceCapability.BODY_COMPOSITION,
            DeviceCapability.HEART_RATE,
            DeviceCapability.USER_SYNC,
            DeviceCapability.HISTORY_READ,
            DeviceCapability.TIME_SYNC,
            DeviceCapability.UNIT_CONFIG,
        )
    }

    companion object {
        /** Scale weights outside this range are noise; the vendor app drops 600 kg and more as well. */
        val VALID_KG = 0.5..600.0

        /** Name parts of other Chipsea devices (skipping ropes and the like) that share the frame headers. */
        private val IGNORED_NAME_PARTS = setOf("SWAN", "AIMA-", "BYECH", "EeEeE", "CS-Rope-")

        private val DISPLAY_NAMES = mapOf(
            ChipseaProtocol.OKOK_BROADCAST to "Chipsea OKOK (CA 20)",
            ChipseaProtocol.OKOK to "Chipsea OKOK (CA 10/11)",
            ChipseaProtocol.OKOK_CLOUD to "Chipsea OKOKCloud",
            ChipseaProtocol.OKOK_CLOUD_V4 to "Chipsea OKOKCloud V4",
            ChipseaProtocol.OKOK_V2 to "Chipsea OKOK V2 (C0)",
        )
    }
}
