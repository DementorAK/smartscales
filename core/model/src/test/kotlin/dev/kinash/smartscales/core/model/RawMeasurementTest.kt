package dev.kinash.smartscales.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class RawMeasurementTest {

    private val person = PersonSnapshot(180.0, Sex.MALE, LocalDate.parse("1990-10-28"))

    private fun measurement(
        takenAt: String = "2026-10-27T07:00:00Z",
        zone: String = "Europe/Kyiv",
        weightKg: Double = 80.0,
        impedances: List<Impedance> = emptyList(),
        heartRateBpm: Int? = null,
    ) = RawMeasurement(
        takenAt = Instant.parse(takenAt),
        zone = ZoneId.of(zone),
        source = MeasurementSource.SCALE,
        weightKg = weightKg,
        person = person,
        impedances = impedances,
        heartRateBpm = heartRateBpm,
    )

    @Test
    fun ageUsesLocalDateOfMeasurementZone() {
        // 2026-10-27T22:30Z is already the 28th, the birthday, in Kyiv (UTC+2) but still the 27th in New York
        val instant = "2026-10-27T22:30:00Z"
        assertEquals(36, measurement(takenAt = instant, zone = "Europe/Kyiv").ageYears)
        assertEquals(35, measurement(takenAt = instant, zone = "America/New_York").ageYears)
        assertEquals(LocalDate.parse("2026-10-28"), measurement(takenAt = instant, zone = "Europe/Kyiv").localDate)
    }

    @Test
    fun footToFootImpedance() {
        val z = Impedance(Impedance.FOOT_TO_FOOT, 450.0)
        assertEquals(z, measurement(impedances = listOf(z)).footToFootImpedance)
        assertNull(measurement().footToFootImpedance)
    }

    @Test
    fun twoUnknownFrequenciesPerChannel() {
        // CloudV4 CB frames: 5 channels x 2 frequencies, the frequencies themselves are not reported
        val impedances = (1..5).flatMap { channel ->
            listOf(
                Impedance(channel, 300.0 + channel, frequencyIndex = 0),
                Impedance(channel, 280.0 + channel, frequencyIndex = 1),
            )
        }
        val result = measurement(impedances = impedances)
        assertEquals(10, result.impedances.size)
        assertNull(result.impedances.first().frequencyKhz)
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateChannelAndFrequencyIndexIsRejected() {
        measurement(impedances = listOf(Impedance(1, 300.0), Impedance(1, 301.0)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownFrequencyIndexIsRejected() {
        Impedance(1, 300.0, frequencyIndex = 2)
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownChannelIsRejected() {
        Impedance(6, 300.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonPositiveImpedanceIsRejected() {
        Impedance(Impedance.FOOT_TO_FOOT, 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonPositiveWeightIsRejected() {
        measurement(weightKg = 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonPositiveHeartRateIsRejected() {
        measurement(heartRateBpm = 0)
    }
}
