package dev.kinash.smartscales.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.kinash.smartscales.core.data.db.SmartScalesDatabase
import dev.kinash.smartscales.core.model.MeasurementSource
import dev.kinash.smartscales.core.model.PersonSnapshot
import dev.kinash.smartscales.core.model.Sex
import org.junit.After
import org.junit.Before
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** In-memory database on Robolectric, recreated for every test. */
abstract class DatabaseTestBase {
    protected lateinit var database: SmartScalesDatabase

    @Before
    fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            SmartScalesDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    protected fun profile(
        name: String = "Dmitry",
        sex: Sex = Sex.MALE,
        birthDate: String = "1990-10-28",
        heightCm: Double = 180.0,
    ) = Profile(
        name = name,
        sex = sex,
        birthDate = LocalDate.parse(birthDate),
        heightCm = heightCm,
        color = 0xFF3F51B5.toInt(),
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
    )

    protected fun measurement(
        profileId: Long?,
        takenAt: String = "2026-10-27T07:00:00Z",
        weightKg: Double = 80.0,
        heightCm: Double = 180.0,
    ) = Measurement(
        profileId = profileId,
        takenAt = Instant.parse(takenAt),
        zone = ZoneId.of("Europe/Kyiv"),
        source = MeasurementSource.MANUAL,
        weightKg = weightKg,
        person = PersonSnapshot(heightCm, Sex.MALE, LocalDate.parse("1990-10-28")),
    )
}
