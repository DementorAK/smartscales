package dev.kinash.smartscales.core.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import dev.kinash.smartscales.core.data.backup.Backup
import dev.kinash.smartscales.core.data.backup.BackupCodec
import dev.kinash.smartscales.core.data.backup.BackupRead
import dev.kinash.smartscales.core.data.backup.BackupRepository
import dev.kinash.smartscales.core.data.backup.BrokenBackupException
import dev.kinash.smartscales.core.data.backup.FormulaSetBackup
import dev.kinash.smartscales.core.data.backup.GoalBackup
import dev.kinash.smartscales.core.data.backup.ImpedanceBackup
import dev.kinash.smartscales.core.data.backup.MeasurementBackup
import dev.kinash.smartscales.core.data.backup.ProfileBackup
import dev.kinash.smartscales.core.data.backup.ScaleBackup
import dev.kinash.smartscales.core.data.backup.SettingsBackup
import dev.kinash.smartscales.core.data.backup.TagBackup
import dev.kinash.smartscales.core.data.repository.MeasurementRepository
import dev.kinash.smartscales.core.data.repository.ProfileRepository
import dev.kinash.smartscales.core.data.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Instant
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class BackupRepositoryTest : DatabaseTestBase() {
    private val settings by lazy {
        val context = ApplicationProvider.getApplicationContext<Context>()
        SettingsRepository(PreferenceDataStoreFactory.create { File(context.filesDir, "settings-${UUID.randomUUID()}.preferences_pb") })
    }
    private val backups by lazy { BackupRepository(database, settings) }
    private val profiles by lazy { ProfileRepository(database, clock) }
    private val measurements by lazy { MeasurementRepository(database) }

    @Test
    fun aBackupRestoredAndWrittenAgainIsTheSame() = runTest {
        backups.restore(FULL)
        val again = backups.backup(Instant.parse(FULL.createdAt), FULL.appVersion, FULL.files)
        assertEquals(FULL, again)
        assertEquals(2L, settings.selectedProfileId.first())
    }

    @Test
    fun restoringReplacesWhatWasThere() = runTest {
        val old = profiles.add(profile(name = "Old"))
        measurements.add(measurement(old))
        settings.setMatchThreshold(7.0)
        backups.restore(FULL)
        assertEquals(listOf("Dmitry", "Nadia"), profiles.observeProfiles().first().map { it.name })
        assertEquals(listOf(10L, 11L, 12L), database.backupDao().measurements().map { it.id })
        // A setting the backup has replaces the one there was
        assertEquals(2.5, settings.matchThresholdKg.first(), 0.0)
    }

    @Test
    fun aBackupThatDoesNotHoldTogetherChangesNothing() = runTest {
        val old = profiles.add(profile(name = "Old"))
        measurements.add(measurement(old))
        // A weighing of a profile the backup does not have
        val broken = FULL.copy(measurements = FULL.measurements + FULL.measurements.first().copy(id = 99, profileId = 42))
        try {
            backups.restore(broken)
            fail("restored")
        } catch (e: BrokenBackupException) {
            // expected
        }
        assertEquals(listOf("Old"), profiles.observeProfiles().first().map { it.name })
        assertEquals(1, database.backupDao().measurements().size)
        // A value that does not read is found before anything is deleted
        try {
            backups.restore(FULL.copy(profiles = FULL.profiles.map { it.copy(sex = "UNKNOWN") }))
            fail("restored")
        } catch (e: BrokenBackupException) {
            // expected
        }
        assertEquals(listOf("Old"), profiles.observeProfiles().first().map { it.name })
    }

    @Test
    fun theFileReadsBackWithAndWithoutAPassword() {
        assertEquals(BackupRead.Ok(FULL), BackupCodec.decode(BackupCodec.encode(FULL)))
        val password = "correct horse".toCharArray()
        val locked = BackupCodec.encode(FULL, password.copyOf(), iterations = 1_000)
        // The data is not readable without the password
        assertTrue("Dmitry" !in locked.decodeToString())
        assertEquals(BackupRead.NeedsPassword, BackupCodec.decode(locked))
        assertEquals(BackupRead.WrongPassword, BackupCodec.decode(locked, "wrong".toCharArray()))
        assertEquals(BackupRead.Ok(FULL), BackupCodec.decode(locked, password))
        // A changed byte of the data is caught by GCM, as a wrong password would be
        val text = locked.decodeToString()
        val at = text.indexOf("\"data\"") + 12
        val changed = text.substring(0, at) + (if (text[at] == 'A') 'B' else 'A') + text.substring(at + 1)
        assertEquals(BackupRead.WrongPassword, BackupCodec.decode(changed.toByteArray(), password))
    }

    @Test
    fun otherFilesAreNotBackups() {
        assertEquals(BackupRead.NotABackup, BackupCodec.decode("not json".toByteArray()))
        assertEquals(BackupRead.NotABackup, BackupCodec.decode("""{"format": 1, "id": "frame"}""".toByteArray()))
        assertEquals(BackupRead.TooNew(2), BackupCodec.decode("""{"format": "smartscales-backup", "version": 2}""".toByteArray()))
        assertEquals(BackupRead.NotABackup, BackupCodec.decode("""{"format": "smartscales-backup", "version": 1}""".toByteArray()))
    }

    private companion object {
        val FULL = Backup(
            createdAt = "2026-11-09T10:00:00Z",
            appVersion = "0.8.0",
            profiles = listOf(
                ProfileBackup(
                    id = 2, name = "Dmitry", sex = "MALE", birthDate = "1990-10-28", heightCm = 180.0, weightUnit = "KILOGRAM",
                    lengthUnit = "CENTIMETRE", color = -12627531, avatar = "🙂", createdAt = "2026-01-01T00:00:00Z", cards = "bmi,bf_mine",
                    goals = listOf(GoalBackup("weight", 78.0, "2026-10-01T00:00:00Z")),
                    manualValues = mapOf("waist" to 84.5), mainVariants = mapOf("bf" to "bf_mine"),
                ),
                ProfileBackup(
                    id = 5, name = "Nadia", sex = "FEMALE", birthDate = "2015-03-15", heightCm = 140.0, weightUnit = "POUND",
                    lengthUnit = "INCH", color = 0, createdAt = "2026-01-02T00:00:00Z",
                ),
            ),
            scales = listOf(
                ScaleBackup(
                    id = 3, mac = "AA:BB:CC:00:11:22", name = "Chipsea", protocol = "OKOK_V2", v2Key = "00112233445566778899aabbccddeeff",
                    v2KeyConfirmed = true, impedanceFactor = 1.05, boundAt = "2026-02-01T00:00:00Z", excludedProfiles = listOf(5),
                ),
            ),
            tags = listOf(TagBackup(1, "утро"), TagBackup(4, "after training")),
            measurements = listOf(
                MeasurementBackup(
                    id = 10, profileId = 2, takenAt = "2026-11-01T06:00:00Z", zone = "Europe/Kyiv", source = "SCALE", weightKg = 83.0,
                    heightCm = 180.0, sex = "MALE", birthDate = "1990-10-28", scaleId = 3, scaleProperties = 1, note = "morning",
                    impedances = listOf(ImpedanceBackup(0, 0, 50, 480.5)), manualValues = mapOf("waist" to 84.5, "wrist" to 17.5),
                    tags = listOf(1, 4),
                ),
                MeasurementBackup(
                    id = 11, profileId = 5, takenAt = "2026-11-02T06:00:00Z", zone = "Europe/Kyiv", source = "IMPORT", weightKg = 35.0,
                    heightCm = 140.0, sex = "FEMALE", birthDate = "2015-03-15", importedValues = mapOf("bf" to 18.0),
                ),
                // In the inbox: no profile and no person yet
                MeasurementBackup(id = 12, takenAt = "2026-11-03T06:00:00Z", zone = "UTC", source = "SCALE", weightKg = 60.0, scaleId = 3),
            ),
            formulaSets = listOf(
                FormulaSetBackup(7, "Test", """{"format": 1, "id": "my_formulas", "name": {"en": "Test"}}""", 1, "IMPORTED", false, 1, "2026-11-05T00:00:00Z"),
            ),
            settings = SettingsBackup(selectedProfileId = 2, matchThresholdKg = 2.5, childNorms = "CDC", compareHintSeen = true),
            files = mapOf("who_growth.csv" to "indicator,sex\nbmi,1\n"),
        )
    }
}
