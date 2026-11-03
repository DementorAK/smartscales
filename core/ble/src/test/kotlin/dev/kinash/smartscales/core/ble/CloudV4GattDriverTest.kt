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
import java.util.UUID

/** Frames are synthetic, built from the protocol description: no V4 scale has been at hand. */
class CloudV4GattDriverTest {
    private val now = Instant.parse("2026-11-03T08:00:00Z")
    private val driver = CloudV4GattDriver { now }
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

    private fun protocol() = driver.gattProtocol("OKOK_CLOUD_V4", mac)!!

    private fun le(value: Int) = byteArrayOf(value.toByte(), (value shr 8).toByte())

    private fun be32(value: Long) = byteArrayOf((value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte())

    /** A `C5` frame: kilograms with one decimal, [weightRaw] tenths, impedance in tenths of an ohm, heart rate. */
    private fun c5(time: Instant, weightRaw: Int, ohmsX10: Int, heartRate: Int) =
        byteArrayOf(0xC5.toByte(), 0x04) + be32(time.epochSecond) + le(weightRaw) + le(ohmsX10) + heartRate.toByte()

    @Test
    fun onlyV4ScalesAreItsOwn() {
        assertNull(driver.gattProtocol("OKOK_V2", mac))
        assertNull(driver.gattProtocol("OKOK_CLOUD", mac))
    }

    @Test
    fun timeUnitAndUserGoFirstThenTheSubscriptions() = runTest {
        val session = RecordingSession()
        val user = ScaleUser(userId = 3, male = true, ageYears = 36, heightCm = 180, weightKg = 80.5)
        protocol().start(session, GattContext(now, ScaleUnit.KILOGRAM, user))
        assertEquals(
            listOf(
                "write 2A08 ${be32(now.epochSecond).toHex()}",
                "write FFF2 01 00",
                "write FFF2 A0 00 00 00 03 A4 B4 03 25",
                "subscribe FA9C",
                "subscribe FFF1",
                "subscribe 2A9C",
            ),
            session.operations,
        )
    }

    @Test
    fun aScaleWithoutTimeOrHistoryIsStillRead() = runTest {
        val session = RecordingSession(missing = setOf(ChipseaGatt.CURRENT_TIME, ChipseaGatt.BODY_COMPOSITION_HISTORY))
        protocol().start(session, GattContext(now, ScaleUnit.POUND, user = null))
        assertEquals(listOf("write FFF2 01 02", "subscribe FFF1", "subscribe 2A9C"), session.operations)
    }

    @Test
    fun resultHasImpedanceHeartRateAndTheScaleTime() {
        val taken = now.minusSeconds(20)
        val events = protocol().onNotification(GattNotification(ChipseaGatt.BODY_COMPOSITION_MEASUREMENT, c5(taken, 805, 5123, 72)))
        val result = events.single() as GattEvent.Result
        assertEquals(taken, result.takenAt)
        assertEquals(80.5, result.reading.weight!!.kilograms, 1e-9)
        assertEquals(listOf(Impedance(Impedance.FOOT_TO_FOOT, 512.3)), result.reading.impedances)
        assertEquals(72, result.reading.heartRateBpm)
        assertTrue(result.reading.locked)
    }

    @Test
    fun aResultFromAScaleWithAnUnsetClockIsKeptWithThePhoneTime() {
        val events = protocol().onNotification(
            GattNotification(ChipseaGatt.BODY_COMPOSITION_MEASUREMENT, c5(Instant.parse("2000-01-01T00:00:00Z"), 805, 0, 0)),
        )
        assertEquals(now, (events.single() as GattEvent.Result).takenAt)
    }

    @Test
    fun historyWithAnImplausibleTimeIsDropped() {
        val p = protocol()
        val kept = p.onNotification(GattNotification(ChipseaGatt.BODY_COMPOSITION_HISTORY, c5(now.minusSeconds(86_400), 790, 0, 0)))
        assertEquals(now.minusSeconds(86_400), (kept.single() as GattEvent.History).takenAt)
        assertTrue(p.onNotification(GattNotification(ChipseaGatt.BODY_COMPOSITION_HISTORY, c5(Instant.parse("2001-01-01T00:00:00Z"), 790, 0, 0))).isEmpty())
    }

    @Test
    fun liveWeightIsNotLockedAndEmptyResultsAreNotSaved() {
        val p = protocol()
        val live = p.onNotification(GattNotification(ChipseaGatt.CHIPSEA_RX, c5(now, 640, 0, 0))).single() as GattEvent.Live
        assertEquals(64.0, live.reading.weight!!.kilograms, 1e-9)
        assertTrue(!live.reading.locked)
        assertTrue(p.onNotification(GattNotification(ChipseaGatt.BODY_COMPOSITION_MEASUREMENT, c5(now, 0, 0, 0))).isEmpty())
        // Frames on characteristics the protocol does not read are ignored
        assertTrue(p.onNotification(GattNotification(ChipseaGatt.CHIPSEA_TX, c5(now, 640, 0, 0))).isEmpty())
    }

    @Test
    fun theDescriptionSaysItIsNotTestedOnARealScale() {
        val description = ChipseaAdvertisementDriver().describe("OKOK_CLOUD_V4")!!
        assertTrue(description.canWeigh)
        assertTrue(!description.testedOnRealScale)
        assertTrue(ChipseaAdvertisementDriver().describe("OKOK_V2")!!.testedOnRealScale)
        assertTrue(!ChipseaAdvertisementDriver().describe("OKOK_CLOUD")!!.canWeigh)
    }
}
