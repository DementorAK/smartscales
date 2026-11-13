package dev.kinash.smartscales.ui

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.os.Looper
import android.os.PowerManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dev.kinash.smartscales.MainActivity
import dev.kinash.smartscales.background.WeighingService
import dev.kinash.smartscales.core.ble.ScanFilters
import dev.kinash.smartscales.core.data.Measurement
import dev.kinash.smartscales.core.data.Profile
import dev.kinash.smartscales.core.data.repository.MeasurementRepository
import dev.kinash.smartscales.core.data.repository.ProfileRepository
import dev.kinash.smartscales.core.data.repository.ScaleBindingRepository
import dev.kinash.smartscales.core.data.repository.SettingsRepository
import dev.kinash.smartscales.core.model.MeasurementSource
import dev.kinash.smartscales.core.model.Sex
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/** The background mode: turned on in the settings, and its service saving and asking with the app closed. */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
class BackgroundFlowTest {
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    @Inject
    lateinit var scanner: FakeScanner

    @Inject
    lateinit var profiles: ProfileRepository

    @Inject
    lateinit var measurements: MeasurementRepository

    @Inject
    lateinit var scales: ScaleBindingRepository

    @Inject
    lateinit var settings: SettingsRepository

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val notifications get() = shadowOf(app.getSystemService(NotificationManager::class.java))

    @Before
    fun setUp() {
        hilt.inject()
        shadowOf(app).grantPermissions(
            Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS,
        )
    }

    @Test
    fun turningItOnStartsTheService() {
        compose.waitUntilAtLeastOneExists(hasContentDescription("Settings"), timeoutMillis = 5_000)
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithTag("background_mode").performScrollTo().performClick()
        // The answer of the permissions and the saving come on the main thread, which reading the tree lets run
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(isRoot()).fetchSemanticsNodes()
            runBlocking { settings.backgroundMode.first() } &&
                shadowOf(app).peekNextStartedService()?.component == ComponentName(app, WeighingService::class.java)
        }
    }

    @Test
    fun theServiceSavesAndAsksWhoseItIsInTheShade() {
        runBlocking { scales.bind("AA:BB:CC:00:11:22", "AA:BB:CC:00:11:22", "OKOK_V2", null, 1) }
        val dmitry = addProfile("Dmitry", lastWeightKg = 80.0)
        addProfile("Pavel", lastWeightKg = 80.5)
        runBlocking { settings.setBackgroundMode(true) }
        val service = Robolectric.buildService(WeighingService::class.java).create().startCommand(0, 1)

        // The screen on: everything, saving the battery, so that a scale with a new address is found again
        waitFor { scanner.listeners > 0 }
        assertEquals(null, scanner.filters)
        assertTrue(scanner.savingBattery)
        // The screen off: only the paired scale, at its address; a C0 scale has no fixed company id
        shadowOf(app.getSystemService(PowerManager::class.java)).setIsInteractive(false)
        app.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        waitFor { scanner.filters != null && scanner.listeners > 0 }
        assertEquals(ScanFilters(setOf("AA:BB:CC:00:11:22"), emptySet()), scanner.filters)
        scanner.advertise(v2Frame(seq = 1, weightRaw = 798, locked = false))
        scanner.advertise(v2Frame(seq = 1, weightRaw = 803, locked = true))

        // Both are close: asked in the shade, with a button each
        waitFor { whose() != null }
        val asked = whose()!!
        assertEquals("Whose weighing, 80.3 kg?", asked.extras.getCharSequence("android.title").toString())
        // The closest first
        assertEquals(listOf("Pavel", "Dmitry"), asked.actions.map { it.title.toString() })
        asked.actions[1].actionIntent.send()
        waitFor { runBlocking { measurements.observeHistory(dmitry).first() }.size == 2 }
        waitFor { whose() == null }
        assertTrue(runBlocking { measurements.observeInbox().first() }.isEmpty())
        // The service's own notification tells the last weighing
        waitFor { listening()?.contains("Dmitry · 80.3 kg") == true }
        service.destroy()
    }

    private fun whose() = notifications.activeNotifications.firstOrNull { it.tag == WeighingService.TAG_WHOSE }?.notification

    private fun listening() = notifications.activeNotifications.firstOrNull { it.id == WeighingService.LISTENING_ID && it.tag == null }
        ?.notification?.extras?.getCharSequence("android.text")?.toString()

    /** The service works on the main thread, which the test runs on: it runs while the test waits. */
    private fun waitFor(condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < until) { "Timed out" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    private fun addProfile(name: String, lastWeightKg: Double): Long = runBlocking {
        val id = profiles.add(
            Profile(
                name = name, sex = Sex.MALE, birthDate = LocalDate.parse("1990-10-28"), heightCm = 180.0,
                color = 0xFF3F51B5.toInt(), createdAt = Instant.parse("2026-01-01T00:00:00Z"),
            ),
        )
        measurements.add(
            Measurement(
                profileId = id, takenAt = Instant.now().minus(Duration.ofDays(2)), zone = ZoneId.systemDefault(),
                source = MeasurementSource.MANUAL, weightKg = lastWeightKg, person = profiles.get(id)!!.snapshot(),
            ),
        )
        id
    }

    /** Plain `C0` frame: kilograms with one decimal, 500.0 Ω, a scale that measures impedance. */
    private fun v2Frame(seq: Int, weightRaw: Int, locked: Boolean): ByteArray = byteArrayOf(
        0xC0.toByte(), seq.toByte(),
        (weightRaw shr 8).toByte(), weightRaw.toByte(),
        0x13, 0x88.toByte(),
        0x00, 0x01,
        (0x20 or if (locked) 0x01 else 0x00).toByte(),
    )
}
