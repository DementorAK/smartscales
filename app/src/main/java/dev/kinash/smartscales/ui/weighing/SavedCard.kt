package dev.kinash.smartscales.ui.weighing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.kinash.smartscales.R
import dev.kinash.smartscales.core.data.Profile
import dev.kinash.smartscales.core.formula.text
import dev.kinash.smartscales.core.model.WeightUnit
import dev.kinash.smartscales.ui.common.FieldSpacing
import dev.kinash.smartscales.ui.common.ScreenPadding
import dev.kinash.smartscales.ui.common.formatNumber
import dev.kinash.smartscales.ui.common.scaleWeightText
import dev.kinash.smartscales.ui.history.WEIGHT_CARD
import dev.kinash.smartscales.ui.history.goalValueText
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.abs

/**
 * A weighing saved for a profile, until closed or the next one comes: the weight and its change, goals it
 * reached and a weight far from the usual one, with "Not me" (requirements §6.2).
 */
@Composable
fun SavedCard(saved: SavedWeighing, onNotMe: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val profile = saved.profile ?: return
    val notes = saved.notes
    // TalkBack tells of the card and of what is added to it, as it told of the message the card replaced
    Card(modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
        Column(Modifier.padding(ScreenPadding), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.weighing_saved, profile.name), style = MaterialTheme.typography.titleMedium)
            Text(scaleWeightText(saved.weight), style = MaterialTheme.typography.headlineSmall)
            notes?.change?.let { change ->
                val since = notes.since
                Text(
                    if (since == null) {
                        stringResource(R.string.weighing_change, changeText(change, profile))
                    } else {
                        stringResource(R.string.weighing_change_since, changeText(change, profile), since.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)))
                    },
                )
            }
            val language = LocalConfiguration.current.locales[0].language
            notes?.reached?.forEach { goal ->
                val title = if (goal.id == WEIGHT_CARD) stringResource(R.string.card_weight) else goal.title.text(language)
                Text(
                    stringResource(R.string.weighing_goal_reached, title, goalValueText(goal.target, goal.unit, goal.precision, profile)),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            notes?.deviation?.let { deviation ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(
                        pluralStringResource(
                            if (deviation > 0) R.plurals.weighing_deviation_up else R.plurals.weighing_deviation_down,
                            USUAL_DAYS.toInt(),
                            changeText(abs(deviation), profile, signed = false),
                            USUAL_DAYS.toInt(),
                            profile.name,
                        ),
                        Modifier.padding(FieldSpacing),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(FieldSpacing)) {
                OutlinedButton(onClick = onNotMe) { Text(stringResource(R.string.weighing_not_me)) }
                TextButton(onClick = onClose) { Text(stringResource(R.string.weighing_saved_close)) }
            }
        }
    }
}

/** Kilograms in the profile's unit to a tenth, with the sign; stones as pounds, which a change is told in. */
@Composable
private fun changeText(kg: Double, profile: Profile, signed: Boolean = true): String {
    val (value, unit) = when (profile.weightUnit) {
        WeightUnit.KILOGRAM -> kg to R.string.unit_kg_value
        else -> WeightUnit.POUND.fromKilograms(kg) to R.string.unit_lb_value
    }
    val number = formatNumber(abs(value), 1, 1)
    val sign = when {
        !signed || number == formatNumber(0.0, 1, 1) -> ""
        value > 0 -> "+"
        else -> "−"
    }
    return stringResource(unit, sign + number)
}
