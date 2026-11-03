package dev.kinash.smartscales.core.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.platform.app.InstrumentationRegistry
import dev.kinash.smartscales.core.data.db.MIGRATIONS
import dev.kinash.smartscales.core.data.db.SmartScalesDatabase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Exported schemas in `core/data/schemas` must match the entities; add a migration test for every new version. */
@RunWith(RobolectricTestRunner::class)
class SchemaTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @get:Rule
    val helper = MigrationTestHelper(
        instrumentation = instrumentation,
        file = instrumentation.targetContext.getDatabasePath("schema-test.db"),
        driver = AndroidSQLiteDriver(),
        databaseClass = SmartScalesDatabase::class,
    )

    @Test
    fun latestVersionMatchesEntities() {
        helper.createDatabase(3).close()
        helper.runMigrationsAndValidate(3).close()
    }

    @Test
    fun migration2To3AddsProfileCircumferencesAndKeepsProfiles() {
        helper.createDatabase(2).use { db ->
            db.execSQL(
                """
                INSERT INTO profile (name, sex, birthDate, heightCm, weightUnit, lengthUnit, color, createdAt)
                VALUES ('Dmitry', 'MALE', '1990-10-28', 180.0, 'KILOGRAM', 'CENTIMETRE', 0, 0)
                """,
            )
        }
        helper.runMigrationsAndValidate(3, MIGRATIONS.toList()).use { db ->
            db.prepare("SELECT COUNT(*) FROM profile").use {
                it.step()
                assertEquals(1L, it.getLong(0))
            }
            db.execSQL("INSERT INTO profile_manual_value (profileId, fieldId, value) VALUES (1, 'waist', 85.0)")
        }
    }

    @Test
    fun migration1To2KeepsBindingsWithKeyUnconfirmed() {
        helper.createDatabase(1).use { it.insertBinding() }

        helper.runMigrationsAndValidate(2, MIGRATIONS.toList()).use { db ->
            db.prepare("SELECT mac, v2Key, v2KeyConfirmed FROM scale_binding").use {
                it.step()
                assertEquals("AA:BB:CC:DD:EE:FF", it.getText(0))
                assertEquals("12345678", it.getText(1))
                assertEquals(0L, it.getLong(2))
            }
        }
    }

    @Test
    fun migration1To2AcceptsColumnFromDevelopmentBuilds() {
        // Development builds of 0.3 created the table with the column, without a default value
        helper.createDatabase(1).use { db ->
            db.execSQL("DROP TABLE scale_binding")
            db.execSQL(
                """
                CREATE TABLE `scale_binding` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `mac` TEXT NOT NULL,
                `bleAddress` TEXT, `name` TEXT, `protocol` TEXT NOT NULL, `protocolVersion` TEXT, `productId` INTEGER,
                `v2Key` TEXT, `v2KeyConfirmed` INTEGER NOT NULL, `impedanceFactor` REAL NOT NULL,
                `unbound` INTEGER NOT NULL, `boundAt` INTEGER NOT NULL)
                """,
            )
            db.execSQL("CREATE UNIQUE INDEX `index_scale_binding_mac` ON `scale_binding` (`mac`)")
            db.execSQL(
                """
                INSERT INTO scale_binding (mac, protocol, v2Key, v2KeyConfirmed, impedanceFactor, unbound, boundAt)
                VALUES ('AA:BB:CC:DD:EE:FF', 'OKOK_V2', '12345678', 1, 1.0, 0, 0)
                """,
            )
        }

        helper.runMigrationsAndValidate(2, MIGRATIONS.toList()).close()
    }

    private fun SQLiteConnection.insertBinding() = execSQL(
        """
        INSERT INTO scale_binding (mac, protocol, v2Key, impedanceFactor, unbound, boundAt)
        VALUES ('AA:BB:CC:DD:EE:FF', 'OKOK_V2', '12345678', 1.0, 0, 0)
        """,
    )
}
