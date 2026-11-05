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
import dev.kinash.smartscales.core.model.Impedance
import dev.kinash.smartscales.indicators.ImpedanceStatus
import dev.kinash.smartscales.indicators.MeasuredIndicators
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
    /** Null when the card is shown only to say what it lacks, see [needsImpedance]. */
    val value: Double?,
    val unit: IndicatorUnit,
    val precision: Int,
    val zone: FormulaModel.Zone?,
    /** Position on the CDC growth chart, for children. */
    val percentile: Double? = null,
    /** Without a value: the indicator has one with an impedance, and this is why the weighing has none. */
    val needsImpedance: ImpedanceStatus? = null,
    /** The weight card's impedance line. */
    val impedance: ImpedanceReading? = null,
)

/** The impedance of a weighing as the formulas took it: [ohms] with the scale's calibration, or as sent for a placeholder. */
data class ImpedanceReading(val ohms: Double?, val status: ImpedanceStatus)

/** The first card of an adult: the weight, and the impedance the body composition comes from. */
const val WEIGHT_CARD = "weight"

/** The weight card of [measured]: its impedance is the one in the formulas, or the placeholder the scale sent. */
fun weightCard(measured: MeasuredIndicators): IndicatorCard {
    val measurement = measured.measurement
    val ohms = when (measured.impedance) {
        ImpedanceStatus.MEASURED -> measured.inputs?.get("impedance") as? Double
        ImpedanceStatus.PLACEHOLDER ->
            measurement.impedances.firstOrNull { it.channel == Impedance.FOOT_TO_FOOT && it.frequencyIndex == 0 }?.ohms
        ImpedanceStatus.MISSING -> null
    }
    return IndicatorCard(
        WEIGHT_CARD, emptyMap(), null, measurement.weightKg, IndicatorUnit.KG, 2, null,
        impedance = ImpedanceReading(ohms, measured.impedance),
    )
}

/**
 * The cards of a profile that has not chosen its own ([Profile.cards]): an indicator id, or a group id whose
 * value comes from the profile's variant.
 */
val DEFAULT_CARDS = listOf("bmi", "bf", "fat_mass", "fat_free_mass", "tbw_percent", "smm_janssen", "bmr", "whtr")

/** The cards [profile] shows after the weight card, in its order. */
fun cardsOf(profile: Profile): List<String> = profile.cards ?: DEFAULT_CARDS

/**
 * A card a profile can put on the main screen: a group, whose value comes from the variant the profile chose
 * ([isGroup], for groups that have a choice), or one indicator, such as body fat by one equation next to the
 * group's value.
 */
data class CardChoice(val id: String, val title: LocalizedText, val isGroup: Boolean)

/**
 * Everything that can be a card, in the formula sets' order, the built-in set first and the person's sets after
 * it: each group before its variants, and indicators that are not hidden. A group's only variant is not offered
 * apart from the group, since both give the same value.
 */
fun cardChoices(model: FormulaModel): List<CardChoice> = buildList {
    val groupsSeen = mutableSetOf<String>()
    model.indicators.values.forEach { indicator ->
        val def = indicator.def
        if (def.id in model.hidden) return@forEach
        val group = def.group?.takeIf { it in model.groups && it !in model.hidden }
        if (group != null && groupsSeen.add(group)) {
            val variants = model.groups.getValue(group)
            add(CardChoice(group, model.groupName(group, variants.first()), isGroup = variants.size > 1))
        }
        if (group == null || model.groups.getValue(group).size > 1) add(CardChoice(def.id, def.name, isGroup = false))
    }
}

private const val NO_VALUE = "—"

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
val FOLLOWS_GROUP = mapOf("fat_mass" to "bf", "fat_free_mass" to "bf", "tbw_percent" to "tbw")

/**
 * The [cards] with a value for this measurement, in their order; hidden indicators and groups, and ids no formula
 * set has any more, are left out. A card that has a value only
 * with an impedance ([withImpedance], the results with one) stays, without a value, to say so: the person sees
 * that water or muscle needs it instead of wondering where the card went.
 */
fun indicatorCards(
    model: FormulaModel,
    results: FormulaModel.Results,
    withImpedance: FormulaModel.Results? = null,
    impedance: ImpedanceStatus = ImpedanceStatus.MEASURED,
    cards: List<String> = DEFAULT_CARDS,
): List<IndicatorCard> =
    cards.filter { it !in model.hidden }.mapNotNull { id ->
        if (id in model.groups) {
            results.group(id)?.let { (variant, value) ->
                val def = model.indicators.getValue(variant).def
                return@mapNotNull IndicatorCard(id, model.groupName(id, variant), def.name, value, def.unit, def.precision, results.zone(variant))
            }
            val (variant, _) = withImpedance?.group(id) ?: return@mapNotNull null
            val def = model.indicators.getValue(variant).def
            IndicatorCard(id, model.groupName(id, variant), null, null, def.unit, def.precision, null, needsImpedance = impedance)
        } else {
            val def = model.indicators[id]?.def ?: return@mapNotNull null
            val value = results.value(id)
            if (value == null) {
                if (withImpedance?.value(id) == null) return@mapNotNull null
                return@mapNotNull IndicatorCard(id, def.name, null, null, def.unit, def.precision, null, needsImpedance = impedance)
            }
            val method = FOLLOWS_GROUP[id]?.let { results.group(it) }?.let { (variant, _) -> model.indicators.getValue(variant).def.name }
            IndicatorCard(id, def.name, method, value, def.unit, def.precision, results.zone(id))
        }
    }

@Composable
fun IndicatorCards(cards: List<IndicatorCard>, profile: Profile, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    // Two equal columns, and both cards of a row as tall as the taller one, so they line up whatever their texts
    Column(modifier.padding(ScreenPadding), verticalArrangement = Arrangement.spacedBy(FieldSpacing)) {
        cards.chunked(2).forEach { row ->
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(FieldSpacing)) {
                row.forEach { card -> IndicatorCardView(card, profile, { onOpen(card.id) }, Modifier.weight(1f).fillMaxHeight()) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun IndicatorCardView(card: IndicatorCard, profile: Profile, onClick: () -> Unit, modifier: Modifier) {
    val language = currentLanguage()
    // A card opens its chart
    OutlinedCard(onClick, modifier) {
        Column(Modifier.padding(ScreenPadding), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(cardTitle(card, language), style = MaterialTheme.typography.labelLarge)
            Text(
                when {
                    card.value == null -> NO_VALUE
                    // The weight as in the history list, with the scale's resolution
                    card.id == WEIGHT_CARD -> weightText(card.value, profile.weightUnit)
                    else -> indicatorValueText(card.value, card.unit, card.precision, profile)
                },
                style = MaterialTheme.typography.titleLarge,
            )
            card.impedance?.let { ImpedanceLine(it) }
            card.needsImpedance?.let {
                Text(
                    stringResource(
                        if (it == ImpedanceStatus.PLACEHOLDER) R.string.card_needs_impedance_placeholder else R.string.card_needs_impedance_missing,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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

/** The impedance under the weight; a placeholder is said to be one, since the cards below are then without it. */
@Composable
private fun ImpedanceLine(reading: ImpedanceReading) {
    val ohms = reading.ohms?.let { formatNumber(it, 0, 1) }
    when {
        reading.status == ImpedanceStatus.MEASURED && ohms != null ->
            Text(stringResource(R.string.card_impedance, ohms), style = MaterialTheme.typography.bodyMedium)
        reading.status == ImpedanceStatus.PLACEHOLDER && ohms != null -> Text(
            stringResource(R.string.card_impedance_placeholder, ohms),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        else -> Text(
            stringResource(R.string.card_impedance_none),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Weight and children's height come from the app, the other titles from the formula set. */
@Composable
private fun cardTitle(card: IndicatorCard, language: String): String = when (card.id) {
    WEIGHT_CARD, CHILD_WEIGHT_CARD -> stringResource(R.string.card_weight)
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

fun ZoneLevel.title(): Int = when (this) {
    ZoneLevel.VERY_LOW -> R.string.zone_very_low
    ZoneLevel.LOW -> R.string.zone_low
    ZoneLevel.NORMAL -> R.string.zone_normal
    ZoneLevel.ELEVATED -> R.string.zone_elevated
    ZoneLevel.HIGH -> R.string.zone_high
    ZoneLevel.VERY_HIGH -> R.string.zone_very_high
}

/** Blue below the norm, green in it, then amber, orange and red; readable on light and dark backgrounds. */
fun ZoneLevel.color(): Color = when (this) {
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
