package dev.kinash.smartscales.core.ble

import dev.kinash.smartscales.core.protocol.ChipseaGatt
import dev.kinash.smartscales.core.protocol.ChipseaProtocol
import dev.kinash.smartscales.core.protocol.CloudV4Channel
import dev.kinash.smartscales.core.protocol.CloudV4Commands
import dev.kinash.smartscales.core.protocol.CloudV4Measurement
import dev.kinash.smartscales.core.protocol.CloudV4Parser
import dev.kinash.smartscales.core.protocol.ScaleRecords
import java.time.Instant

/**
 * OKOKCloud V4 scales (`FF F0 10/11` advertisements) over GATT, written from the protocol description without
 * a scale at hand ([ProtocolDescription.testedOnRealScale] is false).
 */
class CloudV4GattDriver(private val now: () -> Instant = Instant::now) : GattScaleDriver {
    override fun gattProtocol(protocol: String, scaleMac: String, version: String?, productId: Int?): GattProtocol? =
        if (protocol == ChipseaProtocol.OKOK_CLOUD_V4.name) CloudV4Protocol(scaleMac, now) else null

    companion object {
        val IMPLEMENTED = setOf(
            DeviceCapability.LIVE_WEIGHT,
            DeviceCapability.BODY_COMPOSITION,
            DeviceCapability.HEART_RATE,
            DeviceCapability.USER_SYNC,
            DeviceCapability.HISTORY_READ,
            DeviceCapability.TIME_SYNC,
            DeviceCapability.UNIT_CONFIG,
        )
    }
}

/**
 * One connection to a V4 scale. After connecting the vendor app writes the time, the unit and the person, each
 * after the previous one completed, and only then subscribes to history, live weight and results; this does
 * the same. The frames name no user, so a result is matched to a profile by its weight, and history records go
 * to the inbox.
 */
internal class CloudV4Protocol(private val scaleMac: String, private val now: () -> Instant) : GattProtocol {
    private val parser = CloudV4Parser()

    override suspend fun start(session: GattSession, context: GattContext) {
        // The time is not essential: a scale without the Current Time characteristic is still read
        if (session.hasCharacteristic(ChipseaGatt.CURRENT_TIME_SERVICE, ChipseaGatt.CURRENT_TIME)) {
            session.write(ChipseaGatt.CURRENT_TIME_SERVICE, ChipseaGatt.CURRENT_TIME, CloudV4Commands.time(context.now))
        }
        session.write(ChipseaGatt.CHIPSEA_SERVICE, ChipseaGatt.CHIPSEA_TX, CloudV4Commands.unit(context.unit))
        context.user?.let { user ->
            val command = CloudV4Commands.user(
                userId = user.userId,
                male = user.male,
                ageYears = user.ageYears.coerceIn(0, CloudV4Commands.MAX_AGE),
                heightCm = user.heightCm.coerceIn(1, CloudV4Commands.MAX_HEIGHT_CM),
                weightKg = user.weightKg ?: 0.0,
            )
            session.write(ChipseaGatt.CHIPSEA_SERVICE, ChipseaGatt.CHIPSEA_TX, command)
        }
        if (session.hasCharacteristic(ChipseaGatt.BODY_COMPOSITION_SERVICE, ChipseaGatt.BODY_COMPOSITION_HISTORY)) {
            session.subscribe(ChipseaGatt.BODY_COMPOSITION_SERVICE, ChipseaGatt.BODY_COMPOSITION_HISTORY)
        }
        session.subscribe(ChipseaGatt.CHIPSEA_SERVICE, ChipseaGatt.CHIPSEA_RX)
        session.subscribe(ChipseaGatt.BODY_COMPOSITION_SERVICE, ChipseaGatt.BODY_COMPOSITION_MEASUREMENT)
    }

    override suspend fun onNotification(notification: GattNotification): List<GattEvent> {
        val channel = CloudV4Channel.of(notification.characteristic) ?: return emptyList()
        val measurement = parser.parse(channel, notification.value) ?: return emptyList()
        val reading = readingOf(measurement)
        val event = when (channel) {
            CloudV4Channel.LIVE -> GattEvent.Live(reading)
            // A weighing that has no weight is nothing to save
            CloudV4Channel.RESULT -> reading.weight?.let {
                GattEvent.Result(reading, ScaleRecords.timeOfResult(measurement.takenAt, now()))
            }
            CloudV4Channel.HISTORY -> measurement.takenAt
                ?.takeIf { reading.weight != null && ScaleRecords.isPlausibleTime(it, now()) }
                ?.let { GattEvent.History(reading, it) }
        }
        return listOfNotNull(event)
    }

    private fun readingOf(m: CloudV4Measurement): ScaleReading {
        val weight = m.weight.takeIf { ScaleRecords.isPlausibleWeight(it.kilograms) }
        return ScaleReading(
            scaleMac = scaleMac,
            weight = weight,
            impedances = if (weight != null) m.impedances else emptyList(),
            locked = m.channel != CloudV4Channel.LIVE,
            propertyBits = m.property.bits,
            heartRateBpm = m.heartRateBpm,
        )
    }
}
