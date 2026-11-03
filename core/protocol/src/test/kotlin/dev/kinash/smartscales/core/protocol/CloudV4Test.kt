package dev.kinash.smartscales.core.protocol

import dev.kinash.smartscales.core.model.Impedance
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Frames here are synthetic, built field by field from the protocol description: no CloudV4 scale has been
 * available to record real ones.
 */
class CloudV4Test {
    private val time = Instant.parse("2026-11-03T07:30:00Z")

    /** Kilograms with one decimal in the Cloud layout: bits 2..1 = `10`. */
    private val kg1 = 0x04

    private fun le(value: Int) = byteArrayOf(value.toByte(), (value shr 8).toByte())

    private fun be32(value: Long) = byteArrayOf((value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte())

    private fun le32(value: Long) = be32(value).reversedArray()

    private fun frame(type: Int, property: Int, weightRaw: Int, ohmsX10: Int, vararg tail: Byte) =
        byteArrayOf(type.toByte(), property.toByte()) + be32(time.epochSecond) + le(weightRaw) + le(ohmsX10) + tail

    @Test
    fun c4ResultHasTimeWeightAndFootToFootImpedance() {
        val m = CloudV4Parser().parse(CloudV4Channel.RESULT, frame(0xC4, kg1, 805, 5123))!!
        assertEquals(80.5, m.weight.kilograms, 1e-9)
        assertEquals(time, m.takenAt)
        assertEquals(listOf(Impedance(Impedance.FOOT_TO_FOOT, 512.3)), m.impedances)
        assertNull(m.heartRateBpm)
    }

    @Test
    fun liveWeightHasNoTimeOfItsOwn() {
        val m = CloudV4Parser().parse(CloudV4Channel.LIVE, frame(0xC4, kg1, 640, 0))!!
        assertNull(m.takenAt)
        assertEquals(64.0, m.weight.kilograms, 1e-9)
        assertTrue(m.impedances.isEmpty())
    }

    @Test
    fun c5HeartRateAndMeasuringFlagsDoNotChangeTheDecimals() {
        // Bits 6 and 7 say impedance and heart rate are being measured; decimals stay in bits 2..1
        val m = CloudV4Parser().parse(CloudV4Channel.LIVE, frame(0xC5, kg1 or 0xC0, 805, 0, 0))!!
        assertEquals(80.5, m.weight.kilograms, 1e-9)
        assertTrue(m.measuringImpedance)
        assertTrue(m.measuringHeartRate)
        assertNull(m.heartRateBpm)

        val done = CloudV4Parser().parse(CloudV4Channel.RESULT, frame(0xC5, kg1, 805, 5123, 72))!!
        assertEquals(72, done.heartRateBpm)
        assertFalse(done.measuringImpedance)
    }

    @Test
    fun weightInPoundsWithTwoDecimals() {
        // Pounds: bits 4..3 = `10`; two decimals: bits 2..1 = `00`
        val m = CloudV4Parser().parse(CloudV4Channel.RESULT, frame(0xC4, 0x10, 17750, 0))!!
        assertEquals(ScaleUnit.POUND, m.weight.unit)
        assertEquals(177.5, m.weight.value, 1e-9)
        assertEquals(80.512, m.weight.kilograms, 1e-3)
    }

    @Test
    fun c8AddsFiveChannelsAfterTheFootToFootValue() {
        val channels = (1..5).flatMap { le(3000 + it).toList() }.toByteArray()
        val m = CloudV4Parser().parse(CloudV4Channel.RESULT, frame(0xC8, kg1, 805, 5000, *channels))!!
        assertEquals(Impedance(Impedance.FOOT_TO_FOOT, 500.0), m.impedances.first())
        assertEquals((1..5).map { Impedance(it, (3000 + it) / 10.0) }, m.impedances.drop(1))
    }

    @Test
    fun c8WithoutImpedanceHasNoChannels() {
        val channels = (1..5).flatMap { le(3000 + it).toList() }.toByteArray()
        val m = CloudV4Parser().parse(CloudV4Channel.LIVE, frame(0xC8, kg1, 805, 0, *channels))!!
        assertTrue(m.impedances.isEmpty())
    }

    private fun cbFirst(packet: Int = 0x00) = byteArrayOf(0xCB.toByte(), packet.toByte(), kg1.toByte()) +
        le32(time.epochSecond) + le(805) + (1..5).flatMap { le(4000 + it).toList() }

    private fun cbSecond(heartRate: Int = 68) = byteArrayOf(0xCB.toByte(), 0x01) +
        (1..5).flatMap { le(3500 + it).toList() } + heartRate.toByte()

    @Test
    fun cbJoinsTwoPacketsIntoOneMeasurementAtTwoFrequencies() {
        val parser = CloudV4Parser()
        assertNull(parser.parse(CloudV4Channel.RESULT, cbFirst()))
        val m = parser.parse(CloudV4Channel.RESULT, cbSecond())!!
        assertEquals(80.5, m.weight.kilograms, 1e-9)
        // The first packet keeps its time little-endian, unlike the other frames
        assertEquals(time, m.takenAt)
        assertEquals(68, m.heartRateBpm)
        assertEquals((1..5).map { Impedance(it, (4000 + it) / 10.0, frequencyIndex = 0) }, m.impedances.take(5))
        assertEquals((1..5).map { Impedance(it, (3500 + it) / 10.0, frequencyIndex = 1) }, m.impedances.drop(5))
        assertTrue(m.impedances.none { it.channel == Impedance.FOOT_TO_FOOT })
    }

    @Test
    fun cbSecondPacketAloneOrFromAnotherCharacteristicIsDropped() {
        assertNull(CloudV4Parser().parse(CloudV4Channel.RESULT, cbSecond()))
        val parser = CloudV4Parser()
        parser.parse(CloudV4Channel.HISTORY, cbFirst())
        assertNull(parser.parse(CloudV4Channel.RESULT, cbSecond()))
        // The first packet was used up: a repeated second one does not make a measurement either
        assertNull(parser.parse(CloudV4Channel.HISTORY, cbSecond()))
    }

    @Test
    fun unknownOrShortFramesAreIgnored() {
        val parser = CloudV4Parser()
        assertNull(parser.parse(CloudV4Channel.RESULT, byteArrayOf()))
        assertNull(parser.parse(CloudV4Channel.RESULT, byteArrayOf(0xC4.toByte(), 0x04, 0x00)))
        assertNull(parser.parse(CloudV4Channel.RESULT, frame(0xCA, kg1, 805, 0)))
    }

    @Test
    fun channelsAreKnownByTheirCharacteristic() {
        assertEquals(CloudV4Channel.LIVE, CloudV4Channel.of(ChipseaGatt.CHIPSEA_RX))
        assertEquals(CloudV4Channel.RESULT, CloudV4Channel.of(ChipseaGatt.BODY_COMPOSITION_MEASUREMENT))
        assertEquals(CloudV4Channel.HISTORY, CloudV4Channel.of(ChipseaGatt.BODY_COMPOSITION_HISTORY))
        assertNull(CloudV4Channel.of(ChipseaGatt.CHIPSEA_TX))
        assertEquals("0000fa9c-0000-1000-8000-00805f9b34fb", ChipseaGatt.BODY_COMPOSITION_HISTORY.toString())
    }

    @Test
    fun timeIsSecondsBigEndian() {
        assertArrayEquals(be32(time.epochSecond), CloudV4Commands.time(time))
    }

    @Test
    fun unitCommandNamesTheUnit() {
        assertArrayEquals(byteArrayOf(0x01, 0x00), CloudV4Commands.unit(ScaleUnit.KILOGRAM))
        assertArrayEquals(byteArrayOf(0x01, 0x02), CloudV4Commands.unit(ScaleUnit.POUND))
        assertArrayEquals(byteArrayOf(0x01, 0x03), CloudV4Commands.unit(ScaleUnit.STONE))
    }

    @Test
    fun userCommandPacksSexWithAge() {
        assertArrayEquals(
            byteArrayOf(0xA0.toByte(), 0x00, 0x00, 0x01, 0x02, (0x80 or 36).toByte(), 180.toByte(), 0x03, 0x25),
            CloudV4Commands.user(userId = 0x0102, male = true, ageYears = 36, heightCm = 180, weightKg = 80.5),
        )
        val woman = CloudV4Commands.user(userId = 7, male = false, ageYears = 34, heightCm = 165)
        assertEquals(34, woman[5].toInt())
        assertEquals(0, woman[7].toInt() or woman[8].toInt())
    }

    @Test
    fun recordsFromAnUnsetOrFastClockAreImplausible() {
        val now = Instant.parse("2026-11-03T08:00:00Z")
        assertTrue(ScaleRecords.isPlausibleTime(Instant.parse("2026-11-01T08:00:00Z"), now))
        assertFalse(ScaleRecords.isPlausibleTime(Instant.parse("2000-01-01T00:00:00Z"), now))
        assertTrue(ScaleRecords.isPlausibleTime(now.plusSeconds(4 * 60), now))
        assertFalse(ScaleRecords.isPlausibleTime(now.plusSeconds(6 * 60), now))
        assertFalse(ScaleRecords.isPlausibleWeight(0.0))
        assertFalse(ScaleRecords.isPlausibleWeight(600.0))
        assertTrue(ScaleRecords.isPlausibleWeight(80.5))
    }

    @Test
    fun aResultFromAWrongClockTakesThePhoneTime() {
        val now = Instant.parse("2026-11-03T08:00:00Z")
        val scale = Instant.parse("2026-11-03T07:59:30Z")
        assertEquals(scale, ScaleRecords.timeOfResult(scale, now))
        assertEquals(now, ScaleRecords.timeOfResult(Instant.parse("2000-01-01T00:00:00Z"), now))
        assertEquals(now, ScaleRecords.timeOfResult(null, now))
    }

    @Test
    fun userWeightIsRoundedNotCut() {
        val command = CloudV4Commands.user(userId = 1, male = true, ageYears = 40, heightCm = 175, weightKg = 70.99)
        assertEquals(710, (command[7].toInt() and 0xFF shl 8) or (command[8].toInt() and 0xFF))
    }
}
