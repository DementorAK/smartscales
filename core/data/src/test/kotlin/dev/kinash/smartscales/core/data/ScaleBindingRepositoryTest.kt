package dev.kinash.smartscales.core.data

import dev.kinash.smartscales.core.data.repository.MeasurementRepository
import dev.kinash.smartscales.core.data.repository.ProfileRepository
import dev.kinash.smartscales.core.data.repository.ScaleBindingRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
class ScaleBindingRepositoryTest : DatabaseTestBase() {

    private val now = Instant.parse("2026-10-28T09:00:00Z")
    private val scales by lazy { ScaleBindingRepository(database, Clock.fixed(now, ZoneOffset.UTC)) }

    @Test
    fun rebindingSameMacRestoresCalibrationAndKey() = runTest {
        val id = scales.bind("aa:bb:cc:dd:ee:ff", "11:22:33:44:55:66", "chipsea_c0", "v2", productId = 7)
        scales.update(scales.get(id)!!.copy(name = "Bathroom", v2Key = "00112233445566778899aabbccddeeff", impedanceFactor = 1.08))

        scales.unbind(id)
        assertEquals(emptyList<ScaleBinding>(), scales.observeBound().first())

        val again = scales.bind("AA:BB:CC:DD:EE:FF", "11:22:33:44:55:77", "chipsea_c0", "v2", productId = 8)
        assertEquals(id, again)
        val restored = scales.get(id)!!
        assertFalse(restored.unbound)
        assertEquals("Bathroom", restored.name)
        assertEquals("00112233445566778899aabbccddeeff", restored.v2Key)
        assertEquals(1.08, restored.impedanceFactor, 0.0)
        assertEquals(8, restored.productId)
        assertEquals("11:22:33:44:55:77", restored.bleAddress)
    }

    @Test
    fun scaleIsIdentifiedByItsOwnMacNotByAndroidAddress() = runTest {
        // C0 scales: the MAC decrypted from the frame differs from the address Android sees
        val first = scales.bind("AA:BB:CC:DD:EE:FF", "11:22:33:44:55:66", "chipsea_c0", "v2", null)
        val second = scales.bind("AA:BB:CC:DD:EE:FF", "11:22:33:44:55:99", "chipsea_c0", "v2", null)
        val other = scales.bind("AA:BB:CC:DD:EE:00", "11:22:33:44:55:99", "chipsea_c0", "v2", null)

        assertEquals(first, second)
        assertEquals(listOf(first, other), scales.observeBound().first().map { it.id })
        assertEquals(first, scales.getByMac("aa:bb:cc:dd:ee:ff")!!.id)
    }

    @Test
    fun unbindingKeepsHistoryLinked() = runTest {
        val id = scales.bind("AA:BB:CC:DD:EE:FF", null, "chipsea_ca20", null, null)
        val profileId = ProfileRepository(database).add(profile())
        val measurements = MeasurementRepository(database)
        val measurementId = measurements.add(measurement(profileId).copy(scaleBindingId = id))

        scales.unbind(id)

        assertEquals(id, measurements.get(measurementId)!!.scaleBindingId)
        assertEquals(1.0, scales.get(id)!!.impedanceFactor, 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun calibrationFactorMustBePositive() = runTest {
        val id = scales.bind("AA:BB:CC:DD:EE:FF", null, "chipsea_ca20", null, null)
        scales.update(scales.get(id)!!.copy(impedanceFactor = 0.0))
    }
}
