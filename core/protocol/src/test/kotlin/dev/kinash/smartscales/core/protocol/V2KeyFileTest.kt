package dev.kinash.smartscales.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class V2KeyFileTest {
    private val mac = "AA:BB:CC:DD:EE:FF"
    private val found = V2KeyImport.Found("1A2B3C4D")

    @Test
    fun keyAlone() {
        assertEquals(found, V2KeyFile.find("1a2b3c4d\n", mac))
        assertEquals(found, V2KeyFile.find("  1a 2b 3c 4d ", mac))
        assertEquals(found, V2KeyFile.find("1A:2B:3C:4D", mac))
    }

    @Test
    fun keyInAMessage() {
        assertEquals(found, V2KeyFile.find("Your scale key: 1a2b3c4d. Keep it safe.", mac))
        // A longer key is cut to the four bytes the cipher uses
        assertEquals(found, V2KeyFile.find("key=1a2b3c4d5e6f7788", mac))
    }

    @Test
    fun keyNextToTheMacInCsv() {
        val csv = "mac,key\n11:22:33:44:55:66,0F0F0F0F\naa:bb:cc:dd:ee:ff,1A2B3C4D\n"
        assertEquals(found, V2KeyFile.find(csv, mac))
    }

    @Test
    fun keyFromAppSettingsXml() {
        val xml = """
            <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
            <map>
                <string name="scale_key_11:22:33:44:55:66">0f0f0f0f</string>
                <string name="scale_key_AA:BB:CC:DD:EE:FF">1a2b3c4d</string>
                <int name="launches" value="12345678" />
            </map>
        """.trimIndent()
        assertEquals(found, V2KeyFile.find(xml, mac))
    }

    @Test
    fun keyOnTheLineAfterTheMacInJson() {
        val json = "[\n  {\n    \"mac\": \"aabbccddeeff\",\n    \"key\": \"1a2b3c4d\"\n  }\n]"
        assertEquals(found, V2KeyFile.find(json, mac))
    }

    @Test
    fun keyBeforeTheMacInCompactJson() {
        val json = "{\"key\":\"1a2b3c4d\",\"mac\":\"aa:bb:cc:dd:ee:ff\"}\n{\"key\":\"0f0f0f0f\",\"mac\":\"11:22:33:44:55:66\"}"
        assertEquals(found, V2KeyFile.find(json, mac))
    }

    @Test
    fun keyOfTheNextScaleIsNotTaken() {
        assertEquals(V2KeyImport.NotFound, V2KeyFile.find("aa:bb:cc:dd:ee:ff,\n11:22:33:44:55:66,0F0F0F0F", mac))
        assertEquals(V2KeyImport.NotFound, V2KeyFile.find("aabbccddeeff,\n112233445566,0F0F0F0F", mac))
    }

    @Test
    fun eightDigitsWinOverOtherNumbersNearTheMac() {
        // Another MAC without separators, a time in seconds and the key
        assertEquals(found, V2KeyFile.find("aa:bb:cc:dd:ee:ff;112233445566;1730457600;1a2b3c4d", mac))
        assertEquals(V2KeyImport.NotFound, V2KeyFile.find("aa:bb:cc:dd:ee:ff;112233445566;1730457600", mac))
    }

    @Test
    fun macWithDashesOrNoSeparators() {
        assertEquals(found, V2KeyFile.find("AA-BB-CC-DD-EE-FF 1a2b3c4d", mac))
        assertEquals(found, V2KeyFile.find("aabbccddeeff=1a2b3c4d", mac))
    }

    @Test
    fun otherScalesOnly() {
        assertEquals(V2KeyImport.OtherScales, V2KeyFile.find("11:22:33:44:55:66,1A2B3C4D", mac))
        assertEquals(V2KeyImport.OtherScales, V2KeyFile.find("112233445566,1A2B3C4D", mac))
    }

    @Test
    fun thisScaleWithoutAKeyIsNotOtherScales() {
        assertEquals(V2KeyImport.NotFound, V2KeyFile.find("aa:bb:cc:dd:ee:ff\n11:22:33:44:55:66,0F0F0F0F", mac))
    }

    @Test
    fun nothingUsable() {
        assertEquals(V2KeyImport.NotFound, V2KeyFile.find("", mac))
        assertEquals(V2KeyImport.NotFound, V2KeyFile.find("no key here, only 1A2B", mac))
        // Two keys and no MAC to choose by
        assertEquals(V2KeyImport.NotFound, V2KeyFile.find("1A2B3C4D\n5E6F7788", mac))
        // Odd number of digits is not whole bytes
        assertEquals(V2KeyImport.NotFound, V2KeyFile.find("1A2B3C4D5", mac))
        // A date or two hex-looking words are not a key
        assertEquals(V2KeyImport.NotFound, V2KeyFile.find("2026-11-01 12:30", mac))
        assertEquals(V2KeyImport.NotFound, V2KeyFile.find("accede bead", mac))
    }

    @Test
    fun keysAreStoredInOneForm() {
        assertEquals("1A2B3C4D", V2Cipher.normalizeKey("1a 2b 3c 4d"))
        assertEquals("1A2B3C4D", V2Cipher.normalizeKey("1A2B3C4D5E6F7788"))
        assertNull(V2Cipher.normalizeKey("1A2B"))
    }
}
