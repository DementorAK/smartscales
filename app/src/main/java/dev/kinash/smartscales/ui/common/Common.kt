package dev.kinash.smartscales.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kinash.smartscales.R
import dev.kinash.smartscales.core.data.Profile

val ScreenPadding = 16.dp
val FieldSpacing = 8.dp

/** Profile colours to choose from, as ARGB. */
val ProfileColors: List<Int> = listOf(
    0xFF3F51B5, 0xFF009688, 0xFFE91E63, 0xFFFF9800, 0xFF8BC34A, 0xFF9C27B0, 0xFF795548, 0xFF607D8B,
).map { it.toInt() }

/** Circle in the profile colour with its avatar text or the first letter of the name. */
@Composable
fun ProfileAvatar(profile: Profile, modifier: Modifier = Modifier, size: Dp = 32.dp) {
    ProfileAvatar(profile.name, profile.avatar, profile.color, modifier, size)
}

@Composable
fun ProfileAvatar(name: String, avatar: String?, color: Int, modifier: Modifier = Modifier, size: Dp = 32.dp) {
    val background = Color(color)
    Box(
        modifier
            .size(size)
            .background(background, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = avatar?.takeIf { it.isNotBlank() } ?: name.trim().take(1).uppercase(),
            color = if (background.luminance() > 0.5f) Color.Black else Color.White,
            style = if (size >= 48.dp) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleSmall,
        )
    }
}

/**
 * Profiles to choose from in a dialog, each as an outlined card that reacts to a tap. Profiles for which
 * [isEnabled] is false are shown dimmed with the reason: the person was born after the measurement.
 */
@Composable
fun ProfileChoiceList(
    profiles: List<Profile>,
    onSelect: (Profile) -> Unit,
    modifier: Modifier = Modifier,
    isEnabled: (Profile) -> Boolean = { true },
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(FieldSpacing)) {
        profiles.forEach { profile ->
            val enabled = isEnabled(profile)
            OutlinedCard(onClick = { onSelect(profile) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                ListItem(
                    headlineContent = { Text(profile.name) },
                    supportingContent = if (enabled) null else { { Text(stringResource(R.string.profile_born_later)) } },
                    leadingContent = { ProfileAvatar(profile) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = if (enabled) Modifier else Modifier.alpha(DisabledAlpha),
                )
            }
        }
    }
}

private const val DisabledAlpha = 0.38f

@Composable
fun BackButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
    }
}

/** Confirmation for an action that cannot be undone. */
@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmLabel, color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}
