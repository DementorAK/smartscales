package dev.kinash.smartscales.core.protocol

import dev.kinash.smartscales.core.model.Impedance
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** OKOKCloud scales (`FF F0` advertisements other than V4). */
object OkokCloud {
    /**
     * Products whose property byte means nothing; their weights are kilograms with one decimal. One of them
     * sends `FA FB FC FD` for its product number.
     */
    val FIXED_PROPERTY_PRODUCTS = setOf(0xFAFBFCFD.toInt(), 1_104_000_001)

    /** Kilograms, one decimal in the Cloud layout. */
    const val FIXED_PROPERTY_BITS = 0x04

    /** The property of a scale that is a [FIXED_PROPERTY_PRODUCTS] one, or [sent] otherwise. */
    fun property(sent: Int, productId: Int?): ScaleProperty =
        ScaleProperty(if (productId in FIXED_PROPERTY_PRODUCTS) FIXED_PROPERTY_BITS else sent)
}

/**
 * One OKOKCloud measurement.
 *
 * @property takenAt the scale's time of a result or history record; null for a weight in progress or when the
 *   scale's date cannot be a date.
 * @property impedance foot to foot, for scales that measure body composition; null when not measured.
 */
data class OkokCloudMeasurement(
    val channel: CloudV4Channel,
    val property: ScaleProperty,
    val weight: ScaleWeight,
    val takenAt: Instant?,
    val impedance: Impedance? = null,
)

/**
 * Parser of OKOKCloud frames from `FFF1`, `2A9C` and `FA9C`, written from the interface description in the
 * project's protocol notes. As with V4, the characteristic tells a weight in progress from a result or a
 * stored record. A frame is at least 16 bytes:
 * `[0..1]` `02 00` (weight only) or `06 03`/`06 23` (with impedance), `[2..3]` year LE, `[4..8]` month, day,
 * hour, minute, second on the scale's clock (the phone's local time, set on connecting), `[9..10]` impedance
 * LE × 0.1, `[11..12]` weight LE, `[15]` property with the Cloud decimals layout.
 */
object OkokCloudParser {
    fun parse(channel: CloudV4Channel, frame: ByteArray, productId: Int?, zone: ZoneId): OkokCloudMeasurement? {
        if (frame.size < MIN_SIZE) return null
        val withImpedance = when (frame[0].toUByteInt() to frame[1].toUByteInt()) {
            0x02 to 0x00 -> false
            0x06 to 0x03, 0x06 to 0x23 -> true
            else -> return null
        }
        val property = OkokCloud.property(frame[15].toUByteInt(), productId)
        val impedance = frame.uIntLe(9, 2).takeIf { withImpedance && it != 0 }?.let { Impedance(Impedance.FOOT_TO_FOOT, it / 10.0) }
        return OkokCloudMeasurement(
            channel = channel,
            property = property,
            weight = ScaleWeight.of(frame.uIntLe(11, 2), property, DigitsLayout.CLOUD),
            takenAt = if (channel == CloudV4Channel.LIVE) null else timeOf(frame, zone),
            impedance = impedance,
        )
    }

    private fun timeOf(d: ByteArray, zone: ZoneId): Instant? = try {
        LocalDateTime.of(d.uIntLe(2, 2), d[4].toUByteInt(), d[5].toUByteInt(), d[6].toUByteInt(), d[7].toUByteInt(), d[8].toUByteInt())
            .atZone(zone).toInstant()
    } catch (_: DateTimeException) {
        null
    }

    private const val MIN_SIZE = 16
}

/** Commands an OKOKCloud scale takes after connecting: the time, then the unit. */
object OkokCloudCommands {
    /** For `2A08`: the phone's local time as year LE, month, day, hour, minute, second (7 bytes, no weekday). */
    fun time(now: LocalDateTime): ByteArray = byteArrayOf(
        now.year.toByte(),
        (now.year shr 8).toByte(),
        now.monthValue.toByte(),
        now.dayOfMonth.toByte(),
        now.hour.toByte(),
        now.minute.toByte(),
        now.second.toByte(),
    )

    /** For `FFF2`: the unit the scale shows, as for V4. */
    fun unit(unit: ScaleUnit): ByteArray = CloudV4Commands.unit(unit)
}
