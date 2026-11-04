package dev.kinash.smartscales.core.ble

import dev.kinash.smartscales.core.protocol.ChipseaGatt
import dev.kinash.smartscales.core.protocol.ChipseaProtocol
import dev.kinash.smartscales.core.protocol.CloudV4Channel
import dev.kinash.smartscales.core.protocol.OkokCloudCommands
import dev.kinash.smartscales.core.protocol.OkokCloudMeasurement
import dev.kinash.smartscales.core.protocol.OkokCloudParser
import dev.kinash.smartscales.core.protocol.ScaleRecords
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * OKOKCloud scales (`FF F0` advertisements other than V4) over GATT, written from the protocol description
 * without a scale at hand. They take no person: a result is matched to a profile by its weight, and the records
 * they kept go to the inbox.
 */
class OkokCloudGattDriver(
    private val now: () -> Instant = Instant::now,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) : GattScaleDriver {
    override fun gattProtocol(protocol: String, scaleMac: String, version: String?, productId: Int?): GattProtocol? =
        if (protocol == ChipseaProtocol.OKOK_CLOUD.name) {
            OkokCloudProtocol(scaleMac, productId, setsUnit = version == VERSION_UNIT, now, zone)
        } else {
            null
        }

    /** No user command; the unit only for the version that takes it. */
    override fun settings(protocol: String, version: String?): Set<ScaleSetting> = when {
        protocol != ChipseaProtocol.OKOK_CLOUD.name -> emptySet()
        version == VERSION_UNIT -> setOf(ScaleSetting.UNIT, ScaleSetting.TIME)
        else -> setOf(ScaleSetting.TIME)
    }

    companion object {
        /** Only scales of this advertisement version take the unit, as the vendor app has it. */
        const val VERSION_UNIT = "0x02"

        val IMPLEMENTED = setOf(
            DeviceCapability.LIVE_WEIGHT,
            DeviceCapability.BODY_COMPOSITION,
            DeviceCapability.HISTORY_READ,
            DeviceCapability.TIME_SYNC,
            DeviceCapability.UNIT_CONFIG,
        )
    }
}

/**
 * One connection to an OKOKCloud scale. The vendor app subscribes to live weight and results, writes the time and
 * the unit (twice), and only then subscribes to the records the scale kept; this does the same.
 */
internal class OkokCloudProtocol(
    private val scaleMac: String,
    private val productId: Int?,
    private val setsUnit: Boolean,
    private val now: () -> Instant,
    private val zone: () -> ZoneId,
) : GattProtocol {
    override suspend fun start(session: GattSession, context: GattContext) {
        session.subscribe(ChipseaGatt.CHIPSEA_SERVICE, ChipseaGatt.CHIPSEA_RX)
        session.subscribe(ChipseaGatt.BODY_COMPOSITION_SERVICE, ChipseaGatt.BODY_COMPOSITION_MEASUREMENT)
        // The time is not essential: a scale without the Current Time characteristic is still read
        if (context.syncTime && session.hasCharacteristic(ChipseaGatt.CURRENT_TIME_SERVICE, ChipseaGatt.CURRENT_TIME)) {
            val clock = LocalDateTime.ofInstant(context.now, zone())
            session.write(ChipseaGatt.CURRENT_TIME_SERVICE, ChipseaGatt.CURRENT_TIME, OkokCloudCommands.time(clock))
        }
        val unit = context.unit
        if (setsUnit && unit != null) {
            repeat(UNIT_REPEATS) {
                session.write(ChipseaGatt.CHIPSEA_SERVICE, ChipseaGatt.CHIPSEA_TX, OkokCloudCommands.unit(unit))
            }
        }
        if (session.hasCharacteristic(ChipseaGatt.BODY_COMPOSITION_SERVICE, ChipseaGatt.BODY_COMPOSITION_HISTORY)) {
            session.subscribe(ChipseaGatt.BODY_COMPOSITION_SERVICE, ChipseaGatt.BODY_COMPOSITION_HISTORY)
        }
    }

    override suspend fun onNotification(notification: GattNotification): List<GattEvent> {
        val channel = CloudV4Channel.of(notification.characteristic) ?: return emptyList()
        val measurement = OkokCloudParser.parse(channel, notification.value, productId, zone()) ?: return emptyList()
        val reading = readingOf(measurement)
        val event = when (channel) {
            CloudV4Channel.LIVE -> GattEvent.Live(reading)
            CloudV4Channel.RESULT -> reading.weight?.let {
                GattEvent.Result(reading, ScaleRecords.timeOfResult(measurement.takenAt, now()))
            }
            CloudV4Channel.HISTORY -> measurement.takenAt
                ?.takeIf { reading.weight != null && ScaleRecords.isPlausibleTime(it, now()) }
                ?.let { GattEvent.History(reading, it) }
        }
        return listOfNotNull(event)
    }

    private fun readingOf(m: OkokCloudMeasurement): ScaleReading {
        val weight = m.weight.takeIf { ScaleRecords.isPlausibleWeight(it.kilograms) }
        return ScaleReading(
            scaleMac = scaleMac,
            weight = weight,
            impedances = listOfNotNull(m.impedance.takeIf { weight != null }),
            locked = m.channel != CloudV4Channel.LIVE,
            propertyBits = m.property.bits,
        )
    }

    private companion object {
        const val UNIT_REPEATS = 2
    }
}
