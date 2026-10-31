package dev.kinash.smartscales.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Runs on Robolectric for the ICU character iterator. */
@RunWith(RobolectricTestRunner::class)
class AvatarTextTest {
    @Test
    fun upToTwoLetters() {
        assertEquals("ДК", avatarText("ДКи"))
        assertEquals("A", avatarText(" A "))
        assertEquals("", avatarText(""))
    }

    @Test
    fun oneEmojiKeptWhole() {
        assertEquals("😀", avatarText("😀😀"))
        // A family is several code points joined into one emoji
        assertEquals("👨‍👩‍👧", avatarText("👨‍👩‍👧x"))
        assertEquals("🇺🇦", avatarText("🇺🇦🇺🇦"))
    }

    @Test
    fun lettersStopAtAnEmoji() {
        assertEquals("Д", avatarText("Д😀"))
    }
}
