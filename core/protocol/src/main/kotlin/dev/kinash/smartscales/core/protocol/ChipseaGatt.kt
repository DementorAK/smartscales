package dev.kinash.smartscales.core.protocol

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** GATT services and characteristics of Chipsea scales. */
object ChipseaGatt {
    val CHIPSEA_SERVICE: UUID = uuid16(0xFFF0)

    /** Notifications from the scale: the weight in progress, or frames of the OKOK protocol. */
    val CHIPSEA_RX: UUID = uuid16(0xFFF1)

    /** Commands to the scale. */
    val CHIPSEA_TX: UUID = uuid16(0xFFF2)

    val BODY_COMPOSITION_SERVICE: UUID = uuid16(0x181B)

    /** The final result, by indication. */
    val BODY_COMPOSITION_MEASUREMENT: UUID = uuid16(0x2A9C)

    /** Records the scale kept, by indication; a Chipsea extension of the Body Composition service. */
    val BODY_COMPOSITION_HISTORY: UUID = uuid16(0xFA9C)

    val CURRENT_TIME_SERVICE: UUID = uuid16(0x1805)
    val CURRENT_TIME: UUID = uuid16(0x2A08)

    private fun uuid16(short: Int): UUID = UUID.fromString("0000%04x-0000-1000-8000-00805f9b34fb".format(short))
}

/**
 * Which weighings from a scale to keep. A scale whose clock was never set sends records from 1970 or 2000,
 * and a clock running ahead sends future ones: such records cannot be placed in the history.
 */
object ScaleRecords {
    /** Records before this are from a scale whose clock was never set. */
    val EARLIEST: Instant = LocalDate.of(2010, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant()

    /** A clock may run a little ahead of the phone's. */
    val CLOCK_TOLERANCE: Duration = Duration.ofMinutes(5)

    /** No person weighs this much; the scale sent garbage. */
    const val MAX_WEIGHT_KG = 600.0

    /**
     * Whether a record the scale kept ([history][CloudV4Channel.HISTORY]) can be placed in the history: with an
     * implausible time it cannot, and it is dropped.
     */
    fun isPlausibleTime(takenAt: Instant, now: Instant): Boolean =
        !takenAt.isBefore(EARLIEST) && !takenAt.isAfter(now.plus(CLOCK_TOLERANCE))

    /**
     * The time of a result received just now: the scale's own unless its clock is wrong, then the phone's.
     * The person is standing on the scale, so the weighing is kept whatever the scale's clock says.
     */
    fun timeOfResult(scaleTime: Instant?, now: Instant): Instant =
        scaleTime?.takeIf { isPlausibleTime(it, now) } ?: now

    fun isPlausibleWeight(kilograms: Double): Boolean = kilograms > 0 && kilograms < MAX_WEIGHT_KG
}
