package dev.kinash.smartscales.core.formula

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory

/**
 * The first worksheet of an Excel `.xlsx` file, read just far enough for a table of numbers: its name and its cells
 * as text, row by row, with empty cells as null. An `.xlsx` is a zip of XML parts (ECMA-376); this reads the
 * workbook, its relationships, the shared strings and the sheet, and no formulas, styles or dates.
 */
class XlsxSheet(val name: String, val rows: List<List<String?>>) {
    companion object {
        /** Bigger parts than this are not tables someone downloaded; a WHO table by day is about 1.5 MB of XML. */
        private const val MAX_PART_BYTES = 32 * 1024 * 1024

        /** All the parts read together, so that many parts which unpack big do not run the phone out of memory. */
        private const val MAX_TOTAL_BYTES = 64 * 1024 * 1024

        private val WORKSHEET = Regex("xl/worksheets/[^/]+\\.xml")
        private val PARTS = setOf("xl/workbook.xml", "xl/_rels/workbook.xml.rels", "xl/sharedStrings.xml")
        private val DOCTYPE = "<!DOCTYPE".toByteArray()

        /** Reads the first sheet of [bytes]; throws [IOException] when they are not an `.xlsx` with a sheet. */
        fun read(bytes: ByteArray): XlsxSheet {
            val parts = unzip(bytes)
            val workbook = parts["xl/workbook.xml"] ?: throw IOException("Not an xlsx file: no workbook")
            val sheets = ArrayList<Pair<String, String?>>()
            parse(workbook) { element, attributes ->
                if (element == "sheet") sheets += attributes.byName("name").orEmpty() to attributes.byName("id")
            }
            val (sheetName, relationId) = sheets.firstOrNull() ?: throw IOException("The workbook has no sheets")
            val targets = HashMap<String, String>()
            parts["xl/_rels/workbook.xml.rels"]?.let { rels ->
                parse(rels) { element, attributes ->
                    if (element == "Relationship") {
                        val id = attributes.byName("Id")
                        val target = attributes.byName("Target")
                        if (id != null && target != null) targets[id] = target
                    }
                }
            }
            val sheetPath = relationId?.let(targets::get)?.let { if (it.startsWith("/")) it.removePrefix("/") else "xl/$it" }
                ?: parts.keys.filter { it.matches(Regex("xl/worksheets/sheet\\d+\\.xml")) }.minByOrNull { it.filter(Char::isDigit).toInt() }
                ?: throw IOException("The workbook has no worksheet")
            val sheet = parts[sheetPath] ?: throw IOException("Missing $sheetPath")
            return XlsxSheet(sheetName, cells(sheet, sharedStrings(parts["xl/sharedStrings.xml"])))
        }

        /** Unpacks only the parts [read] looks at: the rest of the file (styles, pictures, themes) is skipped unread. */
        private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
            val parts = HashMap<String, ByteArray>()
            var total = 0L
            try {
                ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val name = entry.name.removePrefix("/")
                        if (entry.isDirectory || name !in PARTS && !name.matches(WORKSHEET)) continue
                        val out = ByteArrayOutputStream()
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val read = zip.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            if (out.size() > MAX_PART_BYTES) throw IOException("$name is too big")
                            if (total + out.size() > MAX_TOTAL_BYTES) throw IOException("The xlsx file unpacks too big")
                        }
                        total += out.size()
                        parts[name] = out.toByteArray()
                    }
                }
            } catch (e: IllegalArgumentException) {
                throw IOException("Not a zip file", e)
            }
            if (parts.isEmpty()) throw IOException("Not an xlsx file")
            return parts
        }

        private fun sharedStrings(xml: ByteArray?): List<String> {
            if (xml == null) return emptyList()
            val strings = ArrayList<String>()
            val text = StringBuilder()
            var inText = false
            // The phonetic reading of East Asian text, kept beside it: not part of the string
            var inPhonetic = false
            parse(
                xml,
                onStart = { element, _ ->
                    when (element) {
                        "si" -> text.setLength(0)
                        "t" -> inText = true
                        "rPh" -> inPhonetic = true
                    }
                },
                onEnd = { element ->
                    when (element) {
                        "t" -> inText = false
                        "rPh" -> inPhonetic = false
                        "si" -> strings += text.toString()
                    }
                },
                onText = { if (inText && !inPhonetic) text.append(it) },
            )
            return strings
        }

        private fun cells(xml: ByteArray, strings: List<String>): List<List<String?>> {
            val rows = ArrayList<List<String?>>()
            var row = ArrayList<String?>()
            var column = 0
            var type: String? = null
            val value = StringBuilder()
            var inValue = false
            parse(
                xml,
                onStart = { element, attributes ->
                    when (element) {
                        "row" -> row = ArrayList()
                        "c" -> {
                            column = attributes.byName("r")?.let(::columnOf) ?: row.size
                            type = attributes.byName("t")
                            value.setLength(0)
                        }
                        "v", "t" -> inValue = true
                    }
                },
                onEnd = { element ->
                    when (element) {
                        "v", "t" -> inValue = false
                        "c" -> {
                            while (row.size < column) row += null
                            val text = value.toString()
                            row += when {
                                type == "s" -> text.trim().toIntOrNull()?.let(strings::getOrNull)
                                text.isEmpty() -> null
                                else -> text
                            }
                        }
                        "row" -> rows += row
                    }
                },
                onText = { if (inValue) value.append(it) },
            )
            return rows
        }

        private fun ByteArray.indexOf(part: ByteArray): Int =
            (0..size - part.size).firstOrNull { start -> part.indices.all { this[start + it] == part[it] } } ?: -1

        /** The column of a cell reference, from 0: `B12` is 1. */
        private fun columnOf(reference: String): Int =
            reference.takeWhile { it.isLetter() }.fold(0) { acc, c -> acc * 26 + (c.uppercaseChar() - 'A' + 1) } - 1

        private fun Attributes.byName(name: String): String? =
            (0 until length).firstOrNull { localOf(getQName(it)) == name }?.let(::getValue)

        private fun localOf(name: String) = name.substringAfter(':')

        private fun parse(xml: ByteArray, onStart: (String, Attributes) -> Unit) = parse(xml, onStart, {}, {})

        private fun parse(
            xml: ByteArray,
            onStart: (String, Attributes) -> Unit,
            onEnd: (String) -> Unit,
            onText: (CharSequence) -> Unit,
        ) {
            // No document types: a spreadsheet has none, and they are how XML pulls in other files. The features below
            // are those of Xerces, on the desktop; Android's parser has none of them, so the file is refused here
            if (xml.indexOf(DOCTYPE) >= 0) throw IOException("A document type in the xlsx file")
            val factory = SAXParserFactory.newInstance()
            runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            val handler = object : DefaultHandler() {
                override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) =
                    onStart(localOf(qName), attributes)

                override fun endElement(uri: String?, localName: String?, qName: String) = onEnd(localOf(qName))

                override fun characters(ch: CharArray, start: Int, length: Int) = onText(String(ch, start, length))

                // Nothing is fetched from outside, whatever the parser
                override fun resolveEntity(publicId: String?, systemId: String?) = InputSource(ByteArrayInputStream(ByteArray(0)))
            }
            try {
                factory.newSAXParser().parse(InputSource(ByteArrayInputStream(xml)), handler)
            } catch (e: org.xml.sax.SAXException) {
                throw IOException("Malformed XML in the xlsx file", e)
            }
        }
    }
}
