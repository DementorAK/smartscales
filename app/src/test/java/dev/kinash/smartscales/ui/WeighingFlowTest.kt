package dev.kinash.smartscales.ui

import android.Manifest
import android.app.Application
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dev.kinash.smartscales.MainActivity
import dev.kinash.smartscales.core.data.Measurement
import dev.kinash.smartscales.core.data.Profile
import dev.kinash.smartscales.core.data.repository.MeasurementRepository
import dev.kinash.smartscales.core.data.repository.ProfileRepository
import dev.kinash.smartscales.core.data.repository.ScaleBindingRepository
import dev.kinash.smartscales.core.model.Impedance
import dev.kinash.smartscales.core.model.MeasurementSource
import dev.kinash.smartscales.core.model.Sex
import dev.kinash.smartscales.core.protocol.V2Cipher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
import javax.inject.Inject

/** Weighing with a scale whose advertisements come from [FakeScanner]. */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
class WeighingFlowTest {

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

    @Before
    fun setUp() {
        hilt.inject()
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION)
    }

    @Test
    fun scaleIsFoundAndAdded() {
        openWeighing()
        waitFor(hasText("No scales yet", substring = true))
        compose.onNodeWithText("Add scale").performClick()
        waitForListener()

        scanner.advertise(v2Frame(seq = 1, weightRaw = 0, locked = false))
        waitFor(hasText("Chipsea OKOK V2 (C0)"))
        compose.onNodeWithText("Chipsea OKOK V2 (C0)").performClick()

        waitFor(hasText("Step on the scale"))
        val binding = runBlocking { scales.observeBound().first().single() }
        assertEquals("AA:BB:CC:00:11:22", binding.mac)
        assertEquals("OKOK_V2", binding.protocol)
    }

    @Test
    fun finishedWeighingGoesToTheOnlyMatchingProfile() {
        val scaleId = addScale()
        val dmitry = addProfile("Dmitry", lastWeightKg = 80.0)
        addProfile("Anna", lastWeightKg = 61.0)
        openWeighing()
        waitForListener()

        scanner.advertise(v2Frame(seq = 5, weightRaw = 798, locked = false))
        waitFor(hasText("79.8 kg"))
        scanner.advertise(v2Frame(seq = 5, weightRaw = 803, locked = true))

        waitFor(hasText("Saved for Dmitry"))
        val saved = runBlocking { measurements.observeHistory(dmitry).first() }.first()
        assertEquals(MeasurementSource.SCALE, saved.source)
        assertEquals(80.3, saved.weightKg, 1e-9)
        assertEquals(listOf(Impedance(Impedance.FOOT_TO_FOOT, 500.0)), saved.impedances)
        assertEquals(scaleId, saved.scaleBindingId)
        assertEquals(0x21, saved.scaleProperties)
        assertEquals(LocalDate.parse("1990-10-28"), saved.person!!.birthDate)
    }

    @Test
    fun unmatchedWeighingAsksWhoseItIs() {
        addScale()
        addProfile("Dmitry", lastWeightKg = 80.0)
        val anna = addProfile("Anna", lastWeightKg = 61.0)
        openWeighing()
        waitForListener()

        scanner.advertise(v2Frame(seq = 6, weightRaw = 700, locked = false))
        scanner.advertise(v2Frame(seq = 6, weightRaw = 700, locked = true))
        waitFor(hasText("Whose weighing is it?"))
        // Saved to the inbox before the answer, so it survives the process being killed meanwhile
        assertEquals(70.0, runBlocking { measurements.observeInbox().first() }.single().weightKg, 1e-9)
        compose.onNode(hasText("Anna") and hasAnyAncestor(isDialog()), useUnmergedTree = true).performClick()

        waitFor(hasText("Saved for Anna"))
        assertEquals(70.0, runBlocking { measurements.observeHistory(anna).first() }.first().weightKg, 1e-9)
        assertEquals(0, runBlocking { measurements.observeInbox().first() }.size)
    }

    @Test
    fun notMeMovesTheWeighingToTheInbox() {
        addScale()
        val dmitry = addProfile("Dmitry", lastWeightKg = 80.0)
        openWeighing()
        waitForListener()

        scanner.advertise(v2Frame(seq = 7, weightRaw = 805, locked = false))
        scanner.advertise(v2Frame(seq = 7, weightRaw = 805, locked = true))
        waitFor(hasText("Not me"))
        compose.onNodeWithText("Not me").performClick()
        waitFor(hasText("Inbox: decide later"))
        // The profile stays in the list in case "Not me" was a mistake
        compose.onNode(hasText("Dmitry") and hasAnyAncestor(isDialog()), useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Inbox: decide later").performClick()

        waitFor(hasText("Saved to the inbox"))
        val inbox = runBlocking { measurements.observeInbox().first() }.single()
        assertEquals(80.5, inbox.weightKg, 1e-9)
        assertNull(inbox.person)
        // Only the weighing added in setup is left in the history
        assertEquals(1, runBlocking { measurements.observeHistory(dmitry).first() }.size)
    }

    @Test
    fun weighingFinishedBeforeTheAppListenedIsSaved() {
        addScale()
        val dmitry = addProfile("Dmitry", lastWeightKg = 80.0)
        openWeighing()
        waitForListener()

        // Only the locked frame arrives, as on phones that report a device once per scan
        scanner.advertise(v2Frame(seq = 8, weightRaw = 801, locked = true))

        waitFor(hasText("Saved for Dmitry"))
        assertEquals(2, runBlocking { measurements.observeHistory(dmitry).first() }.size)
    }

    @Test
    fun finishedWeighingIsNotSavedAgainAfterComingBack() {
        addScale()
        val dmitry = addProfile("Dmitry", lastWeightKg = 80.0)
        openWeighing()
        waitForListener()
        scanner.advertise(v2Frame(seq = 9, weightRaw = 802, locked = false))
        scanner.advertise(v2Frame(seq = 9, weightRaw = 802, locked = true))
        waitFor(hasText("Saved for Dmitry"))

        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(isRoot()).fetchSemanticsNodes()
            scanner.listeners == 0
        }
        openWeighing()
        waitForListener()
        // The scale still advertises the result, then the next person steps on
        scanner.advertise(v2Frame(seq = 9, weightRaw = 802, locked = true))
        scanner.advertise(v2Frame(seq = 10, weightRaw = 500, locked = false))

        waitFor(hasText("50.0 kg"))
        assertEquals(2, runBlocking { measurements.observeHistory(dmitry).first() }.size)
    }

    @Test
    fun weighingSavedBeforeRestartIsNotSavedAgain() {
        val scaleId = addScale()
        val dmitry = addProfile("Dmitry", lastWeightKg = 80.0)
        runBlocking {
            measurements.add(
                Measurement(
                    profileId = dmitry,
                    takenAt = Instant.now().minusSeconds(60),
                    zone = ZoneId.systemDefault(),
                    source = MeasurementSource.SCALE,
                    weightKg = 80.1,
                    person = profiles.get(dmitry)!!.snapshot(),
                    impedances = listOf(Impedance(Impedance.FOOT_TO_FOOT, 500.0)),
                    scaleBindingId = scaleId,
                ),
            )
        }
        openWeighing()
        waitForListener()

        scanner.advertise(v2Frame(seq = 11, weightRaw = 801, locked = true))
        scanner.advertise(v2Frame(seq = 12, weightRaw = 500, locked = false))

        waitFor(hasText("50.0 kg"))
        assertEquals(2, runBlocking { measurements.observeHistory(dmitry).first() }.size)
    }

    @Test
    fun newKeyIsConfirmedByTheDisplayOnce() {
        val scaleId = runBlocking {
            val id = scales.bind("AA:BB:CC:DD:EE:FF", "11:22:33:44:55:66", "OKOK_V2", null, 1)
            scales.update(scales.get(id)!!.copy(v2Key = "12345678"))
            id
        }
        val dmitry = addProfile("Dmitry", lastWeightKg = 80.0)
        openWeighing()
        waitForListener()

        scanner.advertise(encryptedFrame(seq = 1, weightRaw = 790, locked = false))
        scanner.advertise(encryptedFrame(seq = 1, weightRaw = 800, locked = true))
        waitFor(hasText("Check the key"))
        compose.onNodeWithText("Different").performClick()
        waitFor(hasText("key is probably wrong", substring = true))
        assertEquals(1, runBlocking { measurements.observeHistory(dmitry).first() }.size)

        scanner.advertise(encryptedFrame(seq = 2, weightRaw = 790, locked = false))
        scanner.advertise(encryptedFrame(seq = 2, weightRaw = 801, locked = true))
        waitFor(hasText("Check the key"))
        compose.onNodeWithText("Same weight").performClick()
        waitFor(hasText("Saved for Dmitry"))
        assertTrue(runBlocking { scales.get(scaleId)!!.v2KeyConfirmed })

        // Asked once: the next weighing is saved by itself
        scanner.advertise(encryptedFrame(seq = 3, weightRaw = 790, locked = false))
        scanner.advertise(encryptedFrame(seq = 3, weightRaw = 802, locked = true))
        compose.waitUntil(timeoutMillis = 5_000) {
            runBlocking { measurements.observeHistory(dmitry).first() }.size == 3
        }
        compose.onNodeWithText("Check the key").assertDoesNotExist()
    }

    @Test
    fun firstWeighingWithoutProfilesGoesToANewProfile() {
        addScale()
        openWeighing()
        waitForListener()
        scanner.advertise(v2Frame(seq = 12, weightRaw = 839, locked = false))
        scanner.advertise(v2Frame(seq = 12, weightRaw = 839, locked = true))

        waitFor(hasText("Whose weighing is it?"))
        compose.onNode(hasText("New profile") and hasAnyAncestor(isDialog()), useUnmergedTree = true).performClick()
        waitFor(hasText("Date of birth"))
        compose.onNodeWithTag("profile_name").performTextInput("Dmitry")
        compose.onNodeWithText("Male").performScrollTo().performClick()
        compose.onNodeWithText("Date of birth").performScrollTo().performClick()
        compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextInput("10281990")
        compose.onNodeWithText("OK").performClick()
        compose.onNodeWithTag("profile_height").performScrollTo().performTextInput("180")
        compose.onNodeWithText("Save").performClick()

        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(isRoot()).fetchSemanticsNodes()
            runBlocking { measurements.observeInbox().first() }.isEmpty()
        }
        val profile = runBlocking { profiles.observeProfiles().first().single() }
        val saved = runBlocking { measurements.observeHistory(profile.id).first() }.single()
        assertEquals(83.9, saved.weightKg, 1e-9)
        assertEquals(LocalDate.parse("1990-10-28"), saved.person!!.birthDate)
        assertEquals(0, runBlocking { measurements.observeInbox().first() }.size)
    }

    @Test
    fun goalReachedByTheWeighingIsSaidOnTheScreen() {
        addScale()
        val dmitry = addProfile("Dmitry", lastWeightKg = 80.6)
        runBlocking { profiles.setGoal(dmitry, ProfileRepository.WEIGHT_GOAL, 80.5) }
        openWeighing()
        waitForListener()

        scanner.advertise(v2Frame(seq = 5, weightRaw = 803, locked = true))
        waitFor(hasText("Saved for Dmitry"))
        waitFor(hasText("Goal reached: Weight 80.5 kg"))
        compose.onNodeWithText("\u22120.3 kg since the weighing before").assertExists()
        // Stays until closed, not a message that goes by itself
        compose.onNodeWithText("Close").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Saved for Dmitry")).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun weightFarFromTheUsualOneIsSaid() {
        addScale()
        val dmitry = addProfile("Dmitry", lastWeightKg = 76.0)
        // A fortnight about 76 kg, then 79.5 yesterday: the new 80.3 is close to the last, far from the usual
        runBlocking {
            listOf(8L to 76.0, 6L to 76.0, 1L to 79.5).forEach { (days, kg) ->
                measurements.add(
                    Measurement(
                        profileId = dmitry,
                        takenAt = Instant.now() - java.time.Duration.ofDays(days),
                        zone = ZoneId.systemDefault(),
                        source = MeasurementSource.MANUAL,
                        weightKg = kg,
                        person = profiles.get(dmitry)!!.snapshot(),
                    ),
                )
            }
        }
        openWeighing()
        waitForListener()

        scanner.advertise(v2Frame(seq = 5, weightRaw = 803, locked = true))
        waitFor(hasText("Saved for Dmitry"))
        waitFor(hasText("4.3 kg more than the usual weight of the last 14 days. If this is not Dmitry, tap “Not me”."))
    }

    private fun openWeighing() {
        compose.onNodeWithText("Weigh", useUnmergedTree = true).performClick()
    }

    /** Database work runs on Room's threads, so screens update a little later than the event. */
    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntilAtLeastOneExists(matcher, timeoutMillis = 5_000)

    /** Waits until the screen scans; reading the tree each time lets pending recomposition run. */
    private fun waitForListener() = compose.waitUntil(timeoutMillis = 5_000) {
        compose.onAllNodes(isRoot()).fetchSemanticsNodes()
        scanner.listeners > 0
    }

    private fun addScale(): Long = runBlocking {
        scales.bind("AA:BB:CC:00:11:22", "AA:BB:CC:00:11:22", "OKOK_V2", null, 1)
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

    /** `C0` frame from the scale AA:BB:CC:DD:EE:FF encrypted with the key 12345678: 500.0 Ω, kilograms. */
    private fun encryptedFrame(seq: Int, weightRaw: Int, locked: Boolean): ByteArray {
        val key = byteArrayOf(0x12, 0x34, 0x56, 0x78)
        val weight = V2Cipher.encrypt(weightRaw, key)
        val impedance = V2Cipher.encrypt(5000 or 0x8000, key)
        val macTail = byteArrayOf(0xCC.toByte(), 0xDD.toByte(), 0xEE.toByte(), 0xFF.toByte())
        val macHead = V2Cipher.encrypt(0xAABB, macTail)
        return byteArrayOf(
            0xC0.toByte(), seq.toByte(),
            (weight shr 8).toByte(), weight.toByte(),
            (impedance shr 8).toByte(), impedance.toByte(),
            0x00, 0x01,
            (0x20 or if (locked) 0x01 else 0x00).toByte(),
            (macHead shr 8).toByte(), macHead.toByte(),
        ) + macTail
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
