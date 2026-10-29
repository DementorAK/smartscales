package dev.kinash.smartscales.core.data

import dev.kinash.smartscales.core.data.repository.MeasurementRepository
import dev.kinash.smartscales.core.data.repository.ProfileRepository
import dev.kinash.smartscales.core.model.Impedance
import dev.kinash.smartscales.core.model.ManualFields
import dev.kinash.smartscales.core.model.MeasurementSource
import dev.kinash.smartscales.core.model.PersonSnapshot
import dev.kinash.smartscales.core.model.Sex
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
class MeasurementRepositoryTest : DatabaseTestBase() {

    private val profiles by lazy { ProfileRepository(database, clock) }
    private val measurements by lazy { MeasurementRepository(database) }

    @Test
    fun measurementRoundTrip() = runTest {
        val profileId = profiles.add(profile())
        val impedances = (1..5).flatMap { channel ->
            listOf(Impedance(channel, 300.0 + channel, 0), Impedance(channel, 280.0 + channel, 1, frequencyKhz = 250))
        }
        val saved = measurement(profileId).copy(
            source = MeasurementSource.SCALE,
            impedances = impedances,
            heartRateBpm = 64,
            manualValues = mapOf(ManualFields.WAIST_CM to 84.5),
            importedValues = mapOf("fat_percent" to 18.2),
            scaleProperties = 0x21,
            note = "After training",
            tags = listOf("morning", "Fasted"),
        )
        val id = measurements.add(saved)

        val loaded = measurements.get(id)!!
        assertEquals(saved.copy(id = id, tags = listOf("Fasted", "morning")), loaded)
        assertEquals(10, loaded.toRawMeasurement()!!.impedances.size)
    }

    @Test
    fun tagsAreCaseInsensitiveAndShared() = runTest {
        val profileId = profiles.add(profile())
        measurements.add(measurement(profileId).copy(tags = listOf("Morning")))
        measurements.add(measurement(profileId).copy(tags = listOf("morning ", "MORNING", "")))

        assertEquals(listOf("Morning"), measurements.observeTags().first())
        assertTrue(measurements.observeHistory(profileId).first().all { it.tags == listOf("Morning") })
    }

    @Test
    fun cyrillicTagsAreCaseInsensitive() = runTest {
        // SQLite NOCASE folds only Latin letters
        val profileId = profiles.add(profile())
        measurements.add(measurement(profileId).copy(tags = listOf("Утро")))
        measurements.add(measurement(profileId).copy(tags = listOf("утро", "УТРО", "Ёлка", "ёлка")))

        assertEquals(listOf("Утро", "Ёлка"), measurements.observeTags().first())
    }

    @Test
    fun updateReplacesDetails() = runTest {
        val profileId = profiles.add(profile())
        val id = measurements.add(
            measurement(profileId).copy(impedances = listOf(Impedance(0, 450.0)), tags = listOf("a", "b")),
        )
        val edited = measurements.get(id)!!.copy(weightKg = 79.5, impedances = emptyList(), tags = listOf("b"))
        measurements.update(edited)

        assertEquals(edited, measurements.get(id))
    }

    @Test
    fun inboxHoldsMeasurementsWithoutProfile() = runTest {
        val profileId = profiles.add(profile())
        measurements.add(measurement(profileId))
        val inboxId = measurements.add(measurement(null).copy(person = null))

        val inbox = measurements.observeInbox().first()
        assertEquals(listOf(inboxId), inbox.map { it.id })
        assertNull(inbox.single().toRawMeasurement())
    }

    @Test
    fun historyIsNewestFirst() = runTest {
        val profileId = profiles.add(profile())
        val older = measurements.add(measurement(profileId, takenAt = "2026-10-01T07:00:00Z"))
        val newer = measurements.add(measurement(profileId, takenAt = "2026-10-20T07:00:00Z"))

        assertEquals(listOf(newer, older), measurements.observeHistory(profileId).first().map { it.id })
    }

    @Test
    fun transferTakesHeightFromNearestMeasurementOfTarget() = runTest {
        val source = profiles.add(profile())
        val target = profiles.add(profile(name = "Anna", sex = Sex.FEMALE, birthDate = "1992-03-15", heightCm = 168.0))
        measurements.add(measurement(target, takenAt = "2026-01-10T07:00:00Z", heightCm = 165.0))
        measurements.add(measurement(target, takenAt = "2026-10-25T07:00:00Z", heightCm = 166.0))
        val id = measurements.add(measurement(source, takenAt = "2026-10-27T07:00:00Z"))

        measurements.transfer(id, target)

        val moved = measurements.get(id)!!
        assertEquals(target, moved.profileId)
        assertEquals(PersonSnapshot(166.0, Sex.FEMALE, LocalDate.parse("1992-03-15")), moved.person)
    }

    @Test
    fun transferFallsBackToProfileHeight() = runTest {
        val target = profiles.add(profile(name = "Anna", sex = Sex.FEMALE, birthDate = "1992-03-15", heightCm = 168.0))
        val id = measurements.add(measurement(null).copy(person = null))

        measurements.transfer(id, target)

        assertEquals(PersonSnapshot(168.0, Sex.FEMALE, LocalDate.parse("1992-03-15")), measurements.get(id)!!.person)
    }

    @Test
    fun transferWithManualHeight() = runTest {
        val target = profiles.add(profile(heightCm = 168.0))
        val id = measurements.add(measurement(null).copy(person = null))

        measurements.transfer(id, target, heightCm = 170.5)

        assertEquals(170.5, measurements.get(id)!!.person!!.heightCm, 0.0)
    }

    @Test
    fun transferRefusesPersonBornAfterMeasurement() = runTest {
        val source = profiles.add(profile())
        val child = profiles.add(profile(name = "Kid", birthDate = "2026-10-28"))
        val id = measurements.add(measurement(source, takenAt = "2026-10-27T07:00:00Z"))

        val error = runCatching { measurements.transfer(id, child) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertEquals(source, measurements.get(id)!!.profileId)
    }

    @Test
    fun measurementBeforeBirthIsNotSaved() = runTest {
        val profileId = profiles.add(profile())
        val beforeBirth = measurement(profileId, takenAt = "1990-10-27T07:00:00Z")

        val error = runCatching { measurements.add(beforeBirth) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertEquals(0, profiles.countMeasurements(profileId))
    }

    @Test
    fun updateAndTransferSavesEditsAndMoves() = runTest {
        val source = profiles.add(profile())
        val target = profiles.add(profile(name = "Anna", sex = Sex.FEMALE, heightCm = 168.0))
        val id = measurements.add(measurement(source))

        measurements.updateAndTransfer(measurements.get(id)!!.copy(weightKg = 79.5, note = "after run"), target)

        val moved = measurements.get(id)!!
        assertEquals(target, moved.profileId)
        assertEquals(79.5, moved.weightKg, 0.0)
        assertEquals("after run", moved.note)
        assertEquals(Sex.FEMALE, moved.person!!.sex)
    }

    @Test
    fun failedTransferKeepsEditsUnsaved() = runTest {
        val source = profiles.add(profile())
        val child = profiles.add(profile(name = "Kid", birthDate = "2026-10-28"))
        val id = measurements.add(measurement(source))
        val before = measurements.get(id)!!

        runCatching { measurements.updateAndTransfer(before.copy(weightKg = 79.5), child) }

        assertEquals(before, measurements.get(id))
    }

    @Test
    fun inboxCountFollowsInbox() = runTest {
        val profileId = profiles.add(profile())
        measurements.add(measurement(null).copy(person = null))
        measurements.add(measurement(null).copy(person = null))
        measurements.add(measurement(profileId))

        assertEquals(2, measurements.observeInboxCount().first())
    }

    @Test
    fun measurementKeepsZoneOfPlaceOfWeighing() = runTest {
        val profileId = profiles.add(profile())
        val id = measurements.add(
            measurement(profileId).copy(takenAt = Instant.parse("2026-10-27T22:30:00Z"), zone = ZoneId.of("America/New_York")),
        )

        val raw = measurements.get(id)!!.toRawMeasurement()!!
        assertEquals(ZoneId.of("America/New_York"), raw.zone)
        assertEquals(35, raw.ageYears)
    }
}
