package dev.kinash.smartscales.ui

import android.Manifest
import android.app.Application
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dev.kinash.smartscales.MainActivity
import dev.kinash.smartscales.core.ble.BluetoothLog
import dev.kinash.smartscales.core.data.repository.ScaleBindingRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.StringWriter
import javax.inject.Inject

/** Recording frames on the log screen, with advertisements from [FakeScanner]. */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
class BluetoothLogFlowTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    @Inject
    lateinit var scanner: FakeScanner

    @Inject
    lateinit var log: BluetoothLog

    @Inject
    lateinit var scales: ScaleBindingRepository

    @Before
    fun setUp() {
        hilt.inject()
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION)
    }

    @Test
    fun recordedScaleFrameIsShownAndExportedWithAlias() {
        openLog()

        compose.onNodeWithText("Record").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(isRoot()).fetchSemanticsNodes()
            scanner.listeners > 0
        }
        scanner.advertise(byteArrayOf(0xC0.toByte(), 1, 0x03, 0x20, 0x13, 0x88.toByte(), 0, 1, 0x21))

        waitFor(hasText("scale-1", substring = true))
        compose.onNodeWithText("1 frame").assertExists()

        val exported = StringWriter().also { runBlocking { log.export(it, emptyList()) } }.toString()
        assertTrue(exported.contains("\tscale-1\t-60\tOKOK_V2\tADV\t"))
        assertFalse(exported.contains("AA:BB:CC:00:11:22"))
    }

    @Test
    fun allDevicesModeEndsWhenLeavingTheLogScreen() {
        openLog()
        compose.onNodeWithText("Record").performClick()
        compose.onNodeWithText("All devices nearby").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { log.allDevices.value }

        compose.onNodeWithContentDescription("Back").performClick()
        waitFor(hasText("My scales"))
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(isRoot()).fetchSemanticsNodes()
            !log.allDevices.value
        }
        assertTrue(log.recording.value)
    }

    @Test
    fun weighingScreenShowsThatTheLogIsRecording() {
        runBlocking { scales.bind("AA:BB:CC:00:11:22", "AA:BB:CC:00:11:22", "OKOK_V2", null, 1) }
        log.setRecording(true)

        compose.onNodeWithText("Weigh", useUnmergedTree = true).performClick()
        waitFor(hasText("Bluetooth log is recording"))
        compose.onNodeWithText("Bluetooth log is recording").performClick()
        waitFor(hasText("All devices nearby"))
    }

    private fun openLog() {
        compose.onNodeWithText("Weigh", useUnmergedTree = true).performClick()
        waitFor(hasText("My scales"))
        compose.onNodeWithText("My scales").performClick()
        waitFor(hasText("Log"))
        compose.onNodeWithText("Log").performClick()
        waitFor(hasText("No frames recorded."))
    }

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntilAtLeastOneExists(matcher, timeoutMillis = 5_000)
}
