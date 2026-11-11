package dev.kinash.smartscales.importing

import dev.kinash.smartscales.core.formula.XlsxSheet
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** A table read from a file: rows of cells as text, an empty cell as "". */
class Table(val rows: List<List<String>>, val kind: Kind) {
    enum class Kind { CSV, XLS, XLSX }

    fun cell(row: Int, column: Int): String = rows.getOrNull(row)?.getOrNull(column).orEmpty()
}

/** Reads the first sheet of an Excel file or a CSV file, telling them by their first bytes. */
object Tables {
    /** Bigger files are not weighings someone exported. */
    const val MAX_BYTES = 16 * 1024 * 1024

    fun read(bytes: ByteArray): Table = when {
        bytes.startsWith(CFB_SIGNATURE) -> Table(XlsWorkbook.firstSheet(bytes), Table.Kind.XLS)
        bytes.startsWith(ZIP_SIGNATURE) -> Table(XlsxSheet.read(bytes).rows.map { row -> row.map { it.orEmpty() } }, Table.Kind.XLSX)
        else -> Table(Csv.read(decode(bytes)), Table.Kind.CSV)
    }

    /**
     * UTF-8, with or without a byte order mark; a file that is not, as Excel in Russian saves CSV, is Windows-1251.
     * Other single-byte code pages are not told apart.
     */
    internal fun decode(bytes: ByteArray): String {
        val body = if (bytes.startsWith(UTF8_BOM)) bytes.copyOfRange(UTF8_BOM.size, bytes.size) else bytes
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(body))
                .toString()
        } catch (e: CharacterCodingException) {
            String(body, Charset.forName("windows-1251"))
        }
    }

    private fun ByteArray.startsWith(prefix: ByteArray) = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private val CFB_SIGNATURE = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())
    private val ZIP_SIGNATURE = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
}

/**
 * CSV as spreadsheets write it (RFC 4180): cells in double quotes may hold the separator, line breaks and doubled
 * quotes. The separator is the one of `;`, `,` and tab that the first line has most of outside quotes.
 */
object Csv {
    fun read(text: String): List<List<String>> {
        val separator = separator(text)
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && text.getOrNull(i + 1) == '"' -> {
                    cell.append('"')
                    i++
                }
                c == '"' && (quoted || cell.isEmpty()) -> quoted = !quoted
                !quoted && c == separator -> {
                    row += cell.toString()
                    cell.clear()
                }
                !quoted && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && text.getOrNull(i + 1) == '\n') i++
                    row += cell.toString()
                    cell.clear()
                    rows += row
                    row = ArrayList()
                }
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) {
            row += cell.toString()
            rows += row
        }
        return rows.filterNot { r -> r.all { it.isBlank() } }
    }

    /** Over the first lines, as a title line above the header may have none. */
    private fun separator(text: String): Char {
        val counts = HashMap<Char, Int>()
        var quoted = false
        var lines = 0
        for (c in text) {
            if (c == '"') quoted = !quoted
            if (!quoted && c == '\n' && ++lines >= 10) break
            if (!quoted && c in SEPARATORS) counts[c] = (counts[c] ?: 0) + 1
        }
        return counts.maxByOrNull { it.value }?.key ?: ','
    }

    private val SEPARATORS = setOf(';', ',', '\t')
}
