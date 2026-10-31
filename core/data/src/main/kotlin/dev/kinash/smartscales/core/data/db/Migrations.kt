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

/** All migrations, for the database builder and the migration tests. */
val MIGRATIONS = arrayOf(MIGRATION_1_2)

private fun SQLiteConnection.hasColumn(table: String, column: String): Boolean =
    prepare("PRAGMA table_info(`$table`)").use { statement ->
        val nameIndex = statement.getColumnNames().indexOf("name")
        generateSequence { statement.step().takeIf { it } }.any { statement.getText(nameIndex) == column }
    }
