package dev.kinash.smartscales.importing

import dev.kinash.smartscales.core.model.LengthUnit
import dev.kinash.smartscales.core.model.ManualFields
import dev.kinash.smartscales.core.model.WeightUnit
import dev.kinash.smartscales.core.model.ScaleValues
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.text.ParsePosition
import java.time.DateTimeException
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.TemporalAccessor
import java.util.concurrent.ConcurrentHashMap
import java.time.temporal.ChronoUnit
import java.util.Locale

/** What a column of an imported table holds. */
enum class ImportField(val kind: Kind, val key: String? = null) {
    IGNORE(Kind.NONE),
    DATE_TIME(Kind.DATE),
    DATE(Kind.DATE),
    TIME(Kind.DATE),
    WEIGHT(Kind.WEIGHT),
    IMPEDANCE(Kind.NUMBER),
    HEART_RATE(Kind.NUMBER),
    WAIST(Kind.LENGTH, ManualFields.WAIST_CM),
    HIP(Kind.LENGTH, ManualFields.HIP_CM),
    NECK(Kind.LENGTH, ManualFields.NECK_CM),
    NOTE(Kind.TEXT),
    TAGS(Kind.TEXT),

    // The other app's own values, kept as they are (shown "by the scale's data", requirements §4.3)
    FAT_PERCENT(Kind.NUMBER, ScaleValues.FAT_PERCENT),
    WATER_PERCENT(Kind.NUMBER, ScaleValues.WATER_PERCENT),

    /** Water in litres, as this app's table writes the scale's water: kept as the percentage of the weight. */
    WATER_LITERS(Kind.NUMBER, ScaleValues.WATER_PERCENT),
    MUSCLE_PERCENT(Kind.NUMBER, ScaleValues.MUSCLE_PERCENT),
    MUSCLE_KG(Kind.WEIGHT, ScaleValues.MUSCLE_KG),
    SKELETAL_MUSCLE_PERCENT(Kind.NUMBER, ScaleValues.SKELETAL_MUSCLE_PERCENT),
    LEAN_MASS_KG(Kind.WEIGHT, ScaleValues.LEAN_MASS_KG),
    BONE_KG(Kind.WEIGHT, ScaleValues.BONE_KG),
    PROTEIN_PERCENT(Kind.NUMBER, ScaleValues.PROTEIN_PERCENT),
    VISCERAL_FAT(Kind.NUMBER, ScaleValues.VISCERAL_FAT),
    BMR_KCAL(Kind.NUMBER, ScaleValues.BMR_KCAL),
    BODY_AGE(Kind.NUMBER, ScaleValues.BODY_AGE),
    ;

    enum class Kind { NONE, DATE, WEIGHT, LENGTH, NUMBER, TEXT }
}

/** The weight unit of an imported table; OKOK may write catties (jin, half a kilogram). */
enum class ImportWeightUnit(val kilograms: Double) {
    KG(1.0),
    LB(WeightUnit.KG_PER_POUND),
    ST(WeightUnit.KG_PER_POUND * WeightUnit.POUNDS_PER_STONE),
    JIN(0.5),
}

/** How the dates of a table are written. */
enum class DateFormat(internal val patterns: List<String>) {
    ISO(listOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd'T'HH:mm", "yyyy-MM-dd")),
    DAY_MONTH_DOT(listOf("d.M.yyyy H:mm:ss", "d.M.yyyy H:mm", "d.M.yyyy")),
    DAY_MONTH_SLASH(listOf("d/M/yyyy H:mm:ss", "d/M/yyyy H:mm", "d/M/yyyy")),
    MONTH_DAY_SLASH(listOf("M/d/yyyy H:mm:ss", "M/d/yyyy H:mm", "M/d/yyyy h:mm a", "M/d/yyyy")),
    YEAR_SLASH(listOf("yyyy/M/d H:mm:ss", "yyyy/M/d H:mm", "yyyy/M/d")),

    /** Excel's day number, a date cell of an `.xlsx` or `.xls` read as a number. */
    EXCEL(emptyList()),

    /** OKOK's month and day without the year, which comes from the period the file covers. */
    MONTH_DAY(emptyList()),
}

/** Which app the table comes from, as told by its layout; only for the person to see. */
enum class TableSource { OKOK, OPENSCALE, SMARTSCALES, TABLE }

/**
 * How to read a table: where its header and data are, what each column holds and in which units. [detect]
 * works one out; the person can then change the columns, the units and the date format.
 */
data class ImportLayout(
    val source: TableSource,
    val headerRow: Int,
    val columns: List<ImportField>,
    val weightUnit: ImportWeightUnit,
    val lengthUnit: LengthUnit,
    val dateFormat: DateFormat,
    /** For OKOK's dates without a year: the first and last day of the export. */
    val period: ClosedRange<LocalDate>? = null,
) {
    val firstDataRow: Int get() = headerRow + 1

    companion object {
        fun detect(table: Table, locale: Locale = Locale.getDefault()): ImportLayout? = okok(table) ?: byHeaders(table, locale)

        /**
         * OKOK International's export, by its layout rather than its words, which are in the phone's language: a
         * title, the person, the period `2025-12-30 ~ 2026-01-02(4…)`, a header of 22 columns in a fixed order,
         * then rows starting with `MM-dd` and `HH:mm`. Values it has none for are `--`.
         */
        private fun okok(table: Table): ImportLayout? {
            val period = OKOK_PERIOD.find(table.cell(2, 0)) ?: return null
            if (table.rows.getOrNull(3).orEmpty().size < OKOK_COLUMNS.size - 4) return null
            if (!MONTH_DAY.matches(table.cell(4, 0).trim()) || !CLOCK.matches(table.cell(4, 1).trim())) return null
            val ends = runCatching { listOf(LocalDate.parse(period.groupValues[1]), LocalDate.parse(period.groupValues[2])) }.getOrNull() ?: return null
            val range = ends.min()..ends.max()
            return ImportLayout(
                source = TableSource.OKOK,
                headerRow = 3,
                columns = OKOK_COLUMNS.take(table.rows[3].size) + List((table.rows[3].size - OKOK_COLUMNS.size).coerceAtLeast(0)) { ImportField.IGNORE },
                weightUnit = weightUnitIn(table.cell(3, 2)) ?: ImportWeightUnit.KG,
                lengthUnit = if (inches(table.cell(3, 10))) LengthUnit.INCH else LengthUnit.CENTIMETRE,
                dateFormat = DateFormat.MONTH_DAY,
                period = range,
            )
        }

        /** Any other table: the header is the row among the first ten whose words name a date and a weight. */
        private fun byHeaders(table: Table, locale: Locale): ImportLayout? {
            val candidates = (0 until minOf(10, table.rows.size)).map { row -> row to table.rows[row].map(::fieldOf) }
            val (headerRow, fields) = candidates
                .filter { (_, fields) -> fields.any { it.kind == ImportField.Kind.DATE } && ImportField.WEIGHT in fields }
                .maxByOrNull { (_, fields) -> fields.count { it != ImportField.IGNORE } } ?: return null
            val header = table.rows[headerRow]
            val ours = header.firstOrNull()?.trim() in OUR_DATE && header.lastOrNull()?.trim() in OUR_SOURCE
            val openScale = header.any { it.trim() == "WEIGHT" } && header.any { it.trim() == "DATE" || it.trim() == "dateTime" }
            // A column is used once: the first that names a field takes it
            val seen = HashSet<ImportField>()
            val columns = fields.mapIndexed { column, named ->
                val text = header[column].lowercase()
                val field = if (named == ImportField.WATER_PERCENT && LITRES.containsMatchIn(text)) ImportField.WATER_LITERS else named
                when {
                    field == ImportField.IGNORE -> field
                    // Our own table's indicators are worked out again from its raw data, all but the scale's own
                    // values, which are raw data too (shown "by the scale")
                    ours && field.key?.startsWith("scale_") == true && SCALE_MARKS.none { it in text } -> ImportField.IGNORE
                    !seen.add(field) -> ImportField.IGNORE
                    else -> field
                }
            }
            val weightHeader = header.getOrNull(columns.indexOf(ImportField.WEIGHT)).orEmpty()
            val lengthHeader = columns.withIndex().firstOrNull { it.value.kind == ImportField.Kind.LENGTH }?.let { header[it.index] }.orEmpty()
            val draft = ImportLayout(
                source = when {
                    ours -> TableSource.SMARTSCALES
                    openScale -> TableSource.OPENSCALE
                    else -> TableSource.TABLE
                },
                headerRow = headerRow,
                columns = columns,
                weightUnit = weightUnitIn(weightHeader) ?: ImportWeightUnit.KG,
                lengthUnit = if (inches(lengthHeader)) LengthUnit.INCH else LengthUnit.CENTIMETRE,
                dateFormat = DateFormat.ISO,
            )
            return draft.copy(dateFormat = draft.detectDateFormat(table, locale))
        }

        /** The field a header names: its words without a unit after a comma or in brackets. */
        internal fun fieldOf(header: String): ImportField {
            val words = header.substringBefore('(').substringBefore('[').substringBefore(',')
                .replace('_', ' ').replace(Regex("([a-z])([A-Z])"), "$1 $2").trim().lowercase()
            return ALIASES[words] ?: ImportField.IGNORE
        }

        private fun weightUnitIn(header: String): ImportWeightUnit? {
            val unit = Regex("[(\\[,]\\s*([^)\\]]+)").find(header)?.groupValues?.get(1)?.trim()?.lowercase() ?: return null
            return when (unit) {
                "kg", "кг" -> ImportWeightUnit.KG
                "lb", "lbs", "фунты", "фунт" -> ImportWeightUnit.LB
                "st", "st:lb", "стоуны" -> ImportWeightUnit.ST
                "jin", "斤" -> ImportWeightUnit.JIN
                else -> null
            }
        }

        private fun inches(header: String): Boolean = Regex("[(\\[,]\\s*(in|inch|inches|дюймы|дюйм)\\s*[)\\]]?$").containsMatchIn(header.trim().lowercase())

        /** Weights out of reach of a person are not weighings. */
        private const val MIN_KG = 2.0
        private const val MAX_KG = 400.0

        private val OKOK_PERIOD = Regex("(\\d{4}-\\d{2}-\\d{2})\\s*~\\s*(\\d{4}-\\d{2}-\\d{2})")
        private val MONTH_DAY = Regex("\\d{1,2}-\\d{1,2}")
        private val CLOCK = Regex("\\d{1,2}:\\d{2}(:\\d{2})?")

        /**
         * The columns of OKOK International's export, in order (as its export writes them): date, time, weight,
         * BMI, degree of obesity, fat %, fat mass, over the goal, goal, age, height, protein %, muscle %, muscle
         * mass, water %, water mass, metabolism, bone mass, skeletal muscle %, its mass, body age, lean mass.
         * BMI, masses that follow from a percentage and the person's data are not taken: they are worked out here.
         */
        private val OKOK_COLUMNS = listOf(
            ImportField.DATE, ImportField.TIME, ImportField.WEIGHT, ImportField.IGNORE, ImportField.IGNORE,
            ImportField.FAT_PERCENT, ImportField.IGNORE, ImportField.IGNORE, ImportField.IGNORE, ImportField.IGNORE,
            ImportField.IGNORE, ImportField.PROTEIN_PERCENT, ImportField.MUSCLE_PERCENT, ImportField.MUSCLE_KG,
            ImportField.WATER_PERCENT, ImportField.IGNORE, ImportField.BMR_KCAL, ImportField.BONE_KG,
            ImportField.SKELETAL_MUSCLE_PERCENT, ImportField.IGNORE, ImportField.BODY_AGE, ImportField.LEAN_MASS_KG,
        )

        private val OUR_DATE = setOf("Date and time", "Дата и время")

        /** How this app's table names the scale's own values: "Body fat (by the scale), %". */
        private val SCALE_MARKS = listOf("by the scale", "по данным весов")
        private val LITRES = Regex("[(\\[,]\\s*(l|л|liters|litres|литры)\\s*[)\\]]?$")
        private val OUR_SOURCE = setOf("Source", "Источник")

        /** Header words in English and Russian, openScale's column keys and the usual words of other tables. */
        private val ALIASES: Map<String, ImportField> = buildMap {
            fun put(field: ImportField, vararg words: String) = words.forEach { put(it, field) }
            put(ImportField.DATE_TIME, "date and time", "дата и время", "datetime", "date time", "timestamp", "время измерения")
            put(ImportField.DATE, "date", "дата", "datum", "fecha", "data", "day")
            put(ImportField.TIME, "time", "время", "zeit", "hora", "heure")
            put(ImportField.WEIGHT, "weight", "вес", "gewicht", "peso", "poids", "body weight", "масса тела")
            put(ImportField.IMPEDANCE, "impedance", "импеданс", "resistance", "сопротивление")
            put(ImportField.HEART_RATE, "heart rate", "пульс", "heartrate", "pulse", "hr")
            put(ImportField.WAIST, "waist", "талия")
            put(ImportField.HIP, "hip", "hips", "бёдра", "бедра")
            put(ImportField.NECK, "neck", "шея")
            put(ImportField.NOTE, "note", "notes", "заметка", "comment", "comments", "комментарий")
            put(ImportField.TAGS, "tags", "метки")
            put(ImportField.FAT_PERCENT, "fat", "body fat", "bodyfat", "fat percent", "body fat percentage", "жир", "процент жира")
            put(ImportField.WATER_PERCENT, "water", "body water", "вода", "вода тела")
            put(ImportField.MUSCLE_PERCENT, "muscle", "мышцы")
            put(ImportField.MUSCLE_KG, "muscle mass", "масса мышц", "мышечная масса")
            put(ImportField.SKELETAL_MUSCLE_PERCENT, "skeletal muscle", "скелетные мышцы")
            put(ImportField.LEAN_MASS_KG, "lbm", "lean body mass", "lean mass", "fat-free mass", "безжировая масса", "тощая масса")
            put(ImportField.BONE_KG, "bone", "bone mass", "кости", "костная масса")
            put(ImportField.PROTEIN_PERCENT, "protein", "белок")
            put(ImportField.VISCERAL_FAT, "visceral fat", "visceral", "висцеральный жир")
            put(ImportField.BMR_KCAL, "bmr", "basal metabolic rate", "основной обмен", "metabolism", "обмен")
            put(ImportField.BODY_AGE, "body age", "возраст тела", "metabolic age")
        }
    }

    /**
     * The date format that reads the most dates of the table (a row of rubbish does not change it); among equals,
     * day before month unless the phone is American.
     */
    fun detectDateFormat(table: Table, locale: Locale): DateFormat {
        val order = DateFormat.entries.filter { it != DateFormat.MONTH_DAY }.let { all ->
            if (locale.country == "US") all.sortedBy { if (it == DateFormat.MONTH_DAY_SLASH) 0 else 1 } else all
        }
        val dates = dataRows(table).mapNotNull { dateText(table, it).takeIf { text -> text.isNotBlank() } }.take(200)
        if (dates.isEmpty()) return DateFormat.ISO
        val read = order.map { format -> format to dates.count { copy(dateFormat = format).parseDate(it) != null } }
        val best = read.maxOf { it.second }
        return if (best == 0) DateFormat.ISO else read.first { it.second == best }.first
    }

    internal fun dataRows(table: Table): IntRange = firstDataRow until table.rows.size

    private fun dateText(table: Table, row: Int): String {
        val dateColumn = columns.indexOf(ImportField.DATE_TIME).takeIf { it >= 0 } ?: columns.indexOf(ImportField.DATE)
        return if (dateColumn >= 0) table.cell(row, dateColumn).trim() else ""
    }

    /** A date, or a date with its time, as [dateFormat] writes it; null when it does not read. */
    fun parseDate(text: String): LocalDateTime? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        return when (dateFormat) {
            DateFormat.EXCEL -> trimmed.replace(',', '.').toDoubleOrNull()?.takeIf { it in 1.0..200_000.0 }?.let(::excelDate)
            DateFormat.MONTH_DAY -> null
            else -> dateFormat.patterns.firstNotNullOfOrNull { pattern ->
                val parsed = parse(trimmed, pattern) ?: return@firstNotNullOfOrNull null
                if ('H' in pattern || 'h' in pattern) LocalDateTime.from(parsed) else LocalDate.from(parsed).atStartOfDay()
            }
        }
    }

    /** A time of day: `07:15`, `7:15:30`, `7:15 PM`, or Excel's fraction of a day. */
    fun parseTime(text: String): LocalTime? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        trimmed.replace(',', '.').toDoubleOrNull()?.takeIf { it in 0.0..1.0 }?.let { return LocalTime.ofSecondOfDay((it * 86_400).toLong().coerceAtMost(86_399)) }
        return TIME_PATTERNS.firstNotNullOfOrNull { pattern -> parse(trimmed, pattern)?.let(LocalTime::from) }
    }

    /**
     * [text] by [pattern], or null when it does not read: tried without an exception for every pattern that does
     * not fit, which thousands of rows times several patterns would make slow. AM and PM in either case.
     */
    private fun parse(text: String, pattern: String): TemporalAccessor? {
        val formatter = FORMATTERS.getOrPut(pattern) {
            DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(pattern).toFormatter(Locale.US)
        }
        val position = ParsePosition(0)
        formatter.parseUnresolved(text, position) ?: return null
        if (position.errorIndex >= 0 || position.index != text.length) return null
        return try {
            formatter.parse(text)
        } catch (e: DateTimeException) {
            // A day that is not in the month and the like
            null
        }
    }

    private fun excelDate(days: Double): LocalDateTime =
        LocalDateTime.of(1899, 12, 30, 0, 0).plusSeconds(Math.round(days * 86_400))

    /** A number written with a point or a comma; OKOK's `--` and other non-numbers are none. */
    fun parseNumber(text: String): Double? = text.trim().replace(" ", "").replace(' '.toString(), "").replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }

    /** A weight in kilograms: stones may be written `11:4.5` or `11 st 4.5 lb`. */
    fun parseWeight(text: String): Double? {
        if (weightUnit == ImportWeightUnit.ST) {
            Regex("^\\s*(\\d+)\\s*(?::|st)\\s*([\\d.,]+)?\\s*(?:lb)?\\s*$").find(text.lowercase())?.let { m ->
                val pounds = m.groupValues[1].toDouble() * WeightUnit.POUNDS_PER_STONE + (m.groupValues[2].replace(',', '.').toDoubleOrNull() ?: 0.0)
                return pounds * WeightUnit.KG_PER_POUND
            }
        }
        return parseNumber(text)?.let { it * weightUnit.kilograms }
    }

    /**
     * The weighings of [table] by this layout, with the rows that are not: no date or no weight that reads, a
     * weight out of reach of a person. OKOK's dates get their year from the period, going back from its end:
     * a month after the one of the row below means the year before.
     */
    fun rows(table: Table): Rows {
        val good = ArrayList<ImportRow>()
        val bad = ArrayList<Int>()
        val okokYears = if (dateFormat == DateFormat.MONTH_DAY) okokYears(table) else emptyMap()
        for (row in dataRows(table)) {
            val cells = table.rows[row]
            if (cells.all { it.isBlank() }) continue
            val at = when {
                dateFormat == DateFormat.MONTH_DAY -> monthDay(table, row, okokYears[row])
                columns.contains(ImportField.DATE_TIME) -> parseDate(cell(table, row, ImportField.DATE_TIME))
                else -> parseDate(cell(table, row, ImportField.DATE))?.let { date ->
                    val time = parseTime(cell(table, row, ImportField.TIME))
                    if (time != null) date.toLocalDate().atTime(time) else date
                }
            }
            val weight = parseWeight(cell(table, row, ImportField.WEIGHT))
            if (at == null || weight == null || weight !in MIN_KG..MAX_KG) {
                bad += row
                continue
            }
            val manual = HashMap<String, Double>()
            val values = HashMap<String, Double>()
            var impedance: Double? = null
            var heartRate: Int? = null
            var note: String? = null
            var tags: List<String> = emptyList()
            columns.forEachIndexed { column, field ->
                val text = cells.getOrNull(column).orEmpty()
                when (field.kind) {
                    ImportField.Kind.LENGTH -> parseNumber(text)?.takeIf { it > 0 }?.let { manual[field.key!!] = lengthUnit.toCentimetres(it) }
                    ImportField.Kind.WEIGHT -> if (field != ImportField.WEIGHT) parseWeight(text)?.takeIf { it > 0 }?.let { values[field.key!!] = it }
                    ImportField.Kind.NUMBER -> {
                        val number = parseNumber(text) ?: return@forEachIndexed
                        when (field) {
                            ImportField.IMPEDANCE -> impedance = number.takeIf { it > 0 }
                            ImportField.HEART_RATE -> heartRate = number.toInt().takeIf { it in 20..250 }
                            ImportField.WATER_LITERS -> if (number > 0) values[field.key!!] = number / weight * 100
                            // OKOK writes 0 where it had nothing worked out
                            else -> if (number > 0) values[field.key!!] = number
                        }
                    }
                    ImportField.Kind.TEXT -> {
                        // This app's table put an apostrophe before text a spreadsheet would run as a formula
                        val written = if (source == TableSource.SMARTSCALES && text.length > 1 && text[0] == '\'' && text[1] in FORMULA_STARTS) text.substring(1) else text
                        when (field) {
                            ImportField.NOTE -> note = written.trim().ifEmpty { null }
                            else -> tags = written.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
                        }
                    }
                    else -> Unit
                }
            }
            good += ImportRow(row, at.truncatedTo(ChronoUnit.SECONDS), weight, impedance, heartRate, manual, values, note, tags)
        }
        return Rows(good, bad)
    }

    class Rows(val rows: List<ImportRow>, val unreadable: List<Int>)

    private fun cell(table: Table, row: Int, field: ImportField): String {
        val column = columns.indexOf(field)
        return if (column < 0) "" else table.cell(row, column)
    }

    private fun monthDay(table: Table, row: Int, year: Int?): LocalDateTime? {
        val (month, day) = cell(table, row, ImportField.DATE).trim().split('-').mapNotNull { it.toIntOrNull() }.takeIf { it.size == 2 } ?: return null
        val date = runCatching { LocalDate.of(year ?: return null, month, day) }.getOrNull() ?: return null
        val time = parseTime(cell(table, row, ImportField.TIME)) ?: LocalTime.MIDNIGHT
        return date.atTime(time)
    }

    /**
     * Years for OKOK's rows. A date of the period that fits one year only takes it; over a longer period the rows
     * are in order, and the year steps where the month turns back (or forward, in a newest-first file).
     */
    private fun okokYears(table: Table): Map<Int, Int> {
        val period = period ?: return emptyMap()
        val column = columns.indexOf(ImportField.DATE)
        val monthDays = dataRows(table).associateWith { row ->
            table.cell(row, column).trim().split('-').mapNotNull { it.toIntOrNull() }.takeIf { it.size == 2 }
        }.filterValues { it != null }.mapValues { it.value!! }
        if (monthDays.isEmpty()) return emptyMap()
        val years = period.start.year..period.endInclusive.year
        fun fits(year: Int, md: List<Int>) = runCatching { LocalDate.of(year, md[0], md[1]) in period }.getOrDefault(false)
        val rows = monthDays.keys.sorted()
        val keys = rows.map { monthDays.getValue(it).let { md -> md[0] * 100 + md[1] } }
        // Oldest first when the first row is the period's first day or the last row its last; newest first the
        // other way round; otherwise as the dates mostly go
        fun key(date: LocalDate) = date.monthValue * 100 + date.dayOfMonth
        val ascending = when {
            keys.first() == key(period.start) || keys.last() == key(period.endInclusive) -> true
            keys.first() == key(period.endInclusive) || keys.last() == key(period.start) -> false
            else -> keys.zipWithNext().count { (a, b) -> b >= a } >= keys.zipWithNext().count { (a, b) -> b < a }
        }
        val out = HashMap<Int, Int>()
        var year = if (ascending) years.first else years.last
        var previous: Int? = null
        for ((i, row) in rows.withIndex()) {
            val key = keys[i]
            if (previous != null) {
                if (ascending && key < previous) year++
                if (!ascending && key > previous) year--
            }
            previous = key
            val only = years.filter { fits(it, monthDays.getValue(row)) }
            out[row] = if (only.size == 1) only.single().also { year = it } else year
        }
        return out
    }

}

private val TIME_PATTERNS = listOf("H:mm:ss", "H:mm", "h:mm a", "h:mm:ss a")
private val FORMATTERS = ConcurrentHashMap<String, DateTimeFormatter>()
private val FORMULA_STARTS = setOf('=', '+', '-', '@', '\t', '\r')

/** One weighing read from a table; [row] is its row in the file, counted from 0. */
data class ImportRow(
    val row: Int,
    val takenAt: LocalDateTime,
    val weightKg: Double,
    val impedance: Double?,
    val heartRate: Int?,
    val manualValues: Map<String, Double>,
    val values: Map<String, Double>,
    val note: String?,
    val tags: List<String>,
)
