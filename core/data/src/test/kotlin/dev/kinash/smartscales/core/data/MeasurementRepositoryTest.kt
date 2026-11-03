package dev.kinash.smartscales.core.data

import dev.kinash.smartscales.core.data.repository.MeasurementRepository
import dev.kinash.smartscales.core.data.repository.ProfileRepository
import dev.kinash.smartscales.core.data.repository.ScaleBindingRepository
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
    fun weighingFromTheScaleTakesTheProfileCircumferences() = runTest {
        val profileId = profiles.add(profile())
        profiles.setManualValues(profileId, mapOf(ManualFields.WAIST_CM to 86.0, ManualFields.NECK_CM to 39.0))
        val id = measurements.add(measurement(profileId).copy(source = MeasurementSource.SCALE))
        assertEquals(mapOf("waist" to 86.0, "neck" to 39.0), measurements.get(id)!!.manualValues)

        // A manual entry keeps what its form had: the person may have cleared a field on purpose
        val manual = measurements.add(measurement(profileId).copy(manualValues = mapOf(ManualFields.WAIST_CM to 84.0)))
        assertEquals(mapOf("waist" to 84.0), measurements.get(manual)!!.manualValues)

        // Changing the profile later does not rewrite the past
        profiles.setManualValues(profileId, mapOf(ManualFields.WAIST_CM to 90.0))
        assertEquals(86.0, measurements.get(id)!!.manualValues["waist"])
    }

    @Test
    fun transferTakesTheCircumferencesOfTheTarget() = runTest {
        val dmitry = profiles.add(profile())
        val anna = profiles.add(profile("Anna", Sex.FEMALE, "1992-03-15", 168.0))
        profiles.setManualValues(dmitry, mapOf(ManualFields.WAIST_CM to 86.0, ManualFields.NECK_CM to 39.0))
        profiles.setManualValues(anna, mapOf(ManualFields.WAIST_CM to 70.0, ManualFields.HIP_CM to 96.0))
        val id = measurements.add(
            measurement(dmitry).copy(source = MeasurementSource.SCALE, manualValues = mapOf("custom" to 1.0)),
        )

        measurements.transfer(id, anna)
        // Anna's waist and hips, none of Dmitry's neck; a field of a formula set stays
        assertEquals(mapOf("waist" to 70.0, "hip" to 96.0, "custom" to 1.0), measurements.get(id)!!.manualValues)
    }

    @Test
    fun weighingFromTheInboxGetsTheCircumferencesOfItsProfile() = runTest {
        val profileId = profiles.add(profile())
        profiles.setManualValues(profileId, mapOf(ManualFields.WAIST_CM to 86.0))
        val id = measurements.add(measurement(null).copy(person = null, source = MeasurementSource.SCALE))
        measurements.transfer(id, profileId)
        assertEquals(mapOf("waist" to 86.0), measurements.get(id)!!.manualValues)
    }

    @Test
    fun editedCircumferencesStayWhenTheEditMovesTheMeasurement() = runTest {
        val dmitry = profiles.add(profile())
        val anna = profiles.add(profile("Anna", Sex.FEMALE, "1992-03-15", 168.0))
        profiles.setManualValues(anna, mapOf(ManualFields.WAIST_CM to 70.0))
        val id = measurements.add(measurement(dmitry))
        measurements.updateAndTransfer(measurements.get(id)!!.copy(manualValues = mapOf("waist" to 72.5)), anna)
        assertEquals(mapOf("waist" to 72.5), measurements.get(id)!!.manualValues)
    }

    @Test
    fun manualEntryKeepsItsCircumferencesWhenMoved() = runTest {
        val dmitry = profiles.add(profile())
        val anna = profiles.add(profile("Anna", Sex.FEMALE, "1992-03-15", 168.0))
        profiles.setManualValues(anna, mapOf(ManualFields.WAIST_CM to 70.0))
        val id = measurements.add(measurement(dmitry).copy(manualValues = mapOf(ManualFields.WAIST_CM to 71.5)))
        measurements.transfer(id, anna)
        assertEquals(mapOf("waist" to 71.5), measurements.get(id)!!.manualValues)
    }

    @Test
    fun inboxWeighingSortedInItsFormGetsTheCircumferencesOfTheProfile() = runTest {
        val profileId = profiles.add(profile())
        profiles.setManualValues(profileId, mapOf(ManualFields.WAIST_CM to 86.0))
        val id = measurements.add(measurement(null).copy(person = null, source = MeasurementSource.SCALE))
        val edited = measurements.get(id)!!.copy(person = profile().snapshot(), note = "edited")
        measurements.updateAndTransfer(edited, profileId)
        assertEquals(mapOf("waist" to 86.0), measurements.get(id)!!.manualValues)
        assertEquals("edited", measurements.get(id)!!.note)
    }

    @Test
    fun newProfileForAnInboxWeighingGivesItTheCircumferencesTypedWithIt() = runTest {
        val id = measurements.add(measurement(null).copy(person = null, source = MeasurementSource.SCALE))
        val profileId = measurements.claimForNewProfile(id) {
            profiles.add(profile()).also { profiles.setManualValues(it, mapOf(ManualFields.WAIST_CM to 86.0)) }
        }
        assertEquals(profileId, measurements.get(id)!!.profileId)
        assertEquals(mapOf("waist" to 86.0), measurements.get(id)!!.manualValues)
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
    fun latestWeightsTakeNewestMeasurementOfEachProfile() = runTest {
        val dmitry = profiles.add(profile())
        val anna = profiles.add(profile(name = "Anna"))
        profiles.add(profile(name = "Kid"))
        measurements.add(measurement(dmitry, takenAt = "2026-10-01T07:00:00Z", weightKg = 82.0))
        measurements.add(measurement(dmitry, takenAt = "2026-10-20T07:00:00Z", weightKg = 80.5))
        measurements.add(measurement(anna, takenAt = "2026-10-10T07:00:00Z", weightKg = 61.0))
        measurements.add(measurement(null, takenAt = "2026-10-25T07:00:00Z", weightKg = 70.0).copy(person = null))

        assertEquals(mapOf(dmitry to 80.5, anna to 61.0), measurements.latestWeights())
    }

    @Test
    fun latestFromScaleLooksInProfilesAndInbox() = runTest {
        val scaleId = ScaleBindingRepository(database, clock).bind("AA:BB:CC:DD:EE:FF", null, "OKOK_V2", null, null)
        assertNull(measurements.latestFromScale(scaleId))

        val profileId = profiles.add(profile())
        measurements.add(measurement(profileId, takenAt = "2026-10-26T07:00:00Z").copy(scaleBindingId = scaleId))
        val inbox = measurements.add(
            measurement(null, takenAt = "2026-10-27T07:00:00Z", weightKg = 70.0).copy(person = null, scaleBindingId = scaleId),
        )
        measurements.add(measurement(profileId, takenAt = "2026-10-28T07:00:00Z"))

        assertEquals(inbox, measurements.latestFromScale(scaleId)!!.id)
    }

    @Test
    fun newProfileClaimsWeighingFromInbox() = runTest {
        val weighing = measurements.add(measurement(null).copy(person = null))

        val profileId = measurements.claimForNewProfile(weighing) { profiles.add(profile()) }

        assertEquals(profileId, measurements.get(weighing)!!.profileId)
        assertEquals(0, measurements.observeInbox().first().size)
    }

    @Test
    fun weighingSortedMeanwhileStaysWhereItIs() = runTest {
        val anna = profiles.add(profile(name = "Anna"))
        val weighing = measurements.add(measurement(anna))

        measurements.claimForNewProfile(weighing) { profiles.add(profile()) }

        assertEquals(anna, measurements.get(weighing)!!.profileId)
    }

    @Test
    fun profileIsNotCreatedWhenItCannotTakeTheWeighing() = runTest {
        val weighing = measurements.add(measurement(null, takenAt = "2026-10-27T07:00:00Z").copy(person = null))

        val result = runCatching {
            measurements.claimForNewProfile(weighing) { profiles.add(profile(birthDate = "2026-10-28")) }
        }

        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertEquals(0, profiles.observeProfiles().first().size)
        assertNull(measurements.get(weighing)!!.profileId)
    }

    @Test
    fun notMeMovesWeighingToInbox() = runTest {
        val profileId = profiles.add(profile())
        val id = measurements.add(measurement(profileId))

        measurements.moveToInbox(id)

        val moved = measurements.get(id)!!
        assertNull(moved.profileId)
        assertNull(moved.person)
        assertEquals(listOf(id), measurements.observeInbox().first().map { it.id })
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

    @Test
    fun impedanceValueRepeatedThreeTimesIsAPlaceholder() = runTest {
        val bindings = ScaleBindingRepository(database, clock)
        val fake = bindings.bind("AA:BB:CC:DD:EE:01", null, "OKOK_V2", null, null)
        val real = bindings.bind("AA:BB:CC:DD:EE:02", null, "OKOK_V2", null, null)
        val profileId = profiles.add(profile())
        suspend fun weighing(scale: Long, ohms: Double) = measurements.add(
            measurement(profileId).copy(source = MeasurementSource.SCALE, scaleBindingId = scale, impedances = listOf(Impedance(0, ohms))),
        )

        // The first two weighings with a value still count as measured
        weighing(fake, 600.0)
        weighing(fake, 600.0)
        assertEquals(emptyMap<Long, Set<Double>>(), measurements.observePlaceholderImpedances().first())
        weighing(fake, 600.0)
        assertEquals(mapOf(fake to setOf(600.0)), measurements.observePlaceholderImpedances().first())

        // A rare other value does not make the constant real, and it is not a placeholder itself
        weighing(fake, 512.0)
        assertEquals(mapOf(fake to setOf(600.0)), measurements.observePlaceholderImpedances().first())

        // Two equal values in a row on a real scale hide nothing
        weighing(real, 480.0)
        weighing(real, 480.0)
        weighing(real, 476.5)
        assertEquals(mapOf(fake to setOf(600.0)), measurements.observePlaceholderImpedances().first())
    }
}
