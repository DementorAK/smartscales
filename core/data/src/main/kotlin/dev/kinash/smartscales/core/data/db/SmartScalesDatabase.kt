package dev.kinash.smartscales.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import dev.kinash.smartscales.core.data.db.dao.FormulaSetDao
import dev.kinash.smartscales.core.data.db.dao.MeasurementDao
import dev.kinash.smartscales.core.data.db.dao.ProfileDao
import dev.kinash.smartscales.core.data.db.dao.ScaleBindingDao
import dev.kinash.smartscales.core.data.db.entity.FormulaSetEntity
import dev.kinash.smartscales.core.data.db.entity.ImpedanceEntity
import dev.kinash.smartscales.core.data.db.entity.ImportedValueEntity
import dev.kinash.smartscales.core.data.db.entity.ManualValueEntity
import dev.kinash.smartscales.core.data.db.entity.MeasurementEntity
import dev.kinash.smartscales.core.data.db.entity.MeasurementTagEntity
import dev.kinash.smartscales.core.data.db.entity.ProfileEntity
import dev.kinash.smartscales.core.data.db.entity.ProfileGoalEntity
import dev.kinash.smartscales.core.data.db.entity.ProfileMainVariantEntity
import dev.kinash.smartscales.core.data.db.entity.ScaleBindingEntity
import dev.kinash.smartscales.core.data.db.entity.TagEntity

/** Schemas are exported to `core/data/schemas`; every version change needs a migration with a test. */
@Database(
    entities = [
        ProfileEntity::class,
        ProfileGoalEntity::class,
        ProfileMainVariantEntity::class,
        ScaleBindingEntity::class,
        MeasurementEntity::class,
        ImpedanceEntity::class,
        ManualValueEntity::class,
        ImportedValueEntity::class,
        TagEntity::class,
        MeasurementTagEntity::class,
        FormulaSetEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class SmartScalesDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao

    abstract fun measurementDao(): MeasurementDao

    abstract fun scaleBindingDao(): ScaleBindingDao

    abstract fun formulaSetDao(): FormulaSetDao

    companion object {
        const val NAME = "smartscales.db"
    }
}
