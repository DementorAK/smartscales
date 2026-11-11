package dev.kinash.smartscales.core.protocol

import dev.kinash.smartscales.core.model.ScaleValues

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime

/** Frames are synthetic, built field by field from the protocol description: no OKOK GATT scale was at hand. */
class OkokTest {
    /** Kilograms with one decimal in the OKOK layout (bits 2..1 = `00`), locked or not. */
    private fun kg1(locked: Boolean) = if (locked) 0x01 else 0x00

    private fun be(value: Int) = byteArrayOf((value shr 8).toByte(), value.toByte())

    private fun be32(value: Long) = byteArrayOf((value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte())

    private fun xor(d: ByteArray, from: Int, until: Int) = (from until until).fold(0) { x, i -> x xor d[i].toInt() }.toByte()

    private fun v10(locked: Boolean, weight: Int, bmr: Int = 1650, visceral: Int = 85, fat: Int = 225) =
        byteArrayOf(0xCA.toByte(), 0x10, 0x10, 0x00, kg1(locked).toByte()) +
            be(weight) + be(fat) + be(552) + be(380) + be(bmr) + be(visceral) + byteArrayOf(31)

    @Test
    fun v10WeightInProgressHasNoComposition() {
        val frame = OkokParser().parse(v10(locked = false, weight = 798)) as OkokFrame.Weight
        assertEquals(79.8, frame.weight.kilograms, 1e-9)
        assertTrue(!frame.locked)
        assertNull(frame.composition)
    }

    @Test
    fun v10LockedWeightCarriesTheScaleComposition() {
        val frame = OkokParser().parse(v10(locked = true, weight = 803)) as OkokFrame.Weight
        assertTrue(frame.locked)
        assertEquals(
            ScaleComposition(fatPercent = 22.5, waterPercent = 55.2, musclePercent = 38.0, bmrKcal = 1650.0, visceralFat = 8.5, boneKg = 3.1),
            frame.composition,
        )
    }

    @Test
    fun aVisceralOf25600WithAFatIsTheScaleThatWantsTheOppositeSex() {
        val composition = (OkokParser().parse(v10(locked = true, weight = 803, visceral = 25600)) as OkokFrame.Weight).composition!!
        assertTrue(composition.invertsSex)
        // Not a visceral fat of 2560
        assertNull(composition.visceralFat)
        assertNull(composition.toValues()[ScaleValues.VISCERAL_FAT])
        // Without a fat it is only a scale that measures no fat
        val noFat = (OkokParser().parse(v10(locked = true, weight = 803, visceral = 25600, fat = 0)) as OkokFrame.Weight).composition!!
        assertTrue(!noFat.invertsSex)
        assertTrue(!(OkokParser().parse(v10(locked = true, weight = 803)) as OkokFrame.Weight).composition!!.invertsSex)
    }

    @Test
    fun bmrInTenthsIsReadAsKilocalories() {
        val frame = OkokParser().parse(v10(locked = true, weight = 803, bmr = 16_504)) as OkokFrame.Weight
        assertEquals(1650.4, frame.composition!!.bmrKcal!!, 1e-9)
        val plain = OkokParser().parse(v10(locked = true, weight = 803, bmr = 4999)) as OkokFrame.Weight
        assertEquals(4999.0, plain.composition!!.bmrKcal!!, 1e-9)
    }

    @Test
    fun v11LiveWeightTakesThePropertyFromByte11() {
        val frame = byteArrayOf(0xCA.toByte(), 0x11, 0x0A, 0x00, 0x00) + be(805) + byteArrayOf(0, 0, 0, 0, kg1(false).toByte())
        val parsed = OkokParser().parse(frame) as OkokFrame.Weight
        assertEquals(80.5, parsed.weight.kilograms, 1e-9)
    }

    /** The joined payload of a `12` or `13`: time (history only), property, user, weight, composition. */
    private fun payload(time: Long, userId: Int, weight: Int) = be32(time) + byteArrayOf(kg1(true).toByte(), 0) +
        be32(userId.toLong()) + be(weight) + be(225) + be(552) + be(0xFFFF) + be(1650) + be(85) + byteArrayOf(31)

    /** Cuts [payload] into packets of [type] with [size] payload bytes each. */
    private fun packets(type: Int, payload: ByteArray, size: Int = 14): List<ByteArray> {
        val chunks = payload.toList().chunked(size)
        return chunks.mapIndexed { i, chunk ->
            byteArrayOf(0xCA.toByte(), 0x11, (chunk.size + 2).toByte(), type.toByte(), (((i + 1) shl 4) or chunks.size).toByte()) +
                chunk.toByteArray() + 0
        }
    }

    @Test
    fun resultJoinsItsPacketsAndNamesTheUser() {
        val parser = OkokParser()
        val (first, second) = packets(0x12, payload(0, userId = 3, weight = 803))
        assertNull(parser.parse(first))
        val result = parser.parse(second) as OkokFrame.Result
        assertEquals(3, result.userId)
        assertEquals(80.3, result.weight.kilograms, 1e-9)
        // v1.1 sends muscle in kilograms; 0xFFFF there means the scale has no value
        assertNull(result.composition.muscleKg)
        assertEquals(22.5, result.composition.fatPercent!!, 1e-9)
        assertEquals(1650.0, result.composition.bmrKcal!!, 1e-9)
    }

    @Test
    fun historyHasItsTimeAndAnUnknownUserIsNull() {
        val taken = Instant.parse("2026-11-01T07:15:00Z")
        val parser = OkokParser()
        val frames = packets(0x13, payload(taken.epochSecond, userId = 0, weight = 790))
        val history = frames.map(parser::parse).last() as OkokFrame.History
        assertEquals(taken, history.takenAt)
        assertNull(history.userId)
        assertEquals(79.0, history.weight.kilograms, 1e-9)
    }

    @Test
    fun aMissingPacketDropsTheMessageAndPacketOneStartsOver() {
        val parser = OkokParser()
        val frames = packets(0x12, payload(0, userId = 3, weight = 803), size = 8)
        assertEquals(3, frames.size)
        // The middle packet is lost: no result
        assertNull(parser.parse(frames[0]))
        assertNull(parser.parse(frames[2]))
        // The scale sends the message again from packet 1
        assertNull(parser.parse(frames[0]))
        assertNull(parser.parse(frames[1]))
        assertTrue(parser.parse(frames[2]) is OkokFrame.Result)
    }

    @Test
    fun userQuestionListsTheCandidates() {
        val payload = be(700) + byteArrayOf(kg1(false).toByte()) + be32(3) + be32(5)
        val question = packets(0x14, payload).map(OkokParser()::parse).last() as OkokFrame.UserQuestion
        assertEquals(listOf(3, 5), question.candidates)
        assertEquals(70.0, question.weight.kilograms, 1e-9)
    }

    @Test
    fun otherFramesAreIgnored() {
        val parser = OkokParser()
        assertNull(parser.parse(byteArrayOf(0xC4.toByte(), 0, 0, 0, 0)))
        assertNull(parser.parse(byteArrayOf(0xCA.toByte(), 0x20, 0, 0, 0)))
        assertNull(parser.parse(byteArrayOf(0xCA.toByte(), 0x11)))
    }

    private val dmitry = OkokUser(userId = 3, male = true, ageYears = 36, heightCm = 180, lastWeightKg = 80.5)
    private val anna = OkokUser(userId = 5, male = false, ageYears = 34, heightCm = 165, lastWeightKg = 61.0)

    @Test
    fun v10UserCommandHasTheClockAndAChecksum() {
        val d = OkokCommands.userV10(LocalDateTime.of(2026, 11, 3, 8, 30, 15), dmitry)
        assertEquals(20, d.size)
        assertArrayEquals(byteArrayOf(0xCA.toByte(), 0x10, 0x0E, 0x01, 26, 11, 3, 8, 30, 15, 0, 0, 0, 3, 1, 36, 180.toByte()), d.copyOf(17))
        assertEquals(xor(d, 1, 17), d[17])
        assertEquals(0, d[18].toInt() or d[19].toInt())
    }

    @Test
    fun oneV11UserFitsOnePacket() {
        val now = Instant.parse("2026-11-03T08:00:00Z")
        val d = OkokCommands.usersV11(now, listOf(dmitry)).single()
        assertEquals(20, d.size)
        assertArrayEquals(byteArrayOf(0xCA.toByte(), 0x11, 0x10, 0x10, 0x11), d.copyOf(5))
        assertArrayEquals(be32(now.epochSecond), d.copyOfRange(5, 9))
        assertArrayEquals(be32(3) + byteArrayOf((0x80 or 36).toByte(), 180.toByte()) + be(805), d.copyOfRange(11, 19))
        assertEquals(xor(d, 1, 19), d[19])
    }

    @Test
    fun severalV11UsersAreCutIntoNumberedPackets() {
        val packets = OkokCommands.usersV11(Instant.parse("2026-11-03T08:00:00Z"), listOf(dmitry, anna))
        // 6 bytes of time and 2 × 8 of people: 14 + 8
        assertEquals(listOf(20, 14), packets.map { it.size })
        assertEquals(listOf(0x12, 0x22), packets.map { it[4].toInt() })
        assertEquals(listOf(16, 10), packets.map { it[2].toInt() })
        packets.forEach { assertEquals(xor(it, 1, it.size - 1), it.last()) }
        // Anna is a woman: no sex bit
        assertEquals(34, packets[1][5 + 4].toInt())
    }

    @Test
    fun historyRequestAndUserChoice() {
        val history = OkokCommands.historyRequest()
        assertEquals(20, history.size)
        assertArrayEquals(byteArrayOf(0xCA.toByte(), 0x11, 0x02, 0x11, 0x01, (0x11 xor 0x02 xor 0x11 xor 0x01).toByte()), history.copyOf(6))
        val select = OkokCommands.selectUser(5)
        assertArrayEquals(byteArrayOf(0xCA.toByte(), 0x11, 0x05, 0x15, 0, 0, 0, 5), select.copyOf(8))
        assertEquals(xor(select, 1, 8), select[8])
    }

    @Test
    fun compositionKeepsOnlySentValues() {
        assertEquals(
            mapOf(ScaleValues.FAT_PERCENT to 22.5, ScaleValues.BONE_KG to 3.1),
            ScaleComposition(fatPercent = 22.5, boneKg = 3.1).toValues(),
        )
    }
}
