package dev.kinash.smartscales.core.protocol

/** One AD structure of a BLE advertisement: its type and payload without the length and type bytes. */
class AdStructure(val type: Int, val data: ByteArray)

/**
 * Splits a raw advertisement (scan record) into AD structures. Parsing stops at a zero length, which pads
 * the record, or at a structure that does not fit.
 */
fun parseAdStructures(record: ByteArray): List<AdStructure> {
    val result = mutableListOf<AdStructure>()
    var i = 0
    while (i < record.size) {
        val length = record[i].toUByteInt()
        if (length == 0 || i + 1 + length > record.size) break
        result += AdStructure(record[i + 1].toUByteInt(), record.copyOfRange(i + 2, i + 1 + length))
        i += 1 + length
    }
    return result
}

/**
 * Payload of the first manufacturer-specific AD structure, including the two bytes that formally hold the
 * company id: Chipsea scales put their frame header there.
 */
fun manufacturerData(record: ByteArray): ByteArray? =
    parseAdStructures(record).firstOrNull { it.type == AD_TYPE_MANUFACTURER_DATA }?.data

const val AD_TYPE_MANUFACTURER_DATA = 0xFF

internal fun Byte.toUByteInt(): Int = toInt() and 0xFF

/** Big-endian unsigned number from [length] bytes starting at [offset]. */
internal fun ByteArray.uIntBe(offset: Int, length: Int): Int {
    var value = 0
    for (i in offset until offset + length) value = (value shl 8) or this[i].toUByteInt()
    return value
}

/** Formats bytes as an uppercase MAC address, `AA:BB:CC:DD:EE:FF`. */
internal fun macOf(bytes: ByteArray): String = bytes.joinToString(":") { "%02X".format(it.toUByteInt()) }
