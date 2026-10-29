package dev.kinash.smartscales.core.data

import android.database.sqlite.SQLiteConstraintException
import dev.kinash.smartscales.core.data.db.entity.ProfileGoalEntity
import dev.kinash.smartscales.core.data.db.entity.ProfileMainVariantEntity
import dev.kinash.smartscales.core.data.repository.MeasurementRepository
import dev.kinash.smartscales.core.data.repository.ProfileRepository
import dev.kinash.smartscales.core.model.PersonSnapshot
import dev.kinash.smartscales.core.model.Sex
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class ProfileRepositoryTest : DatabaseTestBase() {

    private val profiles by lazy { ProfileRepository(database, clock) }
    private val measurements by lazy { MeasurementRepository(database) }

    @Test
    fun applyToPastMeasurementsReplacesSexAndBirthDateButNotHeight() = runTest {
        val stored = profile()
        val profileId = profiles.add(stored)
        val id = measurements.add(measurement(profileId, heightCm = 178.0))
        val edited = stored.copy(id = profileId, sex = Sex.FEMALE, birthDate = LocalDate.parse("1991-01-01"))
        assertTrue(ProfileRepository.changesPastSnapshots(stored.copy(id = profileId), edited))

        profiles.update(edited, applyToPastMeasurements = true)

        assertEquals(PersonSnapshot(178.0, Sex.FEMALE, LocalDate.parse("1991-01-01")), measurements.get(id)!!.person)
    }

    @Test
    fun withoutApplyPastMeasurementsStayAsTheyWere() = runTest {
        val profileId = profiles.add(profile())
        val id = measurements.add(measurement(profileId))
        val before = measurements.get(id)

        profiles.update(profiles.get(profileId)!!.copy(sex = Sex.FEMALE), applyToPastMeasurements = false)

        assertEquals(before, measurements.get(id))
        assertEquals(Sex.FEMALE, profiles.get(profileId)!!.sex)
    }

    @Test
    fun applyToPastMeasurementsTouchesOnlyThisProfile() = runTest {
        val profileId = profiles.add(profile())
        val otherId = profiles.add(profile(name = "Anna"))
        val other = measurements.add(measurement(otherId))

        profiles.update(profiles.get(profileId)!!.copy(sex = Sex.FEMALE), applyToPastMeasurements = true)

        assertEquals(Sex.MALE, measurements.get(other)!!.person!!.sex)
    }

    @Test
    fun measurementsBeforeDateAreCountedByLocalDate() = runTest {
        val profileId = profiles.add(profile())
        // 22:30 UTC is already the next day in Kyiv
        measurements.add(measurement(profileId, takenAt = "2026-10-27T22:30:00Z"))

        assertEquals(0, profiles.countMeasurementsBefore(profileId, LocalDate.parse("2026-10-28")))
        assertEquals(1, profiles.countMeasurementsBefore(profileId, LocalDate.parse("2026-10-29")))
    }

    @Test
    fun applyToPastRefusesBirthDateAfterPastMeasurements() = runTest {
        val profileId = profiles.add(profile())
        val id = measurements.add(measurement(profileId, takenAt = "2026-10-27T07:00:00Z"))
        val edited = profiles.get(profileId)!!.copy(birthDate = LocalDate.parse("2026-10-28"))

        val error = runCatching { profiles.update(edited, applyToPastMeasurements = true) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertEquals(LocalDate.parse("1990-10-28"), profiles.get(profileId)!!.birthDate)
        assertEquals(LocalDate.parse("1990-10-28"), measurements.get(id)!!.person!!.birthDate)
    }

    @Test
    fun heightOrNameChangeDoesNotAsk() {
        val stored = profile()
        assertFalse(ProfileRepository.changesPastSnapshots(stored, stored.copy(name = "D", heightCm = 181.0)))
    }

    @Test
    fun deletingProfileDeletesItsData() = runTest {
        val profileId = profiles.add(profile())
        val id = measurements.add(measurement(profileId).copy(tags = listOf("morning")))
        val dao = database.profileDao()
        dao.upsertGoal(ProfileGoalEntity(profileId, "weight", 75.0, Instant.parse("2026-10-01T00:00:00Z")))
        dao.upsertMainVariant(ProfileMainVariantEntity(profileId, "ffm", "ffm_wu"))

        assertEquals(1, profiles.countMeasurements(profileId))

        profiles.delete(profileId)

        assertNull(profiles.get(profileId))
        assertNull(measurements.get(id))
        assertEquals(emptyList<ProfileGoalEntity>(), dao.observeGoals(profileId).first())
        assertEquals(emptyList<ProfileMainVariantEntity>(), dao.observeMainVariants(profileId).first())
        // Tags are shared between profiles and stay
        assertEquals(listOf("morning"), measurements.observeTags().first())
    }

    @Test(expected = SQLiteConstraintException::class)
    fun databaseRefusesToDropProfileWithHistory() = runTest {
        // Only ProfileRepository.delete removes the history, never a cascade
        val profileId = profiles.add(profile())
        measurements.add(measurement(profileId))

        database.profileDao().delete(profileId)
    }

    @Test
    fun weightGoalIsSetAndCleared() = runTest {
        val profileId = profiles.add(profile())
        assertNull(profiles.getGoal(profileId, ProfileRepository.WEIGHT_GOAL))

        profiles.setGoal(profileId, ProfileRepository.WEIGHT_GOAL, 75.0)
        assertEquals(75.0, profiles.getGoal(profileId, ProfileRepository.WEIGHT_GOAL)!!, 0.0)

        profiles.setGoal(profileId, ProfileRepository.WEIGHT_GOAL, null)
        assertNull(profiles.getGoal(profileId, ProfileRepository.WEIGHT_GOAL))
    }

    @Test
    fun mainVariantIsOnePerGroup() = runTest {
        val profileId = profiles.add(profile())
        val dao = database.profileDao()
        dao.upsertMainVariant(ProfileMainVariantEntity(profileId, "ffm", "ffm_wu"))
        dao.upsertMainVariant(ProfileMainVariantEntity(profileId, "ffm", "ffm_sun"))

        assertEquals(listOf(ProfileMainVariantEntity(profileId, "ffm", "ffm_sun")), dao.observeMainVariants(profileId).first())
    }
}
