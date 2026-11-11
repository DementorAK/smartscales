package dev.kinash.smartscales.ui

import android.Manifest
import android.app.Application
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
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
import dev.kinash.smartscales.core.data.repository.MeasurementRepository
import dev.kinash.smartscales.core.data.repository.ProfileRepository
import dev.kinash.smartscales.core.data.repository.ScaleBindingRepository
import dev.kinash.smartscales.core.model.MeasurementSource
import dev.kinash.smartscales.core.model.Sex
import dev.kinash.smartscales.core.protocol.ChipseaGatt
import dev.kinash.smartscales.core.model.ScaleValues
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
 * Weighing with an OKOK v1.1 scale over a fake GATT connection. The frames are synthetic, built from the
 * protocol description: no OKOK GATT scale has been at hand.
 */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
class OkokWeighingFlowTest {

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
        gatt.sendAfter = ChipseaGatt.CHIPSEA_RX
    }

    @Test
    fun theScaleNamesWhoseWeighingsTheyAre() {
        runBlocking { scales.bind(address, address, "OKOK", "0x11", 7) }
        val dmitry = addProfile("Dmitry", lastWeightKg = 80.0)
        val anna = addProfile("Anna", lastWeightKg = 61.0)
        // Kept after pairing (the clock tolerance allows it), and before it, when the numbers were another app's
        val kept = now.plusSeconds(30)
        val old = now.minus(1, ChronoUnit.DAYS)
        gatt.frames = listOf(
            rx(message(0x13, kept.epochSecond, userId = dmitry.toInt(), weight = 795)),
            rx(message(0x13, old.epochSecond, userId = 0, weight = 700)),
            rx(message(0x13, old.plusSeconds(60).epochSecond, userId = dmitry.toInt(), weight = 640)),
            // 70 kg matches no one by weight, but the scale says it is Anna
            rx(message(0x12, 0, userId = anna.toInt(), weight = 700)),
        )
        openWeighing()
        waitForListener()

        scanner.advertise(advertisement(), address)

        waitFor(hasText("Saved for Anna"))
        val result = runBlocking { measurements.observeHistory(anna).first() }.first()
        assertEquals(70.0, result.weightKg, 1e-9)
        assertEquals(MeasurementSource.SCALE, result.source)
        assertTrue(result.impedances.isEmpty())
        // The scale's own numbers are kept as its data
        assertEquals(22.5, result.importedValues.getValue(ScaleValues.FAT_PERCENT), 1e-9)
        assertEquals(40.0, result.importedValues.getValue(ScaleValues.MUSCLE_KG), 1e-9)

        // A record with a person goes to them; one without, or from before pairing, to the inbox
        assertEquals(listOf(kept, Instant.parse("2026-10-27T07:00:00Z")), runBlocking { measurements.observeHistory(dmitry).first() }.map { it.takenAt })
        assertEquals(setOf(70.0, 64.0), runBlocking { measurements.observeInbox().first() }.map { it.weightKg }.toSet())

        // Everyone went to the scale twice, then the history was asked for after the result
        assertEquals(listOf(0x10, 0x10, 0x10, 0x10, 0x11), gatt.writes.map { it.second[3].toInt() })
    }

    @Test
    fun thePersonAnswersTheScaleQuestion() {
        runBlocking { scales.bind(address, address, "OKOK", "0x11", 7) }
        addProfile("Dmitry", lastWeightKg = 70.5)
        val anna = addProfile("Anna", lastWeightKg = 69.5)
        gatt.sleepAfterFrames = false
        gatt.frames = listOf(rx(question(70.0, 1L, anna)))
        openWeighing()
        waitForListener()

        scanner.advertise(advertisement(), address)

        waitFor(hasText("Who is on the scale?"))
        compose.onNode(hasText("Anna") and hasAnyAncestor(isDialog()), useUnmergedTree = true).performClick()
        compose.waitUntil(timeoutMillis = 5_000) { gatt.writes.any { it.second[3].toInt() == 0x15 } }
        val select = gatt.writes.first { it.second[3].toInt() == 0x15 }.second
        assertEquals(anna.toInt(), select[7].toInt())
    }

    private fun rx(frame: ByteArray) = GattNotification(ChipseaGatt.CHIPSEA_RX, frame)

    private fun be(value: Int) = byteArrayOf((value shr 8).toByte(), value.toByte())

    private fun be32(value: Long) = byteArrayOf((value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte())

    /** A v1.1 `12`/`13` in one packet: locked kilograms with one decimal, the person, weight and the scale's numbers. */
    private fun message(type: Int, time: Long, userId: Int, weight: Int): ByteArray {
        val payload = be32(time) + byteArrayOf(0x01, 0) + be32(userId.toLong()) + be(weight) + be(225) + be(552) + be(400) +
            be(1650) + be(85) + byteArrayOf(31)
        return byteArrayOf(0xCA.toByte(), 0x11, (payload.size + 2).toByte(), type.toByte(), 0x11) + payload + 0
    }

    private fun question(weightKg: Double, vararg candidates: Long): ByteArray {
        val payload = be((weightKg * 10).toInt()) + byteArrayOf(0x00) + candidates.flatMap { be32(it).toList() }
        return byteArrayOf(0xCA.toByte(), 0x11, (payload.size + 2).toByte(), 0x14, 0x11) + payload + 0
    }

    /** `CA 11` of a scale with body composition: property, device type, weight, product. */
    private fun advertisement() = byteArrayOf(0xCA.toByte(), 0x11, 0x0A, 0x01, 0x01, 0x03, 0x20, 0, 0, 0, 0x07)

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
                sex = if (name == "Anna") Sex.FEMALE else Sex.MALE,
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
