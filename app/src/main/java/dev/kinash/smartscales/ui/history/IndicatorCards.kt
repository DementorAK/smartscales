package dev.kinash.smartscales.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.kinash.smartscales.R
import dev.kinash.smartscales.core.data.Measurement
import dev.kinash.smartscales.core.data.Profile
import dev.kinash.smartscales.core.formula.ChildGrowth
import dev.kinash.smartscales.core.formula.FormulaModel
import dev.kinash.smartscales.core.formula.IndicatorUnit
import dev.kinash.smartscales.core.formula.LocalizedText
import dev.kinash.smartscales.core.formula.ZoneLevel
import dev.kinash.smartscales.core.formula.text
import dev.kinash.smartscales.core.model.WeightUnit
import dev.kinash.smartscales.ui.common.FieldSpacing
import dev.kinash.smartscales.ui.common.ScreenPadding
import dev.kinash.smartscales.ui.common.formatNumber
import dev.kinash.smartscales.ui.common.lengthText
import dev.kinash.smartscales.ui.common.weightText

/** One value on the main screen: an indicator, or a group with the variant that gave its value. */
data class IndicatorCard(
    val id: String,
    val title: LocalizedText,
    /** The method behind the value, for a group or an indicator that follows one ("Body fat (Gallagher 2000, from BMI)"). */
    val variant: LocalizedText?,
    val value: Double,
    val unit: IndicatorUnit,
    val precision: Int,
    val zone: FormulaModel.Zone?,
    /** Position on the CDC growth chart, for children. */
    val percentile: Double? = null,
)

/**
 * The cards every profile sees until profiles choose their own in 0.6: an indicator id, or a group id whose
 * value comes from the profile's variant.
 */
val DEFAULT_CARDS = listOf("bmi", "bf", "fat_mass", "fat_free_mass", "tbw_percent", "smm_janssen", "bmr", "whtr")

/** Card ids for children's weight and height, which formula sets do not define. */
const val CHILD_WEIGHT_CARD = "child_weight"
const val CHILD_HEIGHT_CARD = "child_height"

/**
 * A child's cards (requirements §3): BMI with its CDC percentile and weight status from 2 years, weight and height
 * with their percentiles; body composition is not calculated for children.
 */
fun childCards(model: FormulaModel, results: FormulaModel.Results, growth: ChildGrowth, measurement: Measurement): List<IndicatorCard> {
    val person = measurement.person ?: return emptyList()
    val bmi = model.indicators["bmi"]?.def?.takeIf { "bmi" !in model.hidden }
    return listOfNotNull(
        bmi?.let { def ->
            results.value("bmi")?.let { value ->
                val zone = growth.bmiLevel?.let { FormulaModel.Zone(it, null, approximate = false) }
                IndicatorCard("bmi", def.name, null, value, def.unit, def.precision, zone, growth.bmiPercentile)
            }
        },
        IndicatorCard(CHILD_WEIGHT_CARD, emptyMap(), null, measurement.weightKg, IndicatorUnit.KG, 1, null, growth.weightPercentile),
        IndicatorCard(CHILD_HEIGHT_CARD, emptyMap(), null, person.heightCm, IndicatorUnit.CM, 1, null, growth.heightPercentile),
    )
}

/** Indicators whose value follows a group's variant: their card names the method, as the group's card does. */
private val FOLLOWS_GROUP = mapOf("fat_mass" to "bf", "fat_free_mass" to "bf")

/** Cards with a value for this measurement; hidden indicators and groups are left out. */
fun indicatorCards(model: FormulaModel, results: FormulaModel.Results): List<IndicatorCard> =
    DEFAULT_CARDS.filter { it !in model.hidden }.mapNotNull { id ->
        if (id in model.groups) {
            val (variant, value) = results.group(id) ?: return@mapNotNull null
            val def = model.indicators.getValue(variant).def
            IndicatorCard(id, model.groupName(id, variant), def.name, value, def.unit, def.precision, results.zone(variant))
        } else {
            val def = model.indicators[id]?.def ?: return@mapNotNull null
            val value = results.value(id) ?: return@mapNotNull null
            val method = FOLLOWS_GROUP[id]?.let { results.group(it) }?.let { (variant, _) -> model.indicators.getValue(variant).def.name }
            IndicatorCard(id, def.name, method, value, def.unit, def.precision, results.zone(id))
        }
    }

@Composable
fun IndicatorCards(cards: List<IndicatorCard>, profile: Profile, modifier: Modifier = Modifier) {
    // Two equal columns, and both cards of a row as tall as the taller one, so they line up whatever their texts
    Column(modifier.padding(ScreenPadding), verticalArrangement = Arrangement.spacedBy(FieldSpacing)) {
        cards.chunked(2).forEach { row ->
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(FieldSpacing)) {
                row.forEach { IndicatorCardView(it, profile, Modifier.weight(1f).fillMaxHeight()) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun IndicatorCardView(card: IndicatorCard, profile: Profile, modifier: Modifier) {
    val language = currentLanguage()
    OutlinedCard(modifier) {
        Column(Modifier.padding(ScreenPadding), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(cardTitle(card, language), style = MaterialTheme.typography.labelLarge)
            Text(indicatorValueText(card.value, card.unit, card.precision, profile), style = MaterialTheme.typography.titleLarge)
            card.zone?.let { ZoneLine(it, language) }
            card.percentile?.let {
                Text(stringResource(R.string.card_percentile, percentileText(it)), style = MaterialTheme.typography.bodyMedium)
            }
            card.variant?.let {
                Text(
                    it.text(language),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Children's weight and height come from the app, the other titles from the formula set. */
@Composable
private fun cardTitle(card: IndicatorCard, language: String): String = when (card.id) {
    CHILD_WEIGHT_CARD -> stringResource(R.string.card_weight)
    CHILD_HEIGHT_CARD -> stringResource(R.string.card_height)
    else -> card.title.text(language)
}

/** Whole percentiles; the ends of the chart as "below 1" and "above 99", where a number would claim too much. */
@Composable
private fun percentileText(percentile: Double): String = when {
    percentile < 1 -> stringResource(R.string.card_percentile_below, "1")
    percentile > 99 -> stringResource(R.string.card_percentile_above, "99")
    else -> formatNumber(percentile, 0, 0)
}

/** The zone in words as well as colour, so it reads without telling colours apart. */
@Composable
private fun ZoneLine(zone: FormulaModel.Zone, language: String) {
    val name = zone.label?.text(language) ?: stringResource(zone.level.title())
    val text = if (zone.approximate) stringResource(R.string.zone_approximate, name) else name
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier
                .size(10.dp)
                .background(zone.level.color(), CircleShape),
        )
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** A value in its unit; kilograms and centimetres in the profile's units. */
@Composable
fun indicatorValueText(value: Double, unit: IndicatorUnit, precision: Int, profile: Profile): String {
    val number = formatNumber(value, precision, precision)
    return when (unit) {
        // The indicator's precision in kilograms and pounds; stones keep their own format
        IndicatorUnit.KG -> when (profile.weightUnit) {
            WeightUnit.KILOGRAM -> stringResource(R.string.unit_kg_value, number)
            WeightUnit.POUND -> stringResource(
                R.string.unit_lb_value,
                formatNumber(WeightUnit.POUND.fromKilograms(value), precision, precision),
            )
            WeightUnit.STONE -> weightText(value, profile.weightUnit)
        }
        IndicatorUnit.CM -> lengthText(value, profile.lengthUnit)
        IndicatorUnit.PERCENT -> stringResource(R.string.unit_percent_value, number)
        IndicatorUnit.LITER -> stringResource(R.string.unit_liter_value, number)
        IndicatorUnit.KCAL -> stringResource(R.string.unit_kcal_value, number)
        IndicatorUnit.KG_M2 -> stringResource(R.string.unit_kg_m2_value, number)
        IndicatorUnit.NONE -> number
    }
}

private fun ZoneLevel.title(): Int = when (this) {
    ZoneLevel.VERY_LOW -> R.string.zone_very_low
    ZoneLevel.LOW -> R.string.zone_low
    ZoneLevel.NORMAL -> R.string.zone_normal
    ZoneLevel.ELEVATED -> R.string.zone_elevated
    ZoneLevel.HIGH -> R.string.zone_high
    ZoneLevel.VERY_HIGH -> R.string.zone_very_high
}

/** Blue below the norm, green in it, then amber, orange and red; readable on light and dark backgrounds. */
private fun ZoneLevel.color(): Color = when (this) {
    ZoneLevel.VERY_LOW -> Color(0xFF1E5AA8)
    ZoneLevel.LOW -> Color(0xFF4A90D9)
    ZoneLevel.NORMAL -> Color(0xFF2E9E4F)
    ZoneLevel.ELEVATED -> Color(0xFFE0A100)
    ZoneLevel.HIGH -> Color(0xFFE8710A)
    ZoneLevel.VERY_HIGH -> Color(0xFFD32F2F)
}

/** The language the app is shown in, for texts of formula sets. */
@Composable
private fun currentLanguage(): String = LocalConfiguration.current.locales[0].language
