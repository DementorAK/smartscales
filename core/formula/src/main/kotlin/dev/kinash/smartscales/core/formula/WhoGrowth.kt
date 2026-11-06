package dev.kinash.smartscales.core.formula

import dev.kinash.smartscales.core.model.Sex
import java.io.IOException

/** What a growth chart places: BMI, weight or height (length under 2) for age. */
enum class GrowthIndicator {
    BMI,
    WEIGHT,
    HEIGHT,
}

/** Why a file is not a WHO table. */
enum class WhoTableProblem {
    /** Not an `.xlsx` file, or one without a sheet. */
    NOT_XLSX,

    /** Its name says neither BMI, weight nor height for age, or not whose: boys or girls. */
    UNKNOWN_TABLE,

    /** No columns of age (Day, Week or Month) and L, M, S. */
    NO_LMS,

    /** The medians are not those of the indicator its name says, or the ages do not go up. */
    IMPLAUSIBLE,
}

class WhoTableException(val problem: WhoTableProblem, message: String) : Exception(message)

/**
 * One WHO table as downloaded: the Child Growth Standards (0–5 years) or the Growth Reference (5–19 years), BMI,
 * weight or length/height for age, for boys or for girls, with L, M and S by age in months.
 *
 * WHO publishes them under CC BY-NC-SA 3.0 IGO, which cannot go into GPL code (requirements §3), so they are not in
 * the app: the person downloads the `.xlsx` files from WHO and imports them. Every WHO table of these indicators has
 * the age in its first column (`Day`, `Week` or `Month`) and then `L`, `M` and `S`, and says the indicator and the
 * sex in its sheet and file names: `bfa-boys-zscore-expanded-tables.xlsx`, `hfa-girls-z-who-2007-exp.xlsx`.
 */
data class WhoTable(val indicator: GrowthIndicator, val sex: Sex, val rows: List<LmsRow>) {
    companion object {
        /** Days in a month, as WHO converts ages: 365.25 / 12. */
        const val DAYS_PER_MONTH = 30.4375

        /** Reads a downloaded table; [fileName] is its name as the person picked it. */
        fun readXlsx(bytes: ByteArray, fileName: String): WhoTable {
            val sheet = try {
                XlsxSheet.read(bytes)
            } catch (e: IOException) {
                throw WhoTableException(WhoTableProblem.NOT_XLSX, e.message.orEmpty())
            }
            val names = "${sheet.name} $fileName".lowercase()
            val (indicator, sex) = recognize(names)
                ?: throw WhoTableException(WhoTableProblem.UNKNOWN_TABLE, "Not a WHO BMI, weight or height table: $names")
            return WhoTable(indicator, sex, lmsRows(sheet.rows, indicator))
        }

        /** The indicator and sex in the sheet and file names, or null when they say none or more than one. */
        fun recognize(names: String): Pair<GrowthIndicator, Sex>? {
            val words = names.split(Regex("[^a-z0-9]+")).toSet()
            val indicators = buildSet {
                if ("bfa" in words || "bmi" in words || "bmifa" in words) add(GrowthIndicator.BMI)
                if ("wfa" in words) add(GrowthIndicator.WEIGHT)
                if ("lhfa" in words || "hfa" in words || "lfa" in words) add(GrowthIndicator.HEIGHT)
            }
            val sexes = buildSet {
                if ("boys" in words || "boy" in words) add(Sex.MALE)
                if ("girls" in words || "girl" in words) add(Sex.FEMALE)
            }
            return if (indicators.size == 1 && sexes.size == 1) indicators.single() to sexes.single() else null
        }

        private fun lmsRows(cells: List<List<String?>>, indicator: GrowthIndicator): List<LmsRow> {
            val headerAt = cells.indexOfFirst { row -> row.map { it?.trim()?.lowercase() }.containsAll(listOf("l", "m", "s")) }
            if (headerAt < 0) throw WhoTableException(WhoTableProblem.NO_LMS, "No L, M and S columns")
            val header = cells[headerAt].map { it?.trim()?.lowercase() }
            val age = header.indexOfFirst { it == "day" || it == "week" || it == "month" }
            if (age < 0) throw WhoTableException(WhoTableProblem.NO_LMS, "No Day, Week or Month column")
            val perMonth = when (header[age]) {
                "day" -> 1 / DAYS_PER_MONTH
                "week" -> 7 / DAYS_PER_MONTH
                else -> 1.0
            }
            val (l, m, s) = listOf("l", "m", "s").map(header::indexOf)
            val rows = cells.drop(headerAt + 1).mapNotNull { row ->
                val numbers = listOf(age, l, m, s).map { row.getOrNull(it)?.trim()?.toDoubleOrNull() }
                if (numbers.any { it == null || !it.isFinite() }) return@mapNotNull null
                LmsRow(numbers[0]!! * perMonth, numbers[1]!!, numbers[2]!!, numbers[3]!!)
            }
            val medians = MEDIANS.getValue(indicator)
            val plausible = rows.size >= 2 &&
                rows.zipWithNext().all { (a, b) -> a.ageMonths < b.ageMonths } &&
                rows.all { it.m in medians && it.s > 0 && it.s < 1 && it.ageMonths >= 0 && it.ageMonths <= MAX_MONTHS }
            if (!plausible) throw WhoTableException(WhoTableProblem.IMPLAUSIBLE, "Not the medians of $indicator")
            return rows
        }

        /** The oldest age of the WHO tables: 19 years. */
        private const val MAX_MONTHS = 19 * 12.0

        /** Medians of each indicator from birth to 19 years, with a margin: a table outside them is another one. */
        private val MEDIANS = mapOf(
            GrowthIndicator.BMI to 10.0..30.0,
            GrowthIndicator.WEIGHT to 1.5..90.0,
            GrowthIndicator.HEIGHT to 40.0..200.0,
        )
    }
}

/**
 * The WHO tables the person imported, by indicator and sex: the 0–5 standards and the 5–19 reference joined into one
 * chart each. A table imported later takes the ages it covers from the earlier ones of its indicator and sex, so a
 * newer download or a finer table (by day rather than by month) replaces what it overlaps.
 */
class WhoGrowthCharts(val tables: Map<Pair<GrowthIndicator, Sex>, List<LmsRow>>) {
    private val charts: Map<GrowthIndicator, GrowthChart> = GrowthIndicator.entries.mapNotNull { indicator ->
        val rows = Sex.entries.mapNotNull { sex -> tables[indicator to sex]?.let { sex to it } }.toMap()
        // WHO computes the tails of weight and BMI restricted, and not those of height
        rows.takeIf { it.isNotEmpty() }?.let { indicator to GrowthChart(it, restrictedTails = indicator != GrowthIndicator.HEIGHT) }
    }.toMap()

    fun chart(indicator: GrowthIndicator): GrowthChart? = charts[indicator]

    /** The ages in months a table covers, or null without one; the tables have no gaps, so this is one range. */
    fun ages(indicator: GrowthIndicator, sex: Sex): ClosedFloatingPointRange<Double>? =
        tables[indicator to sex]?.let { it.first().ageMonths..it.last().ageMonths }

    /**
     * These tables with [tables] over them, those starting later last: where two picked together meet, the later one
     * keeps the age they share, as WHO's 2–5 BMI table (by standing height) keeps 24 months from the 0–2 one (by length).
     */
    fun with(tables: List<WhoTable>): WhoGrowthCharts =
        tables.sortedBy { it.rows.first().ageMonths }.fold(this) { charts, table -> charts.with(table) }

    /** These tables with [table] over them. */
    fun with(table: WhoTable): WhoGrowthCharts {
        val key = table.indicator to table.sex
        val from = table.rows.first().ageMonths
        val to = table.rows.last().ageMonths
        val kept = tables[key].orEmpty().filter { it.ageMonths < from || it.ageMonths > to }
        return WhoGrowthCharts(tables + (key to (kept + table.rows).sortedBy { it.ageMonths }))
    }

    /** The tables as the app keeps them: `Indicator,Sex,Agemos,L,M,S`, one line per age. */
    fun toCsv(): String = buildString {
        appendLine("Indicator,Sex,Agemos,L,M,S")
        for ((key, rows) in tables) {
            for (row in rows) appendLine("${key.first},${key.second},${row.ageMonths},${row.l},${row.m},${row.s}")
        }
    }

    companion object {
        val EMPTY = WhoGrowthCharts(emptyMap())

        /** Reads [toCsv]; lines that do not read are skipped. */
        fun parseCsv(text: String): WhoGrowthCharts {
            val tables = HashMap<Pair<GrowthIndicator, Sex>, MutableList<LmsRow>>()
            for (line in text.lineSequence()) {
                val cells = line.split(',')
                if (cells.size != 6) continue
                val indicator = GrowthIndicator.entries.firstOrNull { it.name == cells[0] } ?: continue
                val sex = Sex.entries.firstOrNull { it.name == cells[1] } ?: continue
                val numbers = cells.drop(2).map { it.toDoubleOrNull() ?: Double.NaN }
                if (numbers.any { it.isNaN() }) continue
                tables.getOrPut(indicator to sex) { ArrayList() } += LmsRow(numbers[0], numbers[1], numbers[2], numbers[3])
            }
            return WhoGrowthCharts(tables.mapValues { (_, rows) -> rows.sortedBy { it.ageMonths } })
        }
    }
}
