package dev.kinash.smartscales.core.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class V2CipherTest {
    private val key = byteArrayOf(0x12, 0x34, 0x56, 0x78)

    @Test
    fun decryptMatchesReferenceVectors() {
        // Reference vectors; the top bit passes through unchanged
        assertEquals(0x7460, V2Cipher.decrypt(0x0320, key))
        assertEquals(0xA422, V2Cipher.decrypt(0x9388, key))
        assertEquals(0xC2C6, V2Cipher.decrypt(0xAABB, key))
        assertEquals(0x5C24, V2Cipher.decrypt(0x1234, key))
        assertEquals(0xCB93, V2Cipher.decrypt(0xFFFF, key))
        assertEquals(0x63D4, V2Cipher.decrypt(0x4567, byteArrayOf(0xCC.toByte(), 0xDD.toByte(), 0xEE.toByte(), 0xFF.toByte())))
    }

    @Test
    fun encryptIsInverseOfDecrypt() {
        for (value in 0..0xFFFF step 7) {
            assertEquals(value, V2Cipher.decrypt(V2Cipher.encrypt(value, key), key))
        }
    }

    @Test
    fun keyIsParsedFromHex() {
        assertArrayEquals(key, V2Cipher.parseKey("12345678"))
        assertArrayEquals(key, V2Cipher.parseKey("12:34:56:78"))
        assertArrayEquals(key, V2Cipher.parseKey("12 34 56 78 9a bc"))
        assertNull(V2Cipher.parseKey("123456"))
        assertNull(V2Cipher.parseKey("1234567"))
        assertNull(V2Cipher.parseKey("12345g78"))
    }
}
