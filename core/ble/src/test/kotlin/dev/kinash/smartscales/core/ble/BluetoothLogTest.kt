package dev.kinash.smartscales.core.ble

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.StringWriter
import java.time.ZoneOffset

class BluetoothLogTest {
    @get:Rule
    val folder = TemporaryFolder()

    private var now = 1_761_900_000_000L

    /** File work runs right in the calling thread, so the test sees it at once. */
    private fun newLog() = BluetoothLog(folder.root, clock = { now }, scope = CoroutineScope(Dispatchers.Unconfined))

    @Test
    fun nothingIsRecordedUntilSwitchedOn() {
        val log = newLog()
        log.record(scale(), "OKOK_V2")
        assertEquals(0, log.count.value)

        log.setRecording(true)
        log.record(scale(), "OKOK_V2")
        assertEquals(1, log.count.value)
    }

    @Test
    fun repeatsOfTheSameFrameAreSkippedForAWhile() {
        val log = newLog().apply { setRecording(true) }
        log.record(scale(), "OKOK_V2")
        now += 1_000
        log.record(scale(), "OKOK_V2")
        log.record(scale(weight = 0x21), "OKOK_V2")
        now += BluetoothLog.REPEAT_MS
        log.record(scale(weight = 0x21), "OKOK_V2")
        assertEquals(3, log.count.value)
    }

    @Test
    fun otherDevicesOnlyInAllDevicesMode() {
        val log = newLog().apply { setRecording(true) }
        log.record(other(), null)
        assertEquals(0, log.count.value)

        log.setAllDevices(true)
        log.record(other(), null)
        assertEquals(1, log.count.value)

        // Switching recording off ends the mode
        log.setRecording(false)
        assertFalse(log.allDevices.value)
    }

    @Test
    fun exportReplacesAddressesWithStableAliases() = runBlocking {
        val log = newLog().apply {
            setRecording(true)
            setAllDevices(true)
        }
        log.record(scale(), "OKOK_V2")
        now += 1
        log.record(other(), null)
        now += 1
        log.record(scale(weight = 0x21), "OKOK_V2")

        val out = StringWriter()
        log.export(out, listOf("App 0.2.0"), ZoneOffset.UTC)
        val text = out.toString()

        assertFalse(text.contains("11:22:33"))
        assertFalse(text.contains("AA:BB:CC"))
        assertTrue(text.contains("# App 0.2.0"))
        val rows = text.lines().filter { it.isNotEmpty() && !it.startsWith("#") }.map { it.split('\t') }
        assertEquals(listOf("scale-1", "device-1", "scale-1"), rows.map { it[1] })
        assertEquals("2025-10-31T08:40:00Z", rows[0][0])
        assertEquals("OKOK_V2", rows[0][3])
        assertEquals("02 01 06 05 FF C0 01 03 20", rows[0][6])
        assertEquals("Phone", rows[1][5])
    }

    @Test
    fun zeroPaddingAfterTheAdvertisementIsCut() {
        val record = byteArrayOf(2, 1, 6, 0, 0, 0)
        assertEquals("02 01 06", advertisingPart(record).toHex())
    }

    @Test
    fun clearEmptiesTheLog() {
        val log = newLog().apply { setRecording(true) }
        log.record(scale(), "OKOK_V2")
        log.clear()
        assertEquals(0, log.count.value)
        assertEquals(emptyList<BluetoothLog.AliasedEntry>(), log.recent.value)
    }

    @Test
    fun logAndSwitchSurviveRestart() {
        newLog().apply {
            setRecording(true)
            record(scale(), "OKOK_V2")
        }
        val reopened = newLog()
        assertTrue(reopened.recording.value)
        assertEquals(1, reopened.count.value)
        assertEquals("scale-1", reopened.recent.value.single().alias)
    }

    @Test
    fun deviceRecognisedLaterGetsScaleAlias() = runBlocking {
        val log = newLog().apply {
            setRecording(true)
            setAllDevices(true)
        }
        log.record(scale(), null)
        assertEquals("device-1", log.recent.value.single().alias)

        now += 1
        log.record(scale(weight = 0x21), "OKOK_V2")
        assertEquals(listOf("scale-1", "scale-1"), log.recent.value.map { it.alias })

        val out = StringWriter()
        log.export(out, emptyList(), ZoneOffset.UTC)
        val devices = out.toString().lines().filter { it.isNotEmpty() && !it.startsWith("#") }.map { it.split('\t')[1] }
        assertEquals(listOf("scale-1", "scale-1"), devices)
    }

    @Test
    fun aliasFreedByARecognisedScaleIsNotReused() {
        val log = newLog().apply {
            setRecording(true)
            setAllDevices(true)
        }
        log.record(scale(), null)
        now += 1
        log.record(other(), null)
        now += 1
        log.record(scale(weight = 0x21), "OKOK_V2")
        now += 1
        log.record(ScannedAdvertisement("00:00:00:00:00:01", "Watch", -90, byteArrayOf(2, 1, 6), 0), null)
        assertEquals(listOf("scale-1", "device-2", "scale-1", "device-3"), log.recent.value.map { it.alias })
    }

    @Test
    fun addressesInsideFramesAreZeroedOnRequest() = runBlocking {
        val log = newLog().apply { setRecording(true) }
        // A C0 frame carries the last four bytes of the scale's own MAC in the clear
        val c0 = byteArrayOf(2, 1, 6, 16, 0xFF.toByte(), 0xC0.toByte(), 1, 3, 0x20, 0x93.toByte(), 0x88.toByte(), 0, 1, 0x21) +
            byteArrayOf(0x12, 0x34, 0xCC.toByte(), 0xDD.toByte(), 0xEE.toByte(), 0xFF.toByte())
        log.record(ScannedAdvertisement("11:22:33:44:55:66", null, -60, c0, 0), "OKOK_V2")

        val plain = StringWriter().also { log.export(it, emptyList(), ZoneOffset.UTC) }.toString()
        assertTrue(plain.contains("12 34 CC DD EE FF"))

        val hidden = StringWriter().also {
            log.export(it, emptyList(), ZoneOffset.UTC) { listOf("AA:BB:CC:DD:EE:FF") }
        }.toString()
        assertTrue(hidden.contains("12 34 00 00 00 00"))
        assertFalse(hidden.contains("CC DD EE FF"))
    }

    @Test
    fun plainC0FrameLosesItsMacTailWithTheDeviceAddress() = runBlocking {
        val log = newLog().apply { setRecording(true) }
        // As the author's scale sends a locked weighing: an unencrypted C0 ends with the MAC Android reports
        val frame = byteArrayOf(0x10, 0xFF.toByte(), 0xC0.toByte(), 0x48, 0x21, 0x07, 0x17, 0x70, 0x0A, 0x11, 0x25) +
            byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte(), 0xEE.toByte(), 0xFF.toByte())
        log.record(ScannedAdvertisement("aa:bb:cc:dd:ee:ff", null, -70, frame, 0), "OKOK_V2")

        val hidden = StringWriter().also {
            log.export(it, emptyList(), ZoneOffset.UTC) { entry -> listOf(entry.address) }
        }.toString()
        assertTrue(hidden.contains("10 FF C0 48 21 07 17 70 0A 11 25 00 00 00 00 00 00"))
    }

    @Test
    fun fullAddressIsZeroedInBothByteOrders() {
        val address = "AA:BB:CC:DD:EE:FF"
        assertEquals("01 00 00 00 00 00 00 02", hideAddresses("01 AA BB CC DD EE FF 02", listOf(address)))
        assertEquals("01 00 00 00 00 00 00 02", hideAddresses("01 FF EE DD CC BB AA 02", listOf(address)))
    }

    @Test
    fun lastSwitchOfRecordingWinsAfterRestart() {
        newLog().apply {
            setRecording(true)
            setRecording(false)
            setRecording(true)
        }
        assertTrue(newLog().recording.value)
        newLog().setRecording(false)
        assertFalse(newLog().recording.value)
    }

    private fun scale(weight: Int = 0x20) = ScannedAdvertisement(
        address = "11:22:33:44:55:66",
        name = null,
        rssi = -60,
        record = byteArrayOf(2, 1, 6, 5, 0xFF.toByte(), 0xC0.toByte(), 1, 3, weight.toByte(), 0, 0),
        receivedAtMs = 0,
    )

    private fun other() = ScannedAdvertisement("AA:BB:CC:DD:EE:FF", "Phone", -80, byteArrayOf(2, 1, 6), 0)
}
