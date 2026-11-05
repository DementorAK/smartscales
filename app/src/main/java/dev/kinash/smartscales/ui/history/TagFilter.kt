package dev.kinash.smartscales.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.kinash.smartscales.R
import dev.kinash.smartscales.core.data.Measurement
import dev.kinash.smartscales.core.data.normalizeTagName
import dev.kinash.smartscales.ui.common.FieldSpacing

/**
 * The tags of [history] to filter by, each once in the spelling of its newest use, alphabetically: those of
 * this profile only, so another person's tags do not crowd the row.
 */
fun profileTags(history: List<Measurement>): List<String> =
    history.flatMap { it.tags }.distinctBy(::normalizeTagName).sortedBy(::normalizeTagName)

/**
 * The tag filter as it applies to [tags]: a tag no weighing has any more (renamed, the weighing deleted or moved)
 * would hide everything without a chip to turn it off, so it is left out.
 */
fun activeFilter(filter: Set<String>, tags: List<String>): Set<String> = filter intersect tags.map(::normalizeTagName).toSet()

/** Whether [measurement] has all of [filter]'s tags; any measurement does for no filter. */
fun matches(measurement: Measurement, filter: Set<String>): Boolean =
    filter.isEmpty() || measurement.tags.map(::normalizeTagName).containsAll(filter)

/** What the chips do, then a chip for each of [tags], on for those in [filter]. */
@Composable
fun TagFilterRow(tags: List<String>, filter: Set<String>, onToggle: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            stringResource(R.string.tag_filter_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(FieldSpacing)) {
            tags.forEach { tag ->
                FilterChip(
                    selected = normalizeTagName(tag) in filter,
                    onClick = { onToggle(normalizeTagName(tag)) },
                    label = { Text(tag) },
                )
            }
        }
    }
}
