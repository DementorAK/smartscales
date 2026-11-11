package dev.kinash.smartscales.core.ble

import dev.kinash.smartscales.core.protocol.ChipseaGatt
import dev.kinash.smartscales.core.protocol.ScaleUnit
import dev.kinash.smartscales.core.model.ScaleValues
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** Frames are synthetic, built from the protocol description: no OKOK GATT scale has been at hand. */
class OkokGattDriverTest {
    private val now = Instant.parse("2026-11-03T08:30:15Z")
    private val driver = OkokGattDriver({ now }, { ZoneOffset.UTC })
    private val mac = "AA:BB:CC:00:11:22"
    private val dmitry = ScaleUser(userId = 3, male = true, ageYears = 36, heightCm = 180, weightKg = 80.5)
    private val anna = ScaleUser(userId = 5, male = false, ageYears = 34, heightCm = 165, weightKg = 61.0)

    private class RecordingSession : GattSession {
        val writes = mutableListOf<ByteArray>()
        val subscriptions = mutableListOf<UUID>()

        override fun hasCharacteristic(service: UUID, characteristic: UUID) = true

        override suspend fun subscribe(service: UUID, characteristic: UUID) {
            subscriptions += characteristic
        }

        override suspend fun write(service: UUID, characteristic: UUID, value: ByteArray, withResponse: Boolean) {
            assertEquals(ChipseaGatt.CHIPSEA_TX, characteristic)
            assertTrue("OKOK commands go without response", !withResponse)
            writes += value
        }
    }

    private fun be(value: Int) = byteArrayOf((value shr 8).toByte(), value.toByte())

    private fun be32(value: Long) = byteArrayOf((value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte())

    private fun rx(frame: ByteArray) = GattNotification(ChipseaGatt.CHIPSEA_RX, frame)

    private fun v10(locked: Boolean, weight: Int, visceral: Int = 85) = byteArrayOf(0xCA.toByte(), 0x10, 0x10, 0x00, (if (locked) 0x01 else 0x00).toByte()) +
        be(weight) + be(225) + be(552) + be(380) + be(1650) + be(visceral) + byteArrayOf(31)

    /** One packet of a v1.1 `12`/`13` message: the whole payload, 23 bytes, fits in two; this sends it as one of one. */
    private fun v11(type: Int, time: Long, userId: Int, weight: Int): ByteArray {
        val payload = be32(time) + byteArrayOf(0x01, 0) + be32(userId.toLong()) + be(weight) + be(225) + be(552) + be(400) +
            be(1650) + be(85) + byteArrayOf(31)
        return byteArrayOf(0xCA.toByte(), 0x11, (payload.size + 2).toByte(), type.toByte(), 0x11) + payload + 0
    }

    private fun question(vararg candidates: Int): ByteArray {
        val payload = be(700) + byteArrayOf(0x00) + candidates.flatMap { be32(it.toLong()).toList() }
        return byteArrayOf(0xCA.toByte(), 0x11, (payload.size + 2).toByte(), 0x14, 0x11) + payload + 0
    }

    @Test
    fun onlyOkokScalesAreItsOwn() {
        assertNull(driver.gattProtocol("OKOK_CLOUD_V4", mac, "0x10"))
        assertTrue(driver.gattProtocol("OKOK", mac, "0x10") != null)
    }

    @Test
    fun v10GetsThePersonFourTimes() = runTest {
        val session = RecordingSession()
        driver.gattProtocol("OKOK", mac, "0x10")!!.start(session, GattContext(now, ScaleUnit.KILOGRAM, dmitry, listOf(dmitry, anna)))
        assertEquals(listOf(ChipseaGatt.CHIPSEA_RX), session.subscriptions)
        assertEquals(4, session.writes.size)
        session.writes.forEach {
            assertEquals(0x10, it[1].toInt())
            // The scale clock in the phone's zone: 08:30:15
            assertEquals(listOf<Byte>(26, 11, 3, 8, 30, 15), it.copyOfRange(4, 10).toList())
            assertEquals(3, it[13].toInt())
        }
    }

    @Test
    fun aScaleThatWantsTheOppositeSexIsToldIt() = runTest {
        val usual = RecordingSession()
        driver.gattProtocol("OKOK", mac, "0x10")!!.start(usual, GattContext(now, ScaleUnit.KILOGRAM, dmitry))
        assertEquals(1, usual.writes.first()[14].toInt())
        val inverted = RecordingSession()
        driver.gattProtocol("OKOK", mac, "0x10")!!.start(inverted, GattContext(now, ScaleUnit.KILOGRAM, dmitry, invertsSex = true))
        assertTrue(inverted.writes.all { it[14].toInt() == 0 })
        // The result that shows it is passed on, for the app to remember
        val protocol = driver.gattProtocol("OKOK", mac, "0x10")!!
        protocol.start(RecordingSession(), GattContext(now, ScaleUnit.KILOGRAM, dmitry))
        val result = protocol.onNotification(rx(v10(locked = true, weight = 803, visceral = 25600))).single() as GattEvent.Result
        assertTrue(result.reading.invertsSex)
        // Its numbers were worked out for the other sex: only the weight is kept
        assertEquals(80.3, result.reading.weight!!.kilograms, 1e-9)
        assertTrue(result.reading.scaleValues.isEmpty())
        // Once the scale is told the opposite sex, its numbers are kept
        val told = driver.gattProtocol("OKOK", mac, "0x10")!!
        told.start(RecordingSession(), GattContext(now, ScaleUnit.KILOGRAM, dmitry, invertsSex = true))
        val next = told.onNotification(rx(v10(locked = true, weight = 803, visceral = 25600))).single() as GattEvent.Result
        assertEquals(22.5, next.reading.scaleValues[ScaleValues.FAT_PERCENT]!!, 1e-9)
    }

    @Test
    fun v11GetsEveryoneTwice() = runTest {
        val session = RecordingSession()
        driver.gattProtocol("OKOK", mac, "0x11")!!.start(session, GattContext(now, ScaleUnit.KILOGRAM, dmitry, listOf(dmitry, anna)))
        // Two people take two packets; the set goes twice
        assertEquals(4, session.writes.size)
        assertEquals(listOf(0x12, 0x22, 0x12, 0x22), session.writes.map { it[4].toInt() })
    }

    @Test
    fun v10LockedWeightIsTheResultOnceWithTheScaleNumbers() = runTest {
        val protocol = driver.gattProtocol("OKOK", mac, "0x10")!!
        protocol.start(RecordingSession(), GattContext(now, ScaleUnit.KILOGRAM, dmitry))
        assertTrue(protocol.onNotification(rx(v10(locked = false, weight = 798))).single() is GattEvent.Live)
        val result = protocol.onNotification(rx(v10(locked = true, weight = 803))).single() as GattEvent.Result
        assertEquals(80.3, result.reading.weight!!.kilograms, 1e-9)
        assertEquals(now, result.takenAt)
        assertEquals(22.5, result.reading.scaleValues[ScaleValues.FAT_PERCENT]!!, 1e-9)
        assertEquals(38.0, result.reading.scaleValues[ScaleValues.MUSCLE_PERCENT]!!, 1e-9)
        assertTrue(result.reading.impedances.isEmpty())
        // The scale repeats the locked frame: one result
        assertTrue(protocol.onNotification(rx(v10(locked = true, weight = 803))).isEmpty())
    }

    @Test
    fun v11ResultNamesThePersonAndAsksForHistoryOnce() = runTest {
        val session = RecordingSession()
        val protocol = driver.gattProtocol("OKOK", mac, "0x11")!!
        protocol.start(session, GattContext(now, ScaleUnit.KILOGRAM, dmitry, listOf(dmitry)))
        session.writes.clear()

        val result = protocol.onNotification(rx(v11(0x12, 0, userId = 3, weight = 803))).single() as GattEvent.Result
        assertEquals(3, result.reading.userId)
        assertEquals(40.0, result.reading.scaleValues[ScaleValues.MUSCLE_KG]!!, 1e-9)
        protocol.onNotification(rx(v11(0x12, 0, userId = 3, weight = 803)))
        assertEquals(listOf(0x11), session.writes.map { it[3].toInt() })

        val kept = now.minusSeconds(86_400)
        val history = protocol.onNotification(rx(v11(0x13, kept.epochSecond, userId = 5, weight = 610))).single() as GattEvent.History
        assertEquals(kept, history.takenAt)
        assertEquals(5, history.reading.userId)
        // A record from an unset clock cannot be placed
        assertTrue(protocol.onNotification(rx(v11(0x13, 946_684_800, userId = 5, weight = 610))).isEmpty())
    }

    @Test
    fun theAnswerToAUserQuestionRepeatsUntilTheResultComes() = runTest {
        val session = RecordingSession()
        val protocol = driver.gattProtocol("OKOK", mac, "0x11")!!
        protocol.start(session, GattContext(now, ScaleUnit.KILOGRAM, dmitry, listOf(dmitry, anna)))
        session.writes.clear()

        val asked = protocol.onNotification(rx(question(3, 5))).single() as GattEvent.UserQuestion
        assertEquals(listOf(3, 5), asked.candidates)
        val answering = launch { asked.answer(5) }
        runCurrent()
        advanceTimeBy(1_700)
        assertEquals(3, session.writes.size)
        assertEquals(listOf<Byte>(0, 0, 0, 5), session.writes.first().copyOfRange(4, 8).toList())

        protocol.onNotification(rx(v11(0x12, 0, userId = 5, weight = 700)))
        advanceTimeBy(5_000)
        answering.join()
        // Stopped after the result, plus the history request
        assertEquals(listOf(0x15, 0x15, 0x15, 0x11), session.writes.map { it[3].toInt() })
    }

    @Test
    fun theAnswerStopsOnALiveWeightToo() = runTest {
        val session = RecordingSession()
        val protocol = driver.gattProtocol("OKOK", mac, "0x11")!!
        protocol.start(session, GattContext(now, ScaleUnit.KILOGRAM, dmitry, listOf(dmitry, anna)))
        session.writes.clear()

        val asked = protocol.onNotification(rx(question(3, 5))).single() as GattEvent.UserQuestion
        val answering = launch { asked.answer(5) }
        runCurrent()
        advanceTimeBy(900)
        val live = byteArrayOf(0xCA.toByte(), 0x11, 0x0A, 0x00, 0x00) + be(700) + byteArrayOf(0, 0, 0, 0, 0)
        assertTrue(protocol.onNotification(rx(live)).single() is GattEvent.Live)
        advanceTimeBy(5_000)
        answering.join()
        assertEquals(listOf(0x15, 0x15), session.writes.map { it[3].toInt() })
    }
}
