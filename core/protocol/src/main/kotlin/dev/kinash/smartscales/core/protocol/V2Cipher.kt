package dev.kinash.smartscales.core.protocol

/**
 * Cipher of encrypted V2 (`C0`) advertisements. A 16-bit value keeps its top bit; the low 15 bits are
 * XORed with each of four key bytes in turn, rotated right within 15 bits by 1, 3 and 2 between them.
 */
object V2Cipher {
    const val KEY_SIZE = 4
    private const val MASK = 0x7FFF
    private val ROTATIONS = intArrayOf(1, 3, 2)

    fun decrypt(value: Int, key: ByteArray): Int {
        require(key.size >= KEY_SIZE) { "V2 key needs $KEY_SIZE bytes" }
        var x = (value and MASK) xor key[0].toUByteInt()
        for (i in ROTATIONS.indices) x = rotateRight(x, ROTATIONS[i]) xor key[i + 1].toUByteInt()
        return x or (value and 0x8000)
    }

    /** Inverse of [decrypt]; used to build test frames. */
    fun encrypt(value: Int, key: ByteArray): Int {
        require(key.size >= KEY_SIZE) { "V2 key needs $KEY_SIZE bytes" }
        var x = value and MASK
        for (i in ROTATIONS.indices.reversed()) x = rotateLeft(x xor key[i + 1].toUByteInt(), ROTATIONS[i])
        return (x xor key[0].toUByteInt()) or (value and 0x8000)
    }

    /**
     * Key bytes from its hex text as entered or imported (spaces and colons allowed), or null when it is not
     * hex or shorter than [KEY_SIZE] bytes. Only the first [KEY_SIZE] bytes are used.
     */
    fun parseKey(text: String): ByteArray? {
        val hex = text.filterNot { it.isWhitespace() || it == ':' || it == '-' }
        if (hex.length < KEY_SIZE * 2 || hex.length % 2 != 0 || !hex.all { it.isHexDigit() }) return null
        return ByteArray(KEY_SIZE) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    /**
     * The stored form of a key: its [KEY_SIZE] bytes as 8 upper-case hex digits, so the same key typed with
     * spaces or with more digits is the same key. Null when [parseKey] rejects the text.
     */
    fun normalizeKey(text: String): String? =
        parseKey(text)?.joinToString("") { "%02X".format(it.toInt() and 0xFF) }

    private fun rotateRight(x: Int, n: Int) = ((x shr n) or (x shl (15 - n))) and MASK

    private fun rotateLeft(x: Int, n: Int) = ((x shl n) or (x shr (15 - n))) and MASK

    private fun Char.isHexDigit() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}
