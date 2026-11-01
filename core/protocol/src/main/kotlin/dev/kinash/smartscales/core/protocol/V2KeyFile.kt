package dev.kinash.smartscales.core.protocol

/** What a file imported for a scale's V2 key gave. */
sealed interface V2KeyImport {
    /** The key in its stored form, see [V2Cipher.normalizeKey]. */
    data class Found(val key: String) : V2KeyImport

    /** The file names other scales only. */
    data object OtherScales : V2KeyImport

    /** No key for this scale: none in the file, none next to its MAC, or several without a MAC to choose by. */
    data object NotFound : V2KeyImport
}

/**
 * Finds the V2 key of the scale with [mac] in a file the person has. No single format is required, so
 * keys copied in any common way are accepted:
 * - the key alone, as in a note or a message, also written as bytes (`1A 2B 3C 4D`);
 * - lines naming the MAC and the key: `AA:BB:CC:DD:EE:FF,1A2B3C4D`, JSON, or the XML of app settings where
 *   the MAC is part of the entry name; the MAC may have colons, dashes or none, in any case.
 *
 * Next to the scale's MAC the key is looked for after it on the same line, then before it, then on the next
 * line unless that line names another scale. Without any MAC the file must hold exactly one key.
 *
 * A key is a hex group of exactly 8 digits, one with a letter before one of decimal digits only (it may be a
 * date); longer groups count only when there is no 8-digit one, and never
 * when they have 12 digits (a MAC without separators) or decimal digits only (a time, a record id).
 */
object V2KeyFile {
    fun find(text: String, mac: String): V2KeyImport {
        val target = mac.filter { it.isHex() }.lowercase()
        val lines = text.lines()
        var macFound = false
        for ((i, line) in lines.withIndex()) {
            val at = macPosition(line, target) ?: continue
            macFound = true
            val next = lines.getOrNull(i + 1)?.takeUnless(::hasMac).orEmpty()
            val key = bestKey(line.substring(at.last + 1)) ?: bestKey(line.substring(0, at.first)) ?: bestKey(next)
            if (key != null) return V2KeyImport.Found(key)
        }
        if (macFound) return V2KeyImport.NotFound
        if (hasMac(text)) return V2KeyImport.OtherScales
        spacedKey(text.trim())?.let { return V2KeyImport.Found(it) }
        return candidates(text).singleOrNull()?.let { V2KeyImport.Found(it) } ?: V2KeyImport.NotFound
    }

    /** The first key in [text], preferring exactly 8 digits. */
    private fun bestKey(text: String): String? = candidates(text).firstOrNull()

    /**
     * Distinct keys in [text] of the best kind present: 8-digit groups with a letter, then 8 decimal digits
     * (which may also be a date such as 20261101), then longer groups.
     */
    private fun candidates(text: String): List<String> {
        val groups = HEX_GROUP.findAll(text).map { it.value }.toList()
        val exact = groups.filter { it.length == V2Cipher.KEY_SIZE * 2 }
        val chosen = exact.filterNot { it.all(Char::isDigit) }.ifEmpty { exact }.ifEmpty {
            groups.filter { it.length % 2 == 0 && it.length != MAC_DIGITS && !it.all(Char::isDigit) }
        }
        return chosen.mapNotNull(V2Cipher::normalizeKey).distinct()
    }

    /** A key written as bytes on its own line: 4 to 32 groups of two digits, nothing else. */
    private fun spacedKey(line: String): String? {
        if ('\n' in line) return null
        val bytes = line.split(' ', ':', '-').filter { it.isNotEmpty() }
        if (bytes.size !in V2Cipher.KEY_SIZE..MAX_KEY_BYTES || bytes.any { it.length != 2 || !it.all { c -> c.isHex() } }) return null
        return V2Cipher.normalizeKey(bytes.joinToString(""))
    }

    /** Where [target] (12 hex digits) appears in [line] with any or no separators, on hex boundaries. */
    private fun macPosition(line: String, target: String): IntRange? {
        if (target.length != MAC_DIGITS) return null
        val pattern = target.chunked(2).joinToString("[:-]?") { Regex.escape(it) }
        return Regex("(?<![0-9A-Fa-f])$pattern(?![0-9A-Fa-f])", RegexOption.IGNORE_CASE).find(line)?.range
    }

    /** Whether [text] names any MAC, with separators or as a 12-digit group. */
    private fun hasMac(text: String): Boolean =
        MAC_WITH_SEPARATORS.containsMatchIn(text) || HEX_GROUP.findAll(text).any { it.value.length == MAC_DIGITS }

    private fun Char.isHex() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    private const val MAC_DIGITS = 12
    private const val MAX_KEY_BYTES = 32

    /** Hex groups not part of a word. */
    private val HEX_GROUP = Regex("(?<![0-9A-Za-z_])[0-9A-Fa-f]{8,64}(?![0-9A-Za-z_])")
    private val MAC_WITH_SEPARATORS = Regex("(?<![0-9A-Fa-f])([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}(?![0-9A-Fa-f])")
}
