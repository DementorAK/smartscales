package dev.kinash.smartscales.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dev.kinash.smartscales.MainActivity
import dev.kinash.smartscales.core.data.repository.ScaleBindingRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import javax.inject.Inject

/** Importing the V2 key from a file on the scale settings screen; the system file picker is answered here. */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
class ScaleKeyImportFlowTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    @Inject
    lateinit var scales: ScaleBindingRepository

    private var scaleId = 0L

    @Before
    fun setUp() {
        hilt.inject()
        scaleId = runBlocking { scales.bind("AA:BB:CC:00:11:22", "AA:BB:CC:00:11:22", "OKOK_V2", null, 1) }
    }

    @Test
    fun keyForThisScaleIsImportedAndSaved() {
        openSettings()
        pickFile("mac,key\n11:22:33:44:55:66,0F0F0F0F\naa:bb:cc:00:11:22,1a2b3c4d\n")

        waitFor(hasText("Key read from the file. Save to use it."))
        compose.onNodeWithText("1A2B3C4D").assertExists()
        // Nothing changes until Save
        assertNull(runBlocking { scales.get(scaleId)!!.v2Key })

        compose.onNodeWithText("Save").performClick()
        waitFor(hasText("My scales"))
        assertEquals("1A2B3C4D", runBlocking { scales.get(scaleId)!!.v2Key })
    }

    @Test
    fun fileForOtherScalesChangesNothing() {
        openSettings()
        pickFile("11:22:33:44:55:66,0F0F0F0F\n")

        waitFor(hasText("The file has keys for other scales only."))
        compose.onNodeWithText("0F0F0F0F").assertDoesNotExist()
    }

    private fun openSettings() {
        compose.onNodeWithText("Weigh", useUnmergedTree = true).performClick()
        waitFor(hasText("My scales"))
        compose.onNodeWithText("My scales").performClick()
        waitFor(hasText("Chipsea OKOK V2 (C0)"))
        compose.onNodeWithText("Chipsea OKOK V2 (C0)").performClick()
        waitFor(hasText("Import key from file"))
    }

    /** Taps the import button and answers the picker with a file holding [text]. */
    private fun pickFile(text: String) {
        compose.onNodeWithText("Import key from file").performScrollTo().performClick()
        compose.waitForIdle()
        val uri = Uri.parse("content://test.files/keys.txt")
        val activity = shadowOf(compose.activity)
        shadowOf(compose.activity.contentResolver).registerInputStream(uri, text.byteInputStream())
        val request = activity.nextStartedActivityForResult
        activity.receiveResult(request.intent, Activity.RESULT_OK, Intent().setData(uri))
    }

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntilAtLeastOneExists(matcher, timeoutMillis = 5_000)
}
