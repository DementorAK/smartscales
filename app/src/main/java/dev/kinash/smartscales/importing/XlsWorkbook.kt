package dev.kinash.smartscales.importing

import java.io.IOException
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The first worksheet of an old Excel `.xls` file (BIFF8, Excel 97–2003), the format OKOK International exports
 * its weighings in, read without a library: the Compound File container ([MS-CFB]) holding the `Workbook` stream,
 * and in it the shared strings and the cells of the first sheet ([MS-XLS]). Text, numbers and the results of
 * formulas are read; styles, dates as dates and everything else are not. Cells come back as text, a number in its
 * shortest form (`83.9`, `5`), rows from the top with empty cells as "".
 */
object XlsWorkbook {
    /** Throws [IOException] when [bytes] are not an `.xls` with a sheet. */
    fun firstSheet(bytes: ByteArray): List<List<String>> {
        val stream = CompoundFile(bytes).stream("Workbook") ?: CompoundFile(bytes).stream("Book")
            ?: throw IOException("No workbook in the file")
        return Biff(stream).firstSheet()
    }

    /** A Compound File: sectors chained by the FAT, small streams in the mini stream, a directory of entries. */
    private class CompoundFile(private val bytes: ByteArray) {
        private val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        private val sectorSize = 1 shl buffer.getShort(0x1E).toInt().also { if (it !in 7..16) throw IOException("Bad sector size") }
        private val miniSectorSize = 1 shl buffer.getShort(0x20).toInt().also { if (it !in 2..12) throw IOException("Bad mini sector size") }
        private val miniCutoff = buffer.getInt(0x38).toLong() and 0xFFFFFFFFL
        private val fat: IntArray = readFat()
        private val directory: List<Entry> = readDirectory()

        private class Entry(val name: String, val type: Int, val start: Int, val size: Long)

        fun stream(name: String): ByteArray? {
            val entry = directory.firstOrNull { it.type == STREAM && it.name.equals(name, ignoreCase = true) } ?: return null
            if (entry.size > Tables.MAX_BYTES) throw IOException("The workbook is too big")
            return if (entry.size < miniCutoff) {
                val root = directory.firstOrNull { it.type == ROOT } ?: throw IOException("No root entry")
                val mini = chain(root.start, root.size)
                val miniFat = readMiniFat()
                val out = ByteArray(entry.size.toInt())
                var sector = entry.start
                var written = 0
                val seen = HashSet<Int>()
                while (written < out.size) {
                    if (sector < 0 || sector >= miniFat.size || !seen.add(sector)) throw IOException("Broken mini stream chain")
                    val from = sector * miniSectorSize
                    val n = minOf(miniSectorSize, out.size - written)
                    if (from + n > mini.size) throw IOException("Mini sector out of the stream")
                    System.arraycopy(mini, from, out, written, n)
                    written += n
                    sector = miniFat[sector]
                }
                out
            } else {
                chain(entry.start, entry.size)
            }
        }

        private fun sectorOffset(sector: Int): Int {
            val offset = (sector.toLong() + 1) * sectorSize
            if (sector < 0 || offset + sectorSize > bytes.size) throw IOException("Sector $sector out of the file")
            return offset.toInt()
        }

        /** The sectors of a chain from [start], [size] bytes of them; a size below zero reads the whole chain. */
        private fun chain(start: Int, size: Long): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            var sector = start
            val seen = HashSet<Int>()
            while (sector >= 0 && (size < 0 || out.size() < size)) {
                if (sector >= fat.size || !seen.add(sector)) throw IOException("Broken sector chain")
                out.write(bytes, sectorOffset(sector), sectorSize)
                if (out.size() > Tables.MAX_BYTES + sectorSize) throw IOException("The file is too big")
                sector = fat[sector]
            }
            val all = out.toByteArray()
            return if (size < 0 || all.size <= size) all else all.copyOf(size.toInt())
        }

        private fun readFat(): IntArray {
            val perSector = sectorSize / 4
            val fatSectors = ArrayList<Int>()
            for (i in 0 until 109) {
                val s = buffer.getInt(0x4C + i * 4)
                if (s >= 0) fatSectors += s
            }
            var difat = buffer.getInt(0x44)
            val seen = HashSet<Int>()
            while (difat >= 0) {
                if (!seen.add(difat) || seen.size > 10_000) throw IOException("Broken DIFAT")
                val at = sectorOffset(difat)
                for (i in 0 until perSector - 1) {
                    val s = buffer.getInt(at + i * 4)
                    if (s >= 0) fatSectors += s
                }
                difat = buffer.getInt(at + (perSector - 1) * 4)
            }
            val fat = IntArray(fatSectors.size * perSector)
            fatSectors.forEachIndexed { k, s ->
                val at = sectorOffset(s)
                for (i in 0 until perSector) fat[k * perSector + i] = buffer.getInt(at + i * 4)
            }
            return fat
        }

        private fun readMiniFat(): IntArray {
            val start = buffer.getInt(0x3C)
            if (start < 0) return IntArray(0)
            val raw = chain(start, -1)
            val b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            return IntArray(raw.size / 4) { b.getInt(it * 4) }
        }

        private fun readDirectory(): List<Entry> {
            val raw = chain(buffer.getInt(0x30), -1)
            val b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            return (0 until raw.size / 128).mapNotNull { i ->
                val at = i * 128
                val nameBytes = b.getShort(at + 0x40).toInt().coerceIn(0, 64)
                val type = raw[at + 0x42].toInt()
                if (type == 0) return@mapNotNull null
                val name = String(raw, at, (nameBytes - 2).coerceAtLeast(0), Charsets.UTF_16LE)
                Entry(name, type, b.getInt(at + 0x74), b.getInt(at + 0x78).toLong() and 0xFFFFFFFFL)
            }
        }

        companion object {
            const val STREAM = 2
            const val ROOT = 5
        }
    }

    /** The BIFF8 records of the workbook stream. */
    private class Biff(private val data: ByteArray) {
        private val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

        private class Record(val type: Int, val start: Int, val length: Int)

        private val records: List<Record> = buildList {
            var at = 0
            while (at + 4 <= data.size) {
                val type = buffer.getShort(at).toInt() and 0xFFFF
                val length = buffer.getShort(at + 2).toInt() and 0xFFFF
                if (at + 4 + length > data.size) break
                add(Record(type, at + 4, length))
                at += 4 + length
            }
        }

        fun firstSheet(): List<List<String>> {
            var strings: List<String> = emptyList()
            val cells = HashMap<Pair<Int, Int>, String>()
            var sheets = 0
            var inSheet = false
            var i = 0
            while (i < records.size) {
                val r = records[i]
                when (r.type) {
                    BOF -> {
                        // The globals come first, then each sheet; only the first worksheet is read
                        val kind = buffer.getShort(r.start + 2).toInt() and 0xFFFF
                        if (kind == WORKSHEET) {
                            sheets++
                            inSheet = sheets == 1
                        }
                    }
                    EOF -> if (inSheet) break
                    SST -> strings = sharedStrings(i)
                    LABEL_SST -> if (inSheet) cells[at(r)] = strings.getOrNull(buffer.getInt(r.start + 6)).orEmpty()
                    LABEL -> if (inSheet) cells[at(r)] = Chunks(listOf(r.start + 6 to r.start + r.length)).string(sixteenBitLength = true)
                    NUMBER -> if (inSheet) cells[at(r)] = number(buffer.getDouble(r.start + 6))
                    RK -> if (inSheet) cells[at(r)] = number(rk(buffer.getInt(r.start + 6)))
                    MULRK -> if (inSheet) {
                        val row = buffer.getShort(r.start).toInt() and 0xFFFF
                        val first = buffer.getShort(r.start + 2).toInt() and 0xFFFF
                        val count = (r.length - 6) / 6
                        for (k in 0 until count) cells[row to first + k] = number(rk(buffer.getInt(r.start + 4 + k * 6 + 2)))
                    }
                    FORMULA -> if (inSheet) {
                        val result = data.copyOfRange(r.start + 6, r.start + 14)
                        val isNumber = !(result[6] == 0xFF.toByte() && result[7] == 0xFF.toByte())
                        if (isNumber) {
                            cells[at(r)] = number(ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN).double)
                        } else if (result[0].toInt() == 0) {
                            // A text result follows in a STRING record
                            val next = records.getOrNull(i + 1)
                            if (next?.type == STRING) cells[at(r)] = Chunks(listOf(next.start to next.start + next.length)).string(sixteenBitLength = true)
                        }
                    }
                }
                i++
            }
            if (sheets == 0) throw IOException("The workbook has no sheets")
            val byRow = cells.entries.groupBy({ it.key.first }, { it.key.second to it.value })
            val rows = byRow.keys.maxOrNull() ?: return emptyList()
            return (0..rows).map { row ->
                val line = byRow[row].orEmpty().toMap()
                val columns = line.keys.maxOrNull() ?: -1
                (0..columns).map { line[it].orEmpty() }
            }
        }

        private fun at(r: Record) = (buffer.getShort(r.start).toInt() and 0xFFFF) to (buffer.getShort(r.start + 2).toInt() and 0xFFFF)

        /** The shared strings of the SST record at [index] and the CONTINUE records after it. */
        private fun sharedStrings(index: Int): List<String> {
            val parts = ArrayList<Pair<Int, Int>>()
            val sst = records[index]
            parts += sst.start + 8 to sst.start + sst.length
            var k = index + 1
            while (k < records.size && records[k].type == CONTINUE) {
                parts += records[k].start to records[k].start + records[k].length
                k++
            }
            val unique = buffer.getInt(sst.start + 4)
            if (unique < 0 || unique > 1_000_000) throw IOException("Bad shared strings")
            val chunks = Chunks(parts)
            return List(unique) { if (chunks.atEnd()) "" else chunks.string(sixteenBitLength = true) }
        }

        /**
         * Bytes split over a record and its CONTINUE records. A string's characters may break over two of them;
         * the next one then starts with a byte saying whether its characters are one byte or two.
         */
        private inner class Chunks(private val parts: List<Pair<Int, Int>>) {
            private var part = 0
            private var at = parts.firstOrNull()?.first ?: 0

            fun atEnd(): Boolean {
                settle()
                return part >= parts.size
            }

            private fun settle() {
                while (part < parts.size && at >= parts[part].second) {
                    part++
                    if (part < parts.size) at = parts[part].first
                }
            }

            private fun byte(): Int {
                settle()
                if (part >= parts.size) throw IOException("A string runs past its record")
                return data[at++].toInt() and 0xFF
            }

            private fun short(): Int = byte() or (byte() shl 8)

            private fun int(): Int = short() or (short() shl 16)

            private fun skip(n: Int) = repeat(n) { byte() }

            fun string(sixteenBitLength: Boolean): String {
                val length = if (sixteenBitLength) short() else byte()
                val flags = byte()
                var wide = flags and 0x01 != 0
                val runs = if (flags and 0x08 != 0) short() else 0
                val extra = if (flags and 0x04 != 0) int() else 0
                val out = StringBuilder(length)
                while (out.length < length) {
                    val before = part
                    settle()
                    if (part >= parts.size) throw IOException("A string runs past its record")
                    if (part != before) {
                        // The characters go on in the next record, which says again whether they are wide
                        wide = data[at++].toInt() and 0x01 != 0
                    }
                    if (wide) out.append((data[at++].toInt() and 0xFF or ((data[at++].toInt() and 0xFF) shl 8)).toChar())
                    else out.append((data[at++].toInt() and 0xFF).toChar())
                }
                skip(runs * 4 + extra)
                return out.toString()
            }
        }

        private fun rk(value: Int): Double {
            val divided = value and 0x01 != 0
            val number = if (value and 0x02 != 0) {
                (value shr 2).toDouble()
            } else {
                java.lang.Double.longBitsToDouble((value.toLong() and 0xFFFFFFFCL) shl 32)
            }
            return if (divided) number / 100 else number
        }

        private fun number(value: Double): String =
            if (!value.isFinite()) "" else BigDecimal(value.toString()).stripTrailingZeros().toPlainString()

        companion object {
            const val BOF = 0x0809
            const val EOF = 0x000A
            const val SST = 0x00FC
            const val CONTINUE = 0x003C
            const val LABEL_SST = 0x00FD
            const val LABEL = 0x0204
            const val NUMBER = 0x0203
            const val RK = 0x027E
            const val MULRK = 0x00BD
            const val FORMULA = 0x0006
            const val STRING = 0x0207
            const val WORKSHEET = 0x0010
        }
    }
}
