package dev.kinash.smartscales.core.data.db

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * 1 → 2: `scale_binding.v2KeyConfirmed`. Development builds of 0.3 had this column in a changed version 1,
 * without a default value, so it is added only when missing. The entity declares no default either: Room
 * then accepts the column with and without one, and the table needs no rebuild (a rebuild would drop a
 * table that measurements reference).
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(connection: SQLiteConnection) {
        if (!connection.hasColumn("scale_binding", "v2KeyConfirmed")) {
            connection.execSQL("ALTER TABLE `scale_binding` ADD COLUMN `v2KeyConfirmed` INTEGER NOT NULL DEFAULT 0")
        }
    }
}

/** 2 → 3: `profile_manual_value`, the circumferences a profile keeps for its weighings. */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `profile_manual_value` (`profileId` INTEGER NOT NULL, `fieldId` TEXT NOT NULL, " +
                "`value` REAL NOT NULL, PRIMARY KEY(`profileId`, `fieldId`), " +
                "FOREIGN KEY(`profileId`) REFERENCES `profile`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
    }
}

/** 3 → 4: `scale_binding.historyAcceptedUntil`, so records from a scale's memory are taken once. */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE `scale_binding` ADD COLUMN `historyAcceptedUntil` INTEGER")
    }
}

/** 4 → 5: driver settings of a scale: the unit on its display, its clock, and the profiles it is not told about. */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE `scale_binding` ADD COLUMN `displayUnit` TEXT NOT NULL DEFAULT 'PROFILE'")
        connection.execSQL("ALTER TABLE `scale_binding` ADD COLUMN `syncTime` INTEGER NOT NULL DEFAULT 1")
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `scale_excluded_profile` (`bindingId` INTEGER NOT NULL, `profileId` INTEGER NOT NULL, " +
                "PRIMARY KEY(`bindingId`, `profileId`), " +
                "FOREIGN KEY(`bindingId`) REFERENCES `scale_binding`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
                "FOREIGN KEY(`profileId`) REFERENCES `profile`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_scale_excluded_profile_profileId` ON `scale_excluded_profile` (`profileId`)",
        )
    }
}

/** 5 → 6: `profile.cards`, the cards a profile chose for the main screen; null keeps the default set. */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE `profile` ADD COLUMN `cards` TEXT")
    }
}

/** OKOK v1.0 scales that want the opposite sex, which no scale is known to be yet. */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE `scale_binding` ADD COLUMN `invertsSex` INTEGER NOT NULL DEFAULT 0")
    }
}

/** All migrations, for the database builder and the migration tests. */
val MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)

private fun SQLiteConnection.hasColumn(table: String, column: String): Boolean =
    prepare("PRAGMA table_info(`$table`)").use { statement ->
        val nameIndex = statement.getColumnNames().indexOf("name")
        generateSequence { statement.step().takeIf { it } }.any { statement.getText(nameIndex) == column }
    }
