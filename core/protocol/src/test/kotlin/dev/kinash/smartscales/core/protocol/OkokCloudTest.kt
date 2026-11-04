package dev.kinash.smartscales.core.protocol

import dev.kinash.smartscales.core.model.Impedance
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** Frames are synthetic, built field by field from the protocol description: no OKOKCloud scale was at hand. */
class OkokCloudTest {
    private val zone: ZoneId = ZoneOffset.ofHours(3)

    private fun le(value: Int) = byteArrayOf(value.toByte(), (value shr 8).toByte())

    /** A frame of [type] taken at 2026-11-02 21:05:30 on the scale's clock. */
    private fun frame(type: Pair<Int, Int>, weightRaw: Int, ohmsX10: Int = 0, property: Int = 0x04, month: Int = 11) =
        byteArrayOf(type.first.toByte(), type.second.toByte()) + le(2026) + byteArrayOf(month.toByte(), 2, 21, 5, 30) +
            le(ohmsX10) + le(weightRaw) + byteArrayOf(0, 0) + property.toByte()

    @Test
    fun resultWithImpedanceTakesTheScaleClockInThePhoneZone() {
        val m = OkokCloudParser.parse(CloudV4Channel.RESULT, frame(0x06 to 0x03, weightRaw = 803, ohmsX10 = 5123), null, zone)!!
        assertEquals(80.3, m.weight.kilograms, 1e-9)
        assertEquals(Impedance(Impedance.FOOT_TO_FOOT, 512.3), m.impedance)
        assertEquals(Instant.parse("2026-11-02T18:05:30Z"), m.takenAt)
    }

    @Test
    fun weightOnlyFrameHasNoImpedanceEvenIfTheBytesAreSet() {
        val m = OkokCloudParser.parse(CloudV4Channel.HISTORY, frame(0x02 to 0x00, weightRaw = 615, ohmsX10 = 5123), null, zone)!!
        assertEquals(61.5, m.weight.kilograms, 1e-9)
        assertNull(m.impedance)
    }

    @Test
    fun liveWeightHasNoTime() {
        val m = OkokCloudParser.parse(CloudV4Channel.LIVE, frame(0x06 to 0x23, weightRaw = 790), null, zone)!!
        assertNull(m.takenAt)
        assertNull(m.impedance)
    }

    @Test
    fun theUnitAndDecimalsComeFromByte15() {
        // Pounds (bits 4..3 = 10), one decimal in the Cloud layout
        val m = OkokCloudParser.parse(CloudV4Channel.LIVE, frame(0x02 to 0x00, weightRaw = 1771, property = 0x14), null, zone)!!
        assertEquals(ScaleUnit.POUND, m.weight.unit)
        assertEquals(177.1, m.weight.value, 1e-9)
    }

    @Test
    fun productsWithAFixedPropertyIgnoreByte15() {
        val frame = frame(0x02 to 0x00, weightRaw = 803, property = 0x12)
        val m = OkokCloudParser.parse(CloudV4Channel.LIVE, frame, 1_104_000_001, zone)!!
        assertEquals(ScaleUnit.KILOGRAM, m.weight.unit)
        assertEquals(80.3, m.weight.kilograms, 1e-9)
    }

    @Test
    fun aDateThatCannotBeIsNoTime() {
        assertNull(OkokCloudParser.parse(CloudV4Channel.HISTORY, frame(0x02 to 0x00, weightRaw = 803, month = 13), null, zone)!!.takenAt)
    }

    @Test
    fun shortOrUnknownFramesAreNotRead() {
        assertNull(OkokCloudParser.parse(CloudV4Channel.LIVE, frame(0x02 to 0x00, weightRaw = 803).copyOf(15), null, zone))
        assertNull(OkokCloudParser.parse(CloudV4Channel.LIVE, frame(0x06 to 0x01, weightRaw = 803), null, zone))
    }

    @Test
    fun timeIsLocalWithTheYearLittleEndian() {
        assertArrayEquals(
            byteArrayOf(0xEA.toByte(), 0x07, 11, 3, 8, 30, 15),
            OkokCloudCommands.time(LocalDateTime.parse("2026-11-03T08:30:15")),
        )
        assertArrayEquals(byteArrayOf(0x01, 0x02), OkokCloudCommands.unit(ScaleUnit.POUND))
    }
}
