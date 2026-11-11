package dev.kinash.smartscales.export

import android.content.res.Resources
import dev.kinash.smartscales.R
import dev.kinash.smartscales.core.data.Profile
import dev.kinash.smartscales.core.formula.IndicatorUnit
import dev.kinash.smartscales.core.formula.text
import dev.kinash.smartscales.core.model.Impedance
import dev.kinash.smartscales.core.model.LengthUnit
import dev.kinash.smartscales.core.model.MeasurementSource
import dev.kinash.smartscales.core.model.Segment
import dev.kinash.smartscales.core.model.WeightUnit
import dev.kinash.smartscales.indicators.IndicatorHistory
import dev.kinash.smartscales.indicators.MeasuredIndicators
import dev.kinash.smartscales.ui.measurement.CircumferenceFields
import dev.kinash.smartscales.ui.measurement.circumferenceLabel
import dev.kinash.smartscales.ui.segments.title
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A profile's weighings as a table for Excel or Google Sheets (requirements §4.3): the raw data, then every
 * indicator the current formulas give with the profile's choice of variants, as on the screens. One row per
 * weighing, oldest first; a column only when some weighing has a value in it.
 *
 * Written for the phone's [locale]: where its decimal separator is a comma, the columns are separated by a
 * semicolon, as Excel expects there, and the headers are in its language. UTF-8 with a byte order mark, so that
 * Excel reads the letters right; the time is the one where the weighing was taken, `2026-11-10 07:30:15`.
 * Weights and lengths are in the profile's units (pounds for stones), named in the header.
 */
class MeasurementsCsv(private val resources: Resources, private val locale: Locale, private val language: String) {
    private val symbols = DecimalFormatSymbols.getInstance(locale)
    private val separator = if (symbols.decimalSeparator == ',') ';' else ','

    fun write(history: IndicatorHistory, profile: Profile): String {
        // Every weighing, one without indicators (dated before the birth date) too, with its raw data
        val rows = history.sortedBy { it.measurement.takenAt }
        val columns = columns(history, rows, profile)
        val out = StringBuilder("\uFEFF")
        out.appendRow(columns.map { inert(it.header) })
        rows.forEach { row -> out.appendRow(columns.map { if (it.text) inert(it.value(row)) else it.value(row) }) }
        return out.toString()
    }

    /** A column; a [text] one holds what people wrote, which must not become a formula (see [inert]). */
    private class Column(val header: String, val text: Boolean = false, val value: (MeasuredIndicators) -> String)

    private fun columns(history: IndicatorHistory, rows: List<MeasuredIndicators>, profile: Profile): List<Column> {
        val model = history.model
        val weightUnit = if (profile.weightUnit == WeightUnit.STONE) WeightUnit.POUND else profile.weightUnit
        val columns = mutableListOf<Column>()

        columns += Column(string(R.string.csv_date)) { DATE_TIME.format(it.measurement.takenAt.atZone(it.measurement.zone)) }
        columns += Column(withUnit(string(R.string.csv_weight), weightLabel(weightUnit))) {
            number(weightUnit.fromKilograms(it.measurement.weightKg), WEIGHT_DECIMALS, minDecimals = 1)
        }
        if (rows.any { it.measurement.heartRateBpm != null }) {
            columns += Column(withUnit(string(R.string.csv_heart_rate), string(R.string.csv_bpm))) { it.measurement.heartRateBpm?.toString().orEmpty() }
        }

        // Impedances as the scale sent them, before calibration: foot to foot first, then the segments
        val channels = rows.flatMap { row -> row.measurement.impedances.map { it.channel to it.frequencyIndex } }.distinct()
            .sortedWith(compareBy({ it.first }, { it.second }))
        channels.forEach { (channel, frequency) ->
            columns += Column(withUnit(impedanceName(channel, frequency), string(R.string.unit_ohm))) { row ->
                row.measurement.impedances.firstOrNull { it.channel == channel && it.frequencyIndex == frequency }?.let { number(it.ohms, 1) }.orEmpty()
            }
        }

        // The circumferences, then the fields of formula sets, then any kept without a set that knows them
        val fields = rows.flatMap { it.measurement.manualValues.keys }.toSet()
        val ordered = CircumferenceFields.filter { it in fields } + model.inputs.keys.filter { it in fields && it !in CircumferenceFields } +
            fields.filter { it !in CircumferenceFields && it !in model.inputs }.sorted()
        ordered.forEach { field ->
            val input = model.inputs[field]
            val unit = if (field in CircumferenceFields) IndicatorUnit.CM else input?.unit ?: IndicatorUnit.NONE
            val name = if (field in CircumferenceFields) string(circumferenceLabel(field)) else input?.name?.text(language) ?: field
            columns += Column(withUnit(name, unitLabel(unit, profile))) { row ->
                row.measurement.manualValues[field]?.let { number(inProfileUnits(it, unit, profile), 1) }.orEmpty()
            }
        }

        // A group's value by the profile's variant, then every indicator on its own; hidden ones are not shown. The
        // group's column is named after the group, not after a variant: its rows may come from different ones,
        // which the variants' own columns show
        model.groups.filterKeys { it !in model.hidden }.forEach { (group, variants) ->
            val values = rows.associateWith { it.results?.group(group) }
            if (values.values.none { it != null }) return@forEach
            val def = model.indicators.getValue(variants.first()).def
            val name = model.groupNames[group]?.text(language) ?: def.name.text(language).substringBefore(" (")
            columns += Column(withUnit(name, unitLabel(def.unit, profile))) { row ->
                values[row]?.let { (variant, value) -> indicatorNumber(value, model.indicators.getValue(variant).def.unit, model.indicators.getValue(variant).def.precision, profile) }.orEmpty()
            }
        }
        model.indicators.values.filter { it.id !in model.hidden && it.def.group !in model.hidden }.forEach { indicator ->
            val def = indicator.def
            val value = { row: MeasuredIndicators -> row.results?.value(indicator.id) ?: row.measurement.importedValues[indicator.id] }
            if (rows.none { value(it) != null }) return@forEach
            columns += Column(withUnit(def.name.text(language), unitLabel(def.unit, profile))) { row ->
                value(row)?.let { indicatorNumber(it, def.unit, def.precision, profile) }.orEmpty()
            }
        }

        if (rows.any { it.measurement.tags.isNotEmpty() }) {
            columns += Column(string(R.string.csv_tags), text = true) { it.measurement.tags.joinToString(", ") }
        }
        if (rows.any { !it.measurement.note.isNullOrBlank() }) {
            columns += Column(string(R.string.csv_note), text = true) { it.measurement.note.orEmpty() }
        }
        columns += Column(string(R.string.csv_source)) { string(sourceLabel(it.measurement.source)) }
        return columns
    }

    private fun impedanceName(channel: Int, frequency: Int): String {
        val segment = Segment.entries.firstOrNull { it.channel == channel }
        val name = when {
            channel == Impedance.FOOT_TO_FOOT -> string(R.string.csv_impedance)
            segment != null -> resources.getString(R.string.csv_impedance_of, string(segment.title()))
            else -> resources.getString(R.string.csv_impedance_of, channel.toString())
        }
        return if (frequency == 0) name else resources.getString(R.string.segment_frequency, name, frequency + 1)
    }

    private fun indicatorNumber(value: Double, unit: IndicatorUnit, precision: Int, profile: Profile): String =
        if (!value.isFinite()) "" else number(inProfileUnits(value, unit, profile), precision)

    private fun inProfileUnits(value: Double, unit: IndicatorUnit, profile: Profile): Double = when (unit) {
        IndicatorUnit.KG -> (if (profile.weightUnit == WeightUnit.STONE) WeightUnit.POUND else profile.weightUnit).fromKilograms(value)
        IndicatorUnit.CM -> profile.lengthUnit.fromCentimetres(value)
        else -> value
    }

    private fun unitLabel(unit: IndicatorUnit, profile: Profile): String? = when (unit) {
        IndicatorUnit.KG -> weightLabel(if (profile.weightUnit == WeightUnit.STONE) WeightUnit.POUND else profile.weightUnit)
        IndicatorUnit.CM -> string(if (profile.lengthUnit == LengthUnit.INCH) R.string.csv_in else R.string.unit_cm)
        IndicatorUnit.PERCENT -> string(R.string.unit_percent)
        IndicatorUnit.LITER -> string(R.string.unit_liter)
        IndicatorUnit.KCAL -> string(R.string.unit_kcal)
        IndicatorUnit.KG_M2 -> string(R.string.unit_kg_m2)
        IndicatorUnit.OHM -> string(R.string.unit_ohm)
        IndicatorUnit.NONE -> null
    }

    private fun weightLabel(unit: WeightUnit): String = string(if (unit == WeightUnit.POUND) R.string.csv_lb else R.string.unit_kg)

    private fun withUnit(name: String, unit: String?): String = if (unit == null) name else resources.getString(R.string.csv_column_unit, name, unit)

    /** [value] with up to [decimals] places, at least [minDecimals], rounded half up as on the screens, without grouping. */
    private fun number(value: Double, decimals: Int, minDecimals: Int = decimals): String {
        val rounded = BigDecimal(value.toString()).setScale(decimals, RoundingMode.HALF_UP)
        val pattern = if (decimals == 0) "0" else "0." + "0".repeat(minDecimals) + "#".repeat(decimals - minDecimals)
        val format = DecimalFormat(pattern, symbols)
        return format.format(rounded)
    }

    private fun string(id: Int) = resources.getString(id)

    private fun StringBuilder.appendRow(cells: List<String>) {
        cells.joinTo(this, separator.toString()) { escape(it) }
        append("\r\n")
    }

    /**
     * A cell of text that Excel or Google Sheets would take for a formula (OWASP "CSV injection"): starting with
     * `=`, `+`, `-`, `@`, a tab or a carriage return, it gets an apostrophe in front, which they show as text.
     * Headers come from formula sets other people may have written, notes and tags from the person. Numbers are
     * never passed here, so a negative one stays a number.
     */
    private fun inert(cell: String): String = if (cell.firstOrNull() in FORMULA_STARTS) "'$cell" else cell

    /** RFC 4180: a cell with the separator, a quote or a line break goes in quotes, its quotes doubled. */
    private fun escape(cell: String): String =
        if (cell.any { it == separator || it == '"' || it == '\n' || it == '\r' }) "\"" + cell.replace("\"", "\"\"") + "\"" else cell

    private companion object {
        /** Scales weigh to 0.05 kg: two places where needed, one otherwise (83.9, 83.95). */
        const val WEIGHT_DECIMALS = 2
        /** To the second: a weighing repeated on the scale within a minute is a row of its own. */
        val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        val FORMULA_STARTS = setOf('=', '+', '-', '@', '\t', '\r')

        fun sourceLabel(source: MeasurementSource): Int = when (source) {
            MeasurementSource.SCALE -> R.string.csv_source_scale
            MeasurementSource.MANUAL -> R.string.csv_source_manual
            MeasurementSource.IMPORT -> R.string.csv_source_import
        }
    }
}
