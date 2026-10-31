package dev.kinash.smartscales.core.ble

import dev.kinash.smartscales.core.model.Impedance
import dev.kinash.smartscales.core.protocol.V2Cipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChipseaAdvertisementDriverTest {
    private val driver = ChipseaAdvertisementDriver()
    private val noKeys = KeyProvider { null }

    @Test
    fun broadcastScaleIsSupportedForWeighing() {
        val support = driver.supportFor(advertisement("CA 20 0B 00 00 00 2A 01 00 05 00 00 00 00"))!!
        assertEquals("OKOK_BROADCAST", support.protocol)
        assertEquals("AA:BB:CC:00:11:22", support.scaleMac)
        assertEquals(42, support.productId)
        assertEquals(LinkMode.BROADCAST_ONLY, support.description.linkMode)
        assertTrue(support.description.canWeigh)
    }

    @Test
    fun gattScaleShowsOnlyPreview() {
        val support = driver.supportFor(advertisement("CA 11 0A 01 01 03 20 00 00 00 07"))!!
        assertEquals(LinkMode.CONNECT_GATT, support.description.linkMode)
        assertFalse(support.description.canWeigh)
        assertTrue(DeviceCapability.HISTORY_READ in support.description.capabilities)
        assertEquals(support.description, driver.describe("OKOK"))

        val reading = driver.onAdvertisement(advertisement("CA 11 0A 01 01 03 20 00 00 00 07"), noKeys)!!
        assertEquals(80.0, reading.weight!!.kilograms, 1e-9)
        assertFalse(reading.locked)
    }

    @Test
    fun lockedReadingCarriesImpedanceAndProperty() {
        val reading = driver.onAdvertisement(advertisement("C0 07 03 20 13 88 00 01 21"), noKeys)!!
        assertTrue(reading.locked)
        assertEquals(7, reading.measureSeqNo)
        assertEquals(0x21, reading.propertyBits)
        assertEquals(listOf(Impedance(Impedance.FOOT_TO_FOOT, 500.0)), reading.impedances)
    }

    @Test
    fun encryptedScaleIsKeyedByItsOwnMac() {
        val key = byteArrayOf(0x12, 0x34, 0x56, 0x78)
        val frame = encryptedV2(key)
        assertEquals("AA:BB:CC:DD:EE:FF", driver.supportFor(advertisement(frame))!!.scaleMac)

        val locked = driver.onAdvertisement(advertisement(frame), noKeys)!!
        assertTrue(locked.needsKey)
        assertNull(locked.weight)
        assertFalse(locked.locked)

        val keys = KeyProvider { mac -> key.takeIf { mac == "AA:BB:CC:DD:EE:FF" } }
        val open = driver.onAdvertisement(advertisement(frame), keys)!!
        assertEquals(80.0, open.weight!!.kilograms, 1e-9)
        assertTrue(open.locked)
    }

    @Test
    fun noiseAndForeignDevicesAreDropped() {
        // 0.0 kg is no weight
        val empty = driver.onAdvertisement(advertisement("C0 07 00 00 00 00 00 01 21"), noKeys)!!
        assertNull(empty.weight)
        assertFalse(empty.locked)
        assertNull(driver.supportFor(advertisement("4C 00 02 15 00 00 00 00 00 00")))
        assertNull(driver.supportFor(advertisement("CA 20 0B 00 00 00 2A 01 00 05 00 00 00 00", name = "CS-Rope-1")))
    }

    private fun advertisement(manufacturerData: String, name: String? = null) = advertisement(bytes(manufacturerData), name)

    private fun advertisement(manufacturerData: ByteArray, name: String? = null): ScannedAdvertisement {
        val record = byteArrayOf(2, 1, 6, (manufacturerData.size + 1).toByte(), 0xFF.toByte()) + manufacturerData
        return ScannedAdvertisement("aa:bb:cc:00:11:22", name, rssi = -60, record = record, receivedAtMs = 0)
    }

    private fun encryptedV2(key: ByteArray): ByteArray {
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

    private fun bytes(hex: String) = hex.split(' ').map { it.toInt(16).toByte() }.toByteArray()
}
