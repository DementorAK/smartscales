package dev.kinash.smartscales.core.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.platform.app.InstrumentationRegistry
import dev.kinash.smartscales.core.data.db.SmartScalesDatabase
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
    fun version1MatchesEntities() {
        helper.createDatabase(1).close()
        helper.runMigrationsAndValidate(1).close()
    }
}
