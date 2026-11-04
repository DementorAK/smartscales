package dev.kinash.smartscales.core.protocol

import dev.kinash.smartscales.core.model.Impedance
import java.time.Instant
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Where an OKOKCloud or OKOKCloud V4 frame came from. The frames carry no "locked" or "history" flag: the
 * characteristic tells a weight in progress from a result or a record the scale kept while no phone was near.
 */
enum class CloudV4Channel(val characteristic: UUID) {
    /** `FFF1` notifications: the weight while the person stands on the scale. */
    LIVE(ChipseaGatt.CHIPSEA_RX),

    /** `2A9C` indications: the final result of this weighing. */
    RESULT(ChipseaGatt.BODY_COMPOSITION_MEASUREMENT),

    /** `FA9C` indications: a weighing the scale stored earlier. */
    HISTORY(ChipseaGatt.BODY_COMPOSITION_HISTORY),
    ;

    companion object {
        fun of(characteristic: UUID): CloudV4Channel? = entries.firstOrNull { it.characteristic == characteristic }
    }
}

/**
 * One OKOKCloud V4 measurement.
 *
 * @property takenAt the scale's time of a result or history record; null for a weight in progress.
 * @property impedances foot to foot in channel 0 ([Impedance.FOOT_TO_FOOT]) for 4-electrode frames and `C8`;
 *   8-electrode frames add channels 1..5 in the order the scale sends them, `CB` at two frequencies.
 *   Which body segment each of channels 1..5 measures is not known (no real frames yet), so they are kept by
 *   number only. `CB` sends no foot-to-foot value at all: the built-in formulas take channel 0, so a `CB`
 *   weighing has weight but no body composition until equations for 8 electrodes are added (0.6).
 *   Zero values (not measured) are left out.
 * @property measuringImpedance / [measuringHeartRate] the scale is still measuring them (`C5`, `CB`).
 */
data class CloudV4Measurement(
    val channel: CloudV4Channel,
    val property: ScaleProperty,
    val weight: ScaleWeight,
    val takenAt: Instant?,
    val impedances: List<Impedance> = emptyList(),
    val heartRateBpm: Int? = null,
    val measuringImpedance: Boolean = false,
    val measuringHeartRate: Boolean = false,
)

/**
 * Parser of OKOKCloud V4 frames (`C4`, `C5`, `C8`, `CB`), written from the interface description in the
 * project's protocol notes. A `CB` measurement comes in two notifications, so the parser keeps the first
 * one until the second arrives; use one parser per connection.
 *
 * GATT frames take the number of decimals from bits 2..1 of the property ([DigitsLayout.CLOUD]); bits 6 and 7
 * say whether impedance and heart rate are still being measured. The V4 digits layout is for advertisements only.
 */
class CloudV4Parser {
    private var pendingCb: Pair<CloudV4Channel, ByteArray>? = null

    /** The measurement in [frame], or null while a `CB` waits for its second packet or for a frame it cannot read. */
    fun parse(channel: CloudV4Channel, frame: ByteArray): CloudV4Measurement? {
        if (frame.isEmpty()) return null
        return when (frame[0].toUByteInt()) {
            TYPE_C4 -> parseC4(channel, frame, heartRate = false, eightElectrodes = false)
            TYPE_C5 -> parseC4(channel, frame, heartRate = true, eightElectrodes = false)
            TYPE_C8 -> parseC4(channel, frame, heartRate = false, eightElectrodes = true)
            TYPE_CB -> parseCb(channel, frame)
            else -> null
        }
    }

    /** `C4`/`C5`/`C8`: `[1]` property, `[2..5]` time BE, `[6..7]` weight LE, `[8..9]` impedance LE × 0.1. */
    private fun parseC4(channel: CloudV4Channel, d: ByteArray, heartRate: Boolean, eightElectrodes: Boolean): CloudV4Measurement? {
        if (d.size < if (heartRate) 11 else 10) return null
        val property = ScaleProperty(d[1].toUByteInt())
        val footToFoot = ohms(d.uIntLe(8, 2))
        val impedances = buildList {
            footToFoot?.let { add(Impedance(Impedance.FOOT_TO_FOOT, it)) }
            // C8 sends five more channels only after the scale has measured impedance
            if (eightElectrodes && footToFoot != null && d.size >= 20) {
                for (channelIndex in 0 until SEGMENTS) {
                    ohms(d.uIntLe(10 + channelIndex * 2, 2))?.let { add(Impedance(channelIndex + 1, it)) }
                }
            }
        }
        return CloudV4Measurement(
            channel = channel,
            property = property,
            weight = ScaleWeight.of(d.uIntLe(6, 2), property, DigitsLayout.CLOUD),
            takenAt = timeOf(channel) { d.uInt32Be(2) },
            impedances = impedances,
            heartRateBpm = if (heartRate) d[10].toUByteInt().takeIf { it > 0 } else null,
            measuringImpedance = heartRate && property.bits and MEASURING_IMPEDANCE != 0,
            measuringHeartRate = heartRate && property.bits and MEASURING_HEART_RATE != 0,
        )
    }

    /**
     * `CB`, two packets told apart by bits 1..0 of `[1]`. First: `[2]` property, `[3..6]` time LE, `[7..8]` weight
     * LE, `[9..18]` five impedances at the first frequency. Second: `[2..11]` five at the second, `[12]` heart rate.
     */
    private fun parseCb(channel: CloudV4Channel, d: ByteArray): CloudV4Measurement? {
        if (d.size < 2) return null
        when (d[1].toUByteInt() and 0x03) {
            0 -> {
                pendingCb = if (d.size >= 19) channel to d.copyOf() else null
                return null
            }
            1 -> {
                val (firstChannel, first) = pendingCb ?: return null
                pendingCb = null
                // A second packet belongs to the first one only on the same characteristic
                if (firstChannel != channel || d.size < 13) return null
                val property = ScaleProperty(first[2].toUByteInt())
                val impedances = buildList {
                    for (frequency in 0 until 2) {
                        val source = if (frequency == 0) first else d
                        val start = if (frequency == 0) 9 else 2
                        for (channelIndex in 0 until SEGMENTS) {
                            ohms(source.uIntLe(start + channelIndex * 2, 2))
                                ?.let { add(Impedance(channelIndex + 1, it, frequencyIndex = frequency)) }
                        }
                    }
                }
                return CloudV4Measurement(
                    channel = channel,
                    property = property,
                    weight = ScaleWeight.of(first.uIntLe(7, 2), property, DigitsLayout.CLOUD),
                    takenAt = timeOf(channel) { first.uInt32Le(3) },
                    impedances = impedances,
                    heartRateBpm = d[12].toUByteInt().takeIf { it > 0 },
                    measuringImpedance = property.bits and MEASURING_IMPEDANCE != 0,
                    measuringHeartRate = property.bits and MEASURING_HEART_RATE != 0,
                )
            }
            else -> return null
        }
    }

    private inline fun timeOf(channel: CloudV4Channel, seconds: () -> Long): Instant? =
        if (channel == CloudV4Channel.LIVE) null else Instant.ofEpochSecond(seconds())

    private fun ohms(raw: Int): Double? = if (raw == 0) null else raw / 10.0

    private companion object {
        const val TYPE_C4 = 0xC4
        const val TYPE_C5 = 0xC5
        const val TYPE_C8 = 0xC8
        const val TYPE_CB = 0xCB
        const val SEGMENTS = 5
        const val MEASURING_IMPEDANCE = 0x40
        const val MEASURING_HEART_RATE = 0x80
    }
}

/** Commands an OKOKCloud V4 scale takes after connecting, in this order: time, unit, then the current user. */
object CloudV4Commands {
    /** For `2A08`: seconds since 1970, four bytes big-endian (not the standard Current Time format). */
    fun time(now: Instant): ByteArray {
        val seconds = now.epochSecond
        return byteArrayOf((seconds shr 24).toByte(), (seconds shr 16).toByte(), (seconds shr 8).toByte(), seconds.toByte())
    }

    /** For `FFF2`: the unit the scale shows. */
    fun unit(unit: ScaleUnit): ByteArray = byteArrayOf(0x01, unit.code.toByte())

    /**
     * For `FFF2`: the person on the scale, so it can measure body composition for them. [userId] is the number
     * the app gives the person on this scale; age in whole years, height in centimetres, weight in kilograms
     * (0 when not known).
     */
    fun user(userId: Int, male: Boolean, ageYears: Int, heightCm: Int, weightKg: Double = 0.0): ByteArray {
        require(ageYears in 0..MAX_AGE) { "Age out of range: $ageYears" }
        require(heightCm in 1..MAX_HEIGHT_CM) { "Height out of range: $heightCm" }
        val weight = (weightKg * 10).roundToInt().coerceIn(0, 0xFFFF)
        return byteArrayOf(
            0xA0.toByte(),
            (userId shr 24).toByte(), (userId shr 16).toByte(), (userId shr 8).toByte(), userId.toByte(),
            (if (male) 0x80 or ageYears else ageYears).toByte(),
            heightCm.toByte(),
            (weight shr 8).toByte(), weight.toByte(),
        )
    }

    /** Ages fit in the seven bits next to the sex bit. */
    const val MAX_AGE = 0x7F
    const val MAX_HEIGHT_CM = 0xFF
}
