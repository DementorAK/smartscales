package dev.kinash.smartscales.core.ble

import dev.kinash.smartscales.core.protocol.ChipseaProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanFiltersTest {
    /** An advertisement with [manufacturerData] (with the company id bytes) after the flags. */
    private fun advertisement(address: String, vararg manufacturerData: Int): ScannedAdvertisement {
        val data = manufacturerData.map { it.toByte() }.toByteArray()
        val record = byteArrayOf(2, 1, 6, (data.size + 1).toByte(), 0xFF.toByte()) + data
        return ScannedAdvertisement(address, null, -60, record, 0)
    }

    @Test
    fun aScaleIsLetThroughByItsAddressOrTheCompanyIdOfItsFamily() {
        val cloud = advertisement("11:22:33:44:55:66", 0xFF, 0xF0, 0x10, 0x40)
        assertEquals(0xF0FF, manufacturerIdOf(cloud.record))
        val filters = ScanFilters(setOf("AA:BB:CC:00:11:22"), ChipseaProtocol.OKOK_CLOUD_V4.manufacturerIds)
        assertTrue(filters.matches(cloud))
        // C0 puts the weighing number second, so only its address finds it
        assertTrue(ChipseaProtocol.OKOK_V2.manufacturerIds.isEmpty())
        assertTrue(filters.matches(advertisement("aa:bb:cc:00:11:22", 0xC0, 0x05)))
        assertFalse(filters.matches(advertisement("AA:BB:CC:00:11:23", 0xC0, 0x05)))
        assertEquals(setOf(0x20CA), ChipseaProtocol.OKOK_BROADCAST.manufacturerIds)
        assertNull(manufacturerIdOf(byteArrayOf(2, 1, 6)))
    }
}
