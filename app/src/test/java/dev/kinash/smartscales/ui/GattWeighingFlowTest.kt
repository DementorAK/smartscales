package dev.kinash.smartscales.ui

import android.Manifest
import android.app.Application
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dev.kinash.smartscales.MainActivity
import dev.kinash.smartscales.core.ble.GattNotification
import dev.kinash.smartscales.core.data.Measurement
import dev.kinash.smartscales.core.data.Profile
import dev.kinash.smartscales.core.data.ScaleDisplayUnit
import dev.kinash.smartscales.core.data.repository.MeasurementRepository
import dev.kinash.smartscales.core.data.repository.ProfileRepository
import dev.kinash.smartscales.core.data.repository.ScaleBindingRepository
import dev.kinash.smartscales.core.model.Impedance
import dev.kinash.smartscales.core.model.MeasurementSource
import dev.kinash.smartscales.core.model.Sex
import dev.kinash.smartscales.core.protocol.ChipseaGatt
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject

/**
 * Weighing with an OKOKCloud V4 scale over a fake GATT connection. The frames are synthetic, built from the
 * protocol description: no V4 scale has been at hand.
 */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
class GattWeighingFlowTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    @Inject
    lateinit var scanner: FakeScanner

    @Inject
    lateinit var gatt: FakeGattTransport

    @Inject
    lateinit var profiles: ProfileRepository

    @Inject
    lateinit var measurements: MeasurementRepository

    @Inject
    lateinit var scales: ScaleBindingRepository

    private val address = "AA:BB:CC:00:11:22"
    private val now = Instant.now().truncatedTo(ChronoUnit.SECONDS)

    @Before
    fun setUp() {
        hilt.inject()
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION)
    }

    @Test
    fun resultGoesToTheMatchingProfileAndHistoryToTheInbox() {
        val scaleId = runBlocking { scales.bind(address, address, "OKOK_CLOUD_V4", null, 1) }
        val dmitry = addProfile("Dmitry", lastWeightKg = 80.0)
        val takenAt = now.minusSeconds(30)
        val kept = now.minus(1, ChronoUnit.DAYS)
        gatt.frames = listOf(
            GattNotification(ChipseaGatt.BODY_COMPOSITION_HISTORY, c5(kept, weightRaw = 790, ohmsX10 = 0, heartRate = 0)),
            GattNotification(ChipseaGatt.CHIPSEA_RX, c5(now, weightRaw = 801, ohmsX10 = 0, heartRate = 0)),
            GattNotification(ChipseaGatt.BODY_COMPOSITION_MEASUREMENT, c5(takenAt, weightRaw = 803, ohmsX10 = 5123, heartRate = 72)),
        )
        openWeighing()
        waitForListener()

        scanner.advertise(advertisement(weightRaw = 801), address)

        waitFor(hasText("Saved for Dmitry"))
        val saved = runBlocking { measurements.observeHistory(dmitry).first() }.first()
        assertEquals(MeasurementSource.SCALE, saved.source)
        assertEquals(80.3, saved.weightKg, 1e-9)
        assertEquals(listOf(Impedance(Impedance.FOOT_TO_FOOT, 512.3)), saved.impedances)
        assertEquals(72, saved.heartRateBpm)
        // The scale's own time of the weighing
        assertEquals(takenAt, saved.takenAt)
        assertEquals(scaleId, saved.scaleBindingId)

        // The record the scale kept names no person: it waits in the inbox
        val inbox = runBlocking { measurements.observeInbox().first() }.single()
        assertEquals(79.0, inbox.weightKg, 1e-9)
        assertEquals(kept, inbox.takenAt)
        waitFor(hasText("1 weighing from the scale memory went to the inbox"))
        waitFor(hasText("Not checked on a real scale yet", substring = true))

        // The scale was told the time, kilograms and who stands on it (the profile on the main screen)
        val written = gatt.writes.map { it.first to it.second.toList() }
        assertEquals(ChipseaGatt.CURRENT_TIME, written[0].first)
        assertEquals(listOf<Byte>(0x01, 0x00), written[1].second)
        val user = written[2].second
        assertEquals(0xA0.toByte(), user[0])
        assertEquals(dmitry.toInt(), ((user[1].toInt() and 0xFF) shl 24) or ((user[2].toInt() and 0xFF) shl 16) or ((user[3].toInt() and 0xFF) shl 8) or (user[4].toInt() and 0xFF))
        assertEquals(180, user[6].toInt() and 0xFF)
        assertTrue(user[5].toInt() and 0x80 != 0)
    }

    @Test
    fun okokCloudResultAndHistoryWithTheProductsFixedProperty() {
        // This product sends a meaningless property: its weights are kilograms with one decimal
        val product = 1_104_000_001
        runBlocking { scales.bind(address, address, "OKOK_CLOUD", "0x02", product) }
        val dmitry = addProfile("Dmitry", lastWeightKg = 80.0)
        val takenAt = now.minusSeconds(30)
        val kept = now.minus(1, ChronoUnit.DAYS)
        gatt.sendAfter = ChipseaGatt.BODY_COMPOSITION_HISTORY
        gatt.frames = listOf(
            GattNotification(ChipseaGatt.BODY_COMPOSITION_HISTORY, cloud(kept, weightRaw = 790, ohmsX10 = 0)),
            GattNotification(ChipseaGatt.BODY_COMPOSITION_MEASUREMENT, cloud(takenAt, weightRaw = 803, ohmsX10 = 5123)),
        )
        openWeighing()
        waitForListener()

        val ad = byteArrayOf(0xFF.toByte(), 0xF0.toByte(), 0x02, 0x12, 0x23, 0x03) +
            byteArrayOf((product shr 24).toByte(), (product shr 16).toByte(), (product shr 8).toByte(), product.toByte())
        scanner.advertise(ad, address)

        waitFor(hasText("Saved for Dmitry"))
        val saved = runBlocking { measurements.observeHistory(dmitry).first() }.first()
        assertEquals(80.3, saved.weightKg, 1e-9)
        assertEquals(listOf(Impedance(Impedance.FOOT_TO_FOOT, 512.3)), saved.impedances)
        assertEquals(takenAt, saved.takenAt)
        assertEquals(79.0, runBlocking { measurements.observeInbox().first() }.single().weightKg, 1e-9)
        // Local time, then kilograms twice
        assertEquals(listOf(ChipseaGatt.CURRENT_TIME, ChipseaGatt.CHIPSEA_TX, ChipseaGatt.CHIPSEA_TX), gatt.writes.map { it.first })
    }

    @Test
    fun theScaleIsToldOnlyWhatItsSettingsAllow() {
        val scaleId = runBlocking { scales.bind(address, address, "OKOK_CLOUD_V4", null, 1) }
        val dmitry = addProfile("Dmitry", lastWeightKg = 80.0)
        val anna = addProfile("Anna", lastWeightKg = 61.0)
        runBlocking {
            // The clock and the display are left alone; Dmitry, the profile on the main screen, is not on this scale
            scales.update(scales.get(scaleId)!!.copy(displayUnit = ScaleDisplayUnit.KEEP, syncTime = false), excludedProfiles = setOf(dmitry))
        }
        gatt.frames = listOf(GattNotification(ChipseaGatt.BODY_COMPOSITION_MEASUREMENT, c5(now, weightRaw = 610, ohmsX10 = 0, heartRate = 0)))
        openWeighing()
        waitForListener()

        scanner.advertise(advertisement(weightRaw = 610), address)

        waitFor(hasText("Saved for Anna"))
        val user = gatt.writes.single().second
        assertEquals(0xA0.toByte(), user[0])
        assertEquals(anna.toInt(), user[4].toInt())
    }

    @Test
    fun aRecordSentAgainIsNotSavedTwice() {
        val scaleId = runBlocking { scales.bind(address, address, "OKOK_CLOUD_V4", null, 1) }
        val kept = now.minus(2, ChronoUnit.DAYS)
        runBlocking {
            measurements.add(
                Measurement(
                    profileId = null, takenAt = kept, zone = ZoneId.systemDefault(), source = MeasurementSource.SCALE,
                    weightKg = 79.0, person = null, scaleBindingId = scaleId,
                ),
            )
        }
        addProfile("Dmitry", lastWeightKg = 80.0)
        // A result after the record: frames are handled in order, so once it is saved the record was handled too
        gatt.frames = listOf(
            GattNotification(ChipseaGatt.BODY_COMPOSITION_HISTORY, c5(kept, weightRaw = 790, ohmsX10 = 0, heartRate = 0)),
            GattNotification(ChipseaGatt.BODY_COMPOSITION_MEASUREMENT, c5(now, weightRaw = 803, ohmsX10 = 0, heartRate = 0)),
        )
        openWeighing()
        waitForListener()

        scanner.advertise(advertisement(weightRaw = 0), address)

        waitFor(hasText("Saved for Dmitry"))
        assertEquals(1, runBlocking { measurements.observeInbox().first() }.size)
        assertEquals(1, gatt.connections.get())
    }

    @Test
    fun aRecordTakenBeforeStaysAwayAfterItWasDeleted() {
        val scaleId = runBlocking { scales.bind(address, address, "OKOK_CLOUD_V4", null, 1) }
        val deleted = now.minus(3, ChronoUnit.DAYS)
        val newer = now.minus(1, ChronoUnit.DAYS)
        // An earlier connection took the record at `deleted`; the person has deleted it from the inbox since
        runBlocking { scales.acceptHistoryUntil(scaleId, deleted) }
        addProfile("Dmitry", lastWeightKg = 80.0)
        gatt.frames = listOf(
            GattNotification(ChipseaGatt.BODY_COMPOSITION_HISTORY, c5(deleted, weightRaw = 790, ohmsX10 = 0, heartRate = 0)),
            GattNotification(ChipseaGatt.BODY_COMPOSITION_HISTORY, c5(newer, weightRaw = 795, ohmsX10 = 0, heartRate = 0)),
            GattNotification(ChipseaGatt.BODY_COMPOSITION_MEASUREMENT, c5(now, weightRaw = 803, ohmsX10 = 0, heartRate = 0)),
        )
        openWeighing()
        waitForListener()

        scanner.advertise(advertisement(weightRaw = 0), address)

        waitFor(hasText("Saved for Dmitry"))
        assertEquals(listOf(newer), runBlocking { measurements.observeInbox().first() }.map { it.takenAt })
        // The mark moves to the newest time seen, the result's
        compose.waitUntil(timeoutMillis = 5_000) {
            // The connection ends on the main thread, which reading the tree lets run
            compose.onAllNodes(isRoot()).fetchSemanticsNodes()
            runBlocking { scales.get(scaleId) }!!.historyAcceptedUntil == now
        }
    }

    /** `FF F0 10`: version 0x10 (V4), property: kilograms with one decimal in the V4 layout, weight LE, product. */
    private fun advertisement(weightRaw: Int) =
        byteArrayOf(0xFF.toByte(), 0xF0.toByte(), 0x10, 0x40, weightRaw.toByte(), (weightRaw shr 8).toByte(), 0, 0, 0, 1)

    /** An OKOKCloud frame with impedance, on the scale's clock in the phone's zone; property byte 0x12 (pounds, no decimals). */
    private fun cloud(time: Instant, weightRaw: Int, ohmsX10: Int): ByteArray {
        val t = time.atZone(ZoneId.systemDefault())
        return byteArrayOf(0x06, 0x03, t.year.toByte(), (t.year shr 8).toByte()) +
            byteArrayOf(t.monthValue.toByte(), t.dayOfMonth.toByte(), t.hour.toByte(), t.minute.toByte(), t.second.toByte()) +
            byteArrayOf(ohmsX10.toByte(), (ohmsX10 shr 8).toByte(), weightRaw.toByte(), (weightRaw shr 8).toByte(), 0, 0, 0x12)
    }

    private fun c5(time: Instant, weightRaw: Int, ohmsX10: Int, heartRate: Int): ByteArray {
        val s = time.epochSecond
        return byteArrayOf(
            0xC5.toByte(), 0x04,
            (s shr 24).toByte(), (s shr 16).toByte(), (s shr 8).toByte(), s.toByte(),
            weightRaw.toByte(), (weightRaw shr 8).toByte(),
            ohmsX10.toByte(), (ohmsX10 shr 8).toByte(),
            heartRate.toByte(),
        )
    }

    private fun openWeighing() {
        compose.onNodeWithText("Weigh", useUnmergedTree = true).performClick()
    }

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntilAtLeastOneExists(matcher, timeoutMillis = 5_000)

    private fun waitForListener() = compose.waitUntil(timeoutMillis = 5_000) {
        compose.onAllNodes(isRoot()).fetchSemanticsNodes()
        scanner.listeners > 0
    }

    private fun addProfile(name: String, lastWeightKg: Double): Long = runBlocking {
        val id = profiles.add(
            Profile(
                name = name,
                sex = Sex.MALE,
                birthDate = LocalDate.parse("1990-10-28"),
                heightCm = 180.0,
                color = 0xFF3F51B5.toInt(),
                createdAt = Instant.parse("2026-01-01T00:00:00Z"),
            ),
        )
        measurements.add(
            Measurement(
                profileId = id,
                takenAt = Instant.parse("2026-10-27T07:00:00Z"),
                zone = ZoneId.systemDefault(),
                source = MeasurementSource.MANUAL,
                weightKg = lastWeightKg,
                person = profiles.get(id)!!.snapshot(),
            ),
        )
        id
    }
}
