package dev.kinash.smartscales.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick

/** Opens the profile named [name] the way people do: the gear on the main screen, then the profile in the list. */
@OptIn(ExperimentalTestApi::class)
fun ComposeTestRule.openProfile(name: String) {
    onNodeWithContentDescription("Settings").performClick()
    waitUntilAtLeastOneExists(hasText("Profiles"), timeoutMillis = 5_000)
    onNodeWithText(name).performClick()
    waitUntilAtLeastOneExists(hasText("Date of birth"), timeoutMillis = 5_000)
}

/** From the settings, where a saved or deleted profile returns, back to the main screen. */
@OptIn(ExperimentalTestApi::class)
fun ComposeTestRule.backFromSettings() {
    waitUntilAtLeastOneExists(hasText("Profiles"), timeoutMillis = 5_000)
    onNodeWithContentDescription("Back").performClick()
    waitUntilAtLeastOneExists(hasContentDescription("Settings"), timeoutMillis = 5_000)
}
