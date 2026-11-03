package dev.kinash.smartscales.core.protocol

import java.time.Instant
import java.time.LocalDateTime

/**
 * Body composition an OKOK v1.0/v1.1 scale works out itself and sends instead of impedance, as sent: values the
 * scale marks as missing (`0xFFFF`) or leaves at zero are null. Nothing is clamped or recalculated; the app keeps
 * these as the scale's own data and works out its indicators from the weight with its own formulas. The one
 * fix is to the format: some scales send BMR in tenths of a kilocalorie, so a value of 5000 or more is divided
 * by ten, as the vendor app does.
 */
data class ScaleComposition(
    val fatPercent: Double? = null,
    val waterPercent: Double? = null,
    /** v1.0 sends muscle as a percentage, v1.1 as kilograms. */
    val musclePercent: Double? = null,
    val muscleKg: Double? = null,
    val bmrKcal: Double? = null,
    val visceralFat: Double? = null,
    val boneKg: Double? = null,
) {
    /** The values by the identifiers the measurement keeps them under; see [ScaleValues]. */
    fun toValues(): Map<String, Double> = buildMap {
        fatPercent?.let { put(ScaleValues.FAT_PERCENT, it) }
        waterPercent?.let { put(ScaleValues.WATER_PERCENT, it) }
        musclePercent?.let { put(ScaleValues.MUSCLE_PERCENT, it) }
        muscleKg?.let { put(ScaleValues.MUSCLE_KG, it) }
        bmrKcal?.let { put(ScaleValues.BMR_KCAL, it) }
        visceralFat?.let { put(ScaleValues.VISCERAL_FAT, it) }
        boneKg?.let { put(ScaleValues.BONE_KG, it) }
    }
}

/** Identifiers of values a scale works out itself, kept with the measurement next to the raw data. */
object ScaleValues {
    const val FAT_PERCENT = "scale_fat_percent"
    const val WATER_PERCENT = "scale_water_percent"
    const val MUSCLE_PERCENT = "scale_muscle_percent"
    const val MUSCLE_KG = "scale_muscle_kg"
    const val BMR_KCAL = "scale_bmr_kcal"
    const val VISCERAL_FAT = "scale_visceral_fat"
    const val BONE_KG = "scale_bone_kg"
}

/** A frame of the OKOK GATT protocol (`CA 10`, `CA 11`) from `FFF1`. */
sealed interface OkokFrame {
    val property: ScaleProperty
    val weight: ScaleWeight

    /** A weight in progress or locked: v1.0 frames (with the composition once locked) and v1.1 types `00`/`01`. */
    data class Weight(
        override val property: ScaleProperty,
        override val weight: ScaleWeight,
        val locked: Boolean,
        val composition: ScaleComposition? = null,
    ) : OkokFrame

    /** v1.1 `12`: the result of this weighing; [userId] is the person the scale took it for, null when it did not know. */
    data class Result(
        override val property: ScaleProperty,
        override val weight: ScaleWeight,
        val userId: Int?,
        val composition: ScaleComposition,
    ) : OkokFrame

    /** v1.1 `13`: a weighing the scale kept, at [takenAt]. */
    data class History(
        override val property: ScaleProperty,
        override val weight: ScaleWeight,
        val takenAt: Instant,
        val userId: Int?,
        val composition: ScaleComposition,
    ) : OkokFrame

    /** v1.1 `14`: the weight fits several people; the scale asks which of [candidates] stands on it. */
    data class UserQuestion(
        override val property: ScaleProperty,
        override val weight: ScaleWeight,
        val candidates: List<Int>,
    ) : OkokFrame
}

/**
 * Parser of OKOK GATT frames, written from the interface description in the project's protocol notes. Types
 * `12`, `13` and `14` of v1.1 may come in several notifications, so the parser keeps the parts until the last
 * one; use one parser per connection. All numbers are big-endian; the weight uses the OKOK decimals layout.
 */
class OkokParser {
    private val parts = mutableMapOf<Int, MutableMap<Int, ByteArray>>()

    fun parse(frame: ByteArray): OkokFrame? {
        if (frame.size < 5 || frame[0].toUByteInt() != HEADER) return null
        return when (frame[1].toUByteInt()) {
            VERSION_10 -> parseV10(frame)
            VERSION_11 -> when (val type = frame[3].toUByteInt()) {
                TYPE_LIVE, TYPE_LOCKED -> parseV11Weight(frame, locked = type == TYPE_LOCKED)
                TYPE_RESULT, TYPE_HISTORY, TYPE_USER_QUESTION -> assemble(type, frame)?.let { whole -> parseWhole(type, whole) }
                else -> null
            }
            else -> null
        }
    }

    /** `CA 10`: `[4]` property, `[5..6]` weight, then the composition once locked. */
    private fun parseV10(d: ByteArray): OkokFrame? {
        if (d.size < 7) return null
        val property = ScaleProperty(d[4].toUByteInt())
        val composition = if (property.locked && d.size >= 18) {
            ScaleComposition(
                fatPercent = tenths(d, 7),
                waterPercent = tenths(d, 9),
                musclePercent = tenths(d, 11),
                bmrKcal = bmr(d, 13),
                visceralFat = tenths(d, 15),
                boneKg = d[17].toUByteInt().takeIf { it != 0 && it != 0xFF }?.let { it / 10.0 },
            )
        } else {
            null
        }
        return OkokFrame.Weight(property, ScaleWeight.of(d.uIntBe(5, 2), property, DigitsLayout.OKOK), property.locked, composition)
    }

    /** v1.1 `00`/`01`: `[5..6]` weight, `[11]` property. */
    private fun parseV11Weight(d: ByteArray, locked: Boolean): OkokFrame? {
        if (d.size < 12) return null
        val property = ScaleProperty(d[11].toUByteInt())
        return OkokFrame.Weight(property, ScaleWeight.of(d.uIntBe(5, 2), property, DigitsLayout.OKOK), locked)
    }

    /**
     * Joins the parts of a multi-packet message: `[2]` is the payload length + 2, `[4]` the packet number in
     * the high nibble and the count in the low one. Packet 1 starts over; the message is whole once the last
     * packet arrived and all of them are there. Returns the payload, or null while parts are missing.
     */
    private fun assemble(type: Int, d: ByteArray): ByteArray? {
        val length = d[2].toUByteInt() - 2
        if (length <= 0 || d.size < 5 + length) return null
        val number = d[4].toUByteInt() shr 4
        val count = d[4].toUByteInt() and 0x0F
        if (number == 0 || count == 0 || number > count) return null
        val received = if (number == 1) mutableMapOf<Int, ByteArray>().also { parts[type] = it } else parts[type] ?: return null
        received[number] = d.copyOfRange(5, 5 + length)
        if (number != count) return null
        parts.remove(type)
        if (received.size != count) return null
        return (1..count).fold(ByteArray(0)) { all, i -> all + received.getValue(i) }
    }

    private fun parseWhole(type: Int, w: ByteArray): OkokFrame? = when (type) {
        TYPE_USER_QUESTION -> {
            if (w.size < 3) {
                null
            } else {
                val property = ScaleProperty(w[2].toUByteInt())
                val candidates = (3..w.size - 4 step 4).map { w.uIntBe(it, 4) }.filter { it != 0 }
                OkokFrame.UserQuestion(property, ScaleWeight.of(w.uIntBe(0, 2), property, DigitsLayout.OKOK), candidates)
            }
        }
        else -> {
            if (w.size < 23) {
                null
            } else {
                val property = ScaleProperty(w[4].toUByteInt())
                val weight = ScaleWeight.of(w.uIntBe(10, 2), property, DigitsLayout.OKOK)
                val userId = w.uIntBe(6, 4).takeIf { it != 0 }
                val composition = ScaleComposition(
                    fatPercent = tenths(w, 12),
                    waterPercent = tenths(w, 14),
                    muscleKg = tenths(w, 16),
                    bmrKcal = bmr(w, 18),
                    visceralFat = tenths(w, 20),
                    boneKg = w[22].toUByteInt().takeIf { it != 0 && it != 0xFF }?.let { it / 10.0 },
                )
                if (type == TYPE_RESULT) {
                    OkokFrame.Result(property, weight, userId, composition)
                } else {
                    OkokFrame.History(property, weight, Instant.ofEpochSecond(w.uInt32Be(0)), userId, composition)
                }
            }
        }
    }

    private fun raw(d: ByteArray, offset: Int): Int? = d.uIntBe(offset, 2).takeIf { it != 0 && it != MISSING }

    private fun tenths(d: ByteArray, offset: Int): Double? = raw(d, offset)?.let { it / 10.0 }

    /** No one burns 5000 kcal at rest: such a value is in tenths. */
    private fun bmr(d: ByteArray, offset: Int): Double? = raw(d, offset)?.let { if (it >= BMR_IN_TENTHS) it / 10.0 else it.toDouble() }

    private companion object {
        const val HEADER = 0xCA
        const val VERSION_10 = 0x10
        const val VERSION_11 = 0x11
        const val TYPE_LIVE = 0x00
        const val TYPE_LOCKED = 0x01
        const val TYPE_RESULT = 0x12
        const val TYPE_HISTORY = 0x13
        const val TYPE_USER_QUESTION = 0x14
        const val MISSING = 0xFFFF
        const val BMR_IN_TENTHS = 5000
    }
}

/** A person as an OKOK scale keeps them; [lastWeightKg] helps a v1.1 scale tell people apart by weight. */
data class OkokUser(val userId: Int, val male: Boolean, val ageYears: Int, val heightCm: Int, val lastWeightKg: Double? = null)

/** Commands to OKOK GATT scales, for `FFF2`; each ends with the XOR of its bytes from the second one on. */
object OkokCommands {
    /**
     * v1.0: the person on the scale with the scale's clock, 20 bytes. The vendor app sends it four times,
     * 150 ms apart. A male is 1.
     */
    fun userV10(now: LocalDateTime, user: OkokUser): ByteArray {
        val d = ByteArray(20)
        d[0] = HEADER
        d[1] = 0x10
        d[2] = 0x0E
        d[3] = 0x01
        d[4] = (now.year % 100).toByte()
        d[5] = now.monthValue.toByte()
        d[6] = now.dayOfMonth.toByte()
        d[7] = now.hour.toByte()
        d[8] = now.minute.toByte()
        d[9] = now.second.toByte()
        putInt(d, 10, user.userId)
        d[14] = if (user.male) 1 else 0
        d[15] = user.ageYears.coerceIn(0, 0xFF).toByte()
        d[16] = user.heightCm.coerceIn(0, 0xFF).toByte()
        d[17] = xor(d, 17)
        return d
    }

    /**
     * v1.1: every person the scale should know, with the time. One person fits a single 20-byte packet; more
     * are cut into packets of 14 payload bytes. The vendor app sends the whole set twice, 200 ms between packets.
     */
    fun usersV11(now: Instant, users: List<OkokUser>): List<ByteArray> {
        val time = now.epochSecond.toInt()
        val payload = ByteArray(6 + users.size * 8)
        putInt(payload, 0, time)
        users.forEachIndexed { i, user -> userBlock(user).copyInto(payload, 6 + i * 8) }
        if (users.size == 1) {
            val d = ByteArray(20)
            d[0] = HEADER
            d[1] = 0x11
            d[2] = 0x10
            d[3] = TYPE_USERS
            d[4] = 0x11
            payload.copyInto(d, 5)
            d[19] = xor(d, 19)
            return listOf(d)
        }
        val chunks = payload.toList().chunked(PACKET_PAYLOAD)
        return chunks.mapIndexed { i, chunk ->
            val d = ByteArray(chunk.size + 6)
            d[0] = HEADER
            d[1] = 0x11
            d[2] = (chunk.size + 2).toByte()
            d[3] = TYPE_USERS
            d[4] = (((i + 1) shl 4) or chunks.size).toByte()
            chunk.toByteArray().copyInto(d, 5)
            d[d.size - 1] = xor(d, d.size - 1)
            d
        }
    }

    /** v1.1: asks for the weighings the scale kept. The vendor app sends it after the first locked result. */
    fun historyRequest(): ByteArray {
        val d = ByteArray(20)
        d[0] = HEADER
        d[1] = 0x11
        d[2] = 0x02
        d[3] = 0x11
        d[4] = 0x01
        d[5] = xor(d, 5)
        return d
    }

    /** v1.1: the answer to a [OkokFrame.UserQuestion]. The vendor app repeats it until a weight arrives. */
    fun selectUser(userId: Int): ByteArray {
        val d = ByteArray(9)
        d[0] = HEADER
        d[1] = 0x11
        d[2] = 0x05
        d[3] = 0x15
        putInt(d, 4, userId)
        d[8] = xor(d, 8)
        return d
    }

    private fun userBlock(user: OkokUser): ByteArray {
        val d = ByteArray(8)
        putInt(d, 0, user.userId)
        val age = user.ageYears.coerceIn(0, 0x7F)
        d[4] = (if (user.male) 0x80 or age else age).toByte()
        d[5] = user.heightCm.coerceIn(0, 0xFF).toByte()
        val weight = ((user.lastWeightKg ?: 0.0) * 10).let { Math.round(it).toInt() }.coerceIn(0, 0xFFFF)
        d[6] = (weight shr 8).toByte()
        d[7] = weight.toByte()
        return d
    }

    private fun putInt(d: ByteArray, offset: Int, value: Int) {
        d[offset] = (value shr 24).toByte()
        d[offset + 1] = (value shr 16).toByte()
        d[offset + 2] = (value shr 8).toByte()
        d[offset + 3] = value.toByte()
    }

    /** XOR of the bytes from index 1 up to, not including, [checksumAt]. */
    private fun xor(d: ByteArray, checksumAt: Int): Byte {
        var x = 0
        for (i in 1 until checksumAt) x = x xor d[i].toInt()
        return x.toByte()
    }

    private const val HEADER = 0xCA.toByte()
    private const val TYPE_USERS: Byte = 0x10
    private const val PACKET_PAYLOAD = 14
}
