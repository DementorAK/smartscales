package dev.kinash.smartscales.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import dev.kinash.smartscales.R
import dev.kinash.smartscales.core.data.Measurement
import dev.kinash.smartscales.core.model.WeightUnit
import dev.kinash.smartscales.ui.common.weightText
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** One line of the history: weight, date and time, tags and note. */
@Composable
fun MeasurementRow(
    measurement: Measurement,
    weightUnit: WeightUnit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    val details = listOfNotNull(
        measurement.tags.takeIf { it.isNotEmpty() }?.joinToString(),
        measurement.note,
    ).joinToString(" · ")
    ListItem(
        headlineContent = { Text(weightText(measurement.weightKg, weightUnit)) },
        overlineContent = { Text(dateTimeText(measurement)) },
        supportingContent = if (details.isEmpty()) {
            null
        } else {
            { Text(details, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        },
        trailingContent = trailing,
        modifier = modifier.clickable(onClick = onClick),
    )
}

/** Local date and time where the measurement was taken; the zone is shown when it differs from the phone's. */
@Composable
private fun dateTimeText(measurement: Measurement): String {
    val local = measurement.takenAt.atZone(measurement.zone)
    val text = local.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))
    return if (measurement.zone.normalized() == ZoneId.systemDefault().normalized()) {
        text
    } else {
        stringResource(R.string.history_date_with_zone, text, measurement.zone.id)
    }
}
