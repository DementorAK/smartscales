package dev.kinash.smartscales.core.ble

import dev.kinash.smartscales.core.model.Impedance
import dev.kinash.smartscales.core.protocol.ChipseaGatt
import dev.kinash.smartscales.core.protocol.ScaleUnit
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** Frames are synthetic, built from the protocol description: no OKOKCloud scale has been at hand. */
class OkokCloudGattDriverTest {
    private val now = Instant.parse("2026-11-03T08:30:15Z")
    private val driver = OkokCloudGattDriver({ now }, { ZoneOffset.UTC })
    private val mac = "AA:BB:CC:00:11:22"

    private class RecordingSession(private val missing: Set<UUID> = emptySet()) : GattSession {
        val operations = mutableListOf<String>()

        override fun hasCharacteristic(service: UUID, characteristic: UUID) = characteristic !in missing

        override suspend fun subscribe(service: UUID, characteristic: UUID) {
            operations += "subscribe ${shortUuid(characteristic)}"
        }

        override suspend fun write(service: UUID, characteristic: UUID, value: ByteArray, withResponse: Boolean) {
            operations += "write ${shortUuid(characteristic)} ${value.toHex()}"
        }
    }

    private fun le(value: Int) = byteArrayOf(value.toByte(), (value shr 8).toByte())

    /** A frame with impedance, kilograms with one decimal, taken at [time] (UTC) on the scale's clock. */
    private fun frame(time: Instant, weightRaw: Int, ohmsX10: Int) = time.atOffset(ZoneOffset.UTC).let { t ->
        byteArrayOf(0x06, 0x03) + le(t.year) + byteArrayOf(t.monthValue.toByte(), t.dayOfMonth.toByte(), t.hour.toByte(), t.minute.toByte(), t.second.toByte()) +
            le(ohmsX10) + le(weightRaw) + byteArrayOf(0, 0, 0x04)
    }

    private fun on(characteristic: UUID, value: ByteArray) = GattNotification(characteristic, value)

    @Test
    fun onlyOkokCloudScalesAreItsOwn() {
        assertNull(driver.gattProtocol("OKOK_CLOUD_V4", mac, "0x10"))
        assertTrue(driver.gattProtocol("OKOK_CLOUD", mac, "0x01") != null)
    }

    @Test
    fun liveAndResultsFirstThenTimeAndUnitThenHistory() = runTest {
        val session = RecordingSession()
        driver.gattProtocol("OKOK_CLOUD", mac, "0x02")!!.start(session, GattContext(now, ScaleUnit.POUND, null))
        assertEquals(
            listOf(
                "subscribe FFF1",
                "subscribe 2A9C",
                "write 2A08 EA 07 0B 03 08 1E 0F",
                "write FFF2 01 02",
                "write FFF2 01 02",
                "subscribe FA9C",
            ),
            session.operations,
        )
    }

    @Test
    fun otherVersionsTakeNoUnitAndAScaleWithoutTimeOrHistoryIsStillRead() = runTest {
        val session = RecordingSession(missing = setOf(ChipseaGatt.CURRENT_TIME, ChipseaGatt.BODY_COMPOSITION_HISTORY))
        driver.gattProtocol("OKOK_CLOUD", mac, "0x01")!!.start(session, GattContext(now, ScaleUnit.POUND, null))
        assertEquals(listOf("subscribe FFF1", "subscribe 2A9C"), session.operations)
    }

    @Test
    fun theClockAndTheUnitAreLeftAloneWhenTheSettingsSaySo() = runTest {
        val session = RecordingSession()
        driver.gattProtocol("OKOK_CLOUD", mac, "0x02")!!.start(session, GattContext(now, unit = null, user = null, syncTime = false))
        assertEquals(listOf("subscribe FFF1", "subscribe 2A9C", "subscribe FA9C"), session.operations)
    }

    @Test
    fun theCharacteristicTellsLiveResultAndHistory() = runTest {
        val protocol = driver.gattProtocol("OKOK_CLOUD", mac, "0x02")!!
        val live = protocol.onNotification(on(ChipseaGatt.CHIPSEA_RX, frame(now, 798, 0))).single() as GattEvent.Live
        assertEquals(79.8, live.reading.weight!!.kilograms, 1e-9)
        assertTrue(!live.reading.locked)

        val takenAt = now.minusSeconds(20)
        val result = protocol.onNotification(on(ChipseaGatt.BODY_COMPOSITION_MEASUREMENT, frame(takenAt, 803, 5123))).single() as GattEvent.Result
        assertEquals(takenAt, result.takenAt)
        assertEquals(listOf(Impedance(Impedance.FOOT_TO_FOOT, 512.3)), result.reading.impedances)
        assertTrue(result.reading.locked)

        val kept = now.minusSeconds(86_400)
        val history = protocol.onNotification(on(ChipseaGatt.BODY_COMPOSITION_HISTORY, frame(kept, 615, 4980))).single() as GattEvent.History
        assertEquals(kept, history.takenAt)
        assertNull(history.reading.userId)
    }

    @Test
    fun aResultFromAWrongClockGetsThePhoneTimeAndSuchHistoryIsDropped() = runTest {
        val protocol = driver.gattProtocol("OKOK_CLOUD", mac, "0x02")!!
        val unset = Instant.parse("2000-01-01T00:00:00Z")
        val result = protocol.onNotification(on(ChipseaGatt.BODY_COMPOSITION_MEASUREMENT, frame(unset, 803, 5123))).single() as GattEvent.Result
        assertEquals(now, result.takenAt)
        assertTrue(protocol.onNotification(on(ChipseaGatt.BODY_COMPOSITION_HISTORY, frame(unset, 803, 5123))).isEmpty())
    }
}
