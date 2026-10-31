package dev.kinash.smartscales.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic frames built from the documented layouts; recorded frames from real scales come with task 9.3. */
class ChipseaAdvertisementTest {

    @Test
    fun scanRecordYieldsManufacturerDataWithCompanyIdBytes() {
        val record = bytes("02 01 06 05 09 41 44 56 00 0A FF CA 20 0B 00 00 00 01 01 01 05 00 00")
        assertEquals(listOf(0x01, 0x09, 0xFF), parseAdStructures(record).map { it.type })
        assertEquals("CA 20 0B 00 00 00 01 01 01", hex(manufacturerData(record)!!))
        assertNull(manufacturerData(bytes("02 01 06")))
    }

    @Test
    fun protocolIsDetectedByHeader() {
        assertEquals(ChipseaProtocol.OKOK_BROADCAST, ChipseaProtocol.detect(bytes("CA 20")))
        assertEquals(ChipseaProtocol.OKOK, ChipseaProtocol.detect(bytes("CA 11")))
        assertEquals(ChipseaProtocol.OKOK_V2, ChipseaProtocol.detect(bytes("C0 07")))
        assertEquals(ChipseaProtocol.OKOK_CLOUD, ChipseaProtocol.detect(bytes("FF F0 01")))
        assertEquals(ChipseaProtocol.OKOK_CLOUD_V4, ChipseaProtocol.detect(bytes("FF F0 11")))
        assertNull(ChipseaProtocol.detect(bytes("4C 00 02 15")))
    }

    @Test
    fun broadcastFrameCarriesWeightAndImpedanceOnceLocked() {
        val frame = ChipseaAdvertisement.parse(bytes("CA 20 0B 00 00 00 2A 01 01 05 03 20 13 88"))!!
        assertEquals(ChipseaProtocol.OKOK_BROADCAST, frame.protocol)
        assertEquals(42, frame.productId)
        assertTrue(frame.locked)
        assertTrue(frame.measuresImpedance)
        assertEquals(5, frame.measureSeqNo)
        assertEquals(80.0, frame.weight!!.kilograms, 1e-9)
        assertEquals(500.0, frame.impedanceOhm!!, 1e-9)
    }

    @Test
    fun broadcastFrameBeforeLockHasNoWeight() {
        val frame = ChipseaAdvertisement.parse(bytes("CA 20 0B 00 00 00 2A 01 00 05 03 20 13 88"))!!
        assertFalse(frame.locked)
        assertNull(frame.weight)
        assertNull(frame.measureSeqNo)
    }

    @Test
    fun unitsAndPrecisionComeFromPropertyByte() {
        fun weightWith(property: String, weight: String) =
            ChipseaAdvertisement.parse(bytes("CA 20 0B 00 00 00 2A 00 $property 05 $weight 00 00"))!!.weight!!

        // Pounds with one decimal: 176.4 lb
        assertEquals(176.4 * 0.45359237, weightWith("11", "06 E4").kilograms, 1e-9)
        // Stones: 12 st 8.6 lb
        val stones = weightWith("19", "0C 56")
        assertEquals(ScaleUnit.STONE, stones.unit)
        assertEquals(12, stones.stones)
        assertEquals(8.6, stones.pounds, 1e-9)
        assertEquals(176.6 * 0.45359237, stones.kilograms, 1e-9)
        // Kilograms with two decimals: 80.12 kg; jin with one: 160.2 jin = 80.1 kg
        assertEquals(80.12, weightWith("05", "1F 4C").kilograms, 1e-9)
        assertEquals(80.1, weightWith("09", "06 42").kilograms, 1e-9)
        // No decimals
        assertEquals(80.0, weightWith("03", "00 50").kilograms, 1e-9)
    }

    @Test
    fun okokFramesSwapTypeAndPropertyBetweenVersions() {
        val v11 = ChipseaAdvertisement.parse(bytes("CA 11 0A 01 01 03 20 00 00 00 07"))!!
        assertEquals(ChipseaProtocol.OKOK, v11.protocol)
        assertEquals(0x11, v11.version)
        assertEquals(7, v11.productId)
        assertEquals(80.0, v11.weight!!.kilograms, 1e-9)
        assertFalse("GATT scales lock over the connection", v11.locked)

        val v10 = ChipseaAdvertisement.parse(bytes("CA 10 0A 01 00 03 20 00 00 00 07"))!!
        assertEquals(80.0, v10.weight!!.kilograms, 1e-9)

        val weightOnlyV10 = ChipseaAdvertisement.parse(bytes("CA 10 0A 00 00 00 00 00 03 1F"))!!
        assertEquals(79.9, weightOnlyV10.weight!!.kilograms, 1e-9)
    }

    @Test
    fun cloudFrameHasLittleEndianWeight() {
        // Cloud precision: bits 2..1 = 10 → one decimal
        val cloud = ChipseaAdvertisement.parse(bytes("FF F0 01 04 20 03 00 00 00 09"))!!
        assertEquals(ChipseaProtocol.OKOK_CLOUD, cloud.protocol)
        assertEquals(9, cloud.productId)
        assertEquals(80.0, cloud.weight!!.kilograms, 1e-9)

        // CloudV4 precision: bits 6..5 = 10 → one decimal
        val v4 = ChipseaAdvertisement.parse(bytes("FF F0 11 40 20 03 00 00 00 09"))!!
        assertEquals(ChipseaProtocol.OKOK_CLOUD_V4, v4.protocol)
        assertEquals(80.0, v4.weight!!.kilograms, 1e-9)

        // A product with a fixed property: kilograms with one decimal whatever the byte says
        val fixed = ChipseaAdvertisement.parse(bytes("FF F0 01 00 20 03 FA FB FC FD"))!!
        assertEquals(80.0, fixed.weight!!.kilograms, 1e-9)
    }

    @Test
    fun plainV2Frame() {
        val frame = ChipseaAdvertisement.parse(bytes("C0 07 03 20 13 88 00 01 21"))!!
        assertEquals(ChipseaProtocol.OKOK_V2, frame.protocol)
        assertFalse(frame.encrypted)
        assertNull(frame.scaleMac)
        assertTrue(frame.locked)
        assertTrue(frame.measuresImpedance)
        assertEquals(7, frame.measureSeqNo)
        assertEquals(1, frame.productId)
        assertEquals(80.0, frame.weight!!.kilograms, 1e-9)
        assertEquals(500.0, frame.impedanceOhm!!, 1e-9)
    }

    @Test
    fun encryptedV2FrameNeedsKeyButRevealsScaleMac() {
        val key = byteArrayOf(0x12, 0x34, 0x56, 0x78)
        val frame = encryptedFrame(key)

        val withoutKey = ChipseaAdvertisement.parse(frame)!!
        assertTrue(withoutKey.encrypted)
        assertTrue(withoutKey.needsKey)
        assertNull(withoutKey.weight)
        assertEquals("AA:BB:CC:DD:EE:FF", withoutKey.scaleMac)

        val withKey = ChipseaAdvertisement.parse(frame, key)!!
        assertFalse(withKey.needsKey)
        assertEquals(80.0, withKey.weight!!.kilograms, 1e-9)
        assertEquals(500.0, withKey.impedanceOhm!!, 1e-9)
        assertEquals("AA:BB:CC:DD:EE:FF", withKey.scaleMac)
    }

    @Test
    fun shortOrForeignFramesAreRejected() {
        assertNull(ChipseaAdvertisement.parse(bytes("CA 20 0B 00")))
        assertNull(ChipseaAdvertisement.parse(bytes("C0 07 03")))
        assertNull(ChipseaAdvertisement.parse(bytes("4C 00 02 15 00 00 00 00 00 00")))
    }

    /** `C0` frame of 80.0 kg and 500.0 Ω from the scale AA:BB:CC:DD:EE:FF, encrypted with [key]. */
    private fun encryptedFrame(key: ByteArray): ByteArray {
        val weight = V2Cipher.encrypt(800, key)
        val impedance = V2Cipher.encrypt(5000 or 0x8000, key)
        val macTail = bytes("CC DD EE FF")
        val macHead = V2Cipher.encrypt(0xAABB, macTail)
        return byteArrayOf(
            0xC0.toByte(), 0x07,
            (weight shr 8).toByte(), weight.toByte(),
            (impedance shr 8).toByte(), impedance.toByte(),
            0x00, 0x01, 0x21,
            (macHead shr 8).toByte(), macHead.toByte(),
        ) + macTail
    }

    private fun bytes(hex: String): ByteArray = hex.split(' ').map { it.toInt(16).toByte() }.toByteArray()

    private fun hex(bytes: ByteArray) = bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
}
