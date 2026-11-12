package dev.kinash.smartscales.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import dev.kinash.smartscales.core.data.db.entity.FormulaSetEntity
import dev.kinash.smartscales.core.data.db.entity.ImpedanceEntity
import dev.kinash.smartscales.core.data.db.entity.ImportedValueEntity
import dev.kinash.smartscales.core.data.db.entity.ManualValueEntity
import dev.kinash.smartscales.core.data.db.entity.MeasurementEntity
import dev.kinash.smartscales.core.data.db.entity.MeasurementTagEntity
import dev.kinash.smartscales.core.data.db.entity.ProfileEntity
import dev.kinash.smartscales.core.data.db.entity.ProfileGoalEntity
import dev.kinash.smartscales.core.data.db.entity.ProfileMainVariantEntity
import dev.kinash.smartscales.core.data.db.entity.ProfileManualValueEntity
import dev.kinash.smartscales.core.data.db.entity.ProfileReminderEntity
import dev.kinash.smartscales.core.data.db.entity.ScaleBindingEntity
import dev.kinash.smartscales.core.data.db.entity.ScaleExcludedProfileEntity
import dev.kinash.smartscales.core.data.db.entity.TagEntity

/** Every table whole, to write a backup and to replace everything with one (see `BackupRepository`). */
@Dao
interface BackupDao {
    @Query("SELECT * FROM profile ORDER BY id")
    suspend fun profiles(): List<ProfileEntity>

    @Query("SELECT * FROM profile_goal")
    suspend fun goals(): List<ProfileGoalEntity>

    @Query("SELECT * FROM profile_main_variant")
    suspend fun mainVariants(): List<ProfileMainVariantEntity>

    @Query("SELECT * FROM profile_manual_value")
    suspend fun profileManualValues(): List<ProfileManualValueEntity>

    @Query("SELECT * FROM profile_reminder")
    suspend fun reminders(): List<ProfileReminderEntity>

    @Query("SELECT * FROM scale_binding ORDER BY id")
    suspend fun scales(): List<ScaleBindingEntity>

    @Query("SELECT * FROM scale_excluded_profile")
    suspend fun excludedProfiles(): List<ScaleExcludedProfileEntity>

    @Query("SELECT * FROM measurement ORDER BY id")
    suspend fun measurements(): List<MeasurementEntity>

    @Query("SELECT * FROM impedance")
    suspend fun impedances(): List<ImpedanceEntity>

    @Query("SELECT * FROM manual_value")
    suspend fun manualValues(): List<ManualValueEntity>

    @Query("SELECT * FROM imported_value")
    suspend fun importedValues(): List<ImportedValueEntity>

    @Query("SELECT * FROM tag ORDER BY id")
    suspend fun tags(): List<TagEntity>

    @Query("SELECT * FROM measurement_tag")
    suspend fun measurementTags(): List<MeasurementTagEntity>

    @Query("SELECT * FROM formula_set ORDER BY position, id")
    suspend fun formulaSets(): List<FormulaSetEntity>

    @Insert
    suspend fun insertProfiles(rows: List<ProfileEntity>)

    @Insert
    suspend fun insertGoals(rows: List<ProfileGoalEntity>)

    @Insert
    suspend fun insertMainVariants(rows: List<ProfileMainVariantEntity>)

    @Insert
    suspend fun insertProfileManualValues(rows: List<ProfileManualValueEntity>)

    @Insert
    suspend fun insertReminders(rows: List<ProfileReminderEntity>)

    @Insert
    suspend fun insertScales(rows: List<ScaleBindingEntity>)

    @Insert
    suspend fun insertExcludedProfiles(rows: List<ScaleExcludedProfileEntity>)

    @Insert
    suspend fun insertMeasurements(rows: List<MeasurementEntity>)

    @Insert
    suspend fun insertImpedances(rows: List<ImpedanceEntity>)

    @Insert
    suspend fun insertManualValues(rows: List<ManualValueEntity>)

    @Insert
    suspend fun insertImportedValues(rows: List<ImportedValueEntity>)

    @Insert
    suspend fun insertTags(rows: List<TagEntity>)

    @Insert
    suspend fun insertMeasurementTags(rows: List<MeasurementTagEntity>)

    @Insert
    suspend fun insertFormulaSets(rows: List<FormulaSetEntity>)

    // Children before their parents, as the foreign keys restrict deleting a profile or a scale with measurements
    @Query("DELETE FROM measurement_tag")
    suspend fun clearMeasurementTags()

    @Query("DELETE FROM tag")
    suspend fun clearTags()

    @Query("DELETE FROM impedance")
    suspend fun clearImpedances()

    @Query("DELETE FROM manual_value")
    suspend fun clearManualValues()

    @Query("DELETE FROM imported_value")
    suspend fun clearImportedValues()

    @Query("DELETE FROM measurement")
    suspend fun clearMeasurements()

    @Query("DELETE FROM scale_excluded_profile")
    suspend fun clearExcludedProfiles()

    @Query("DELETE FROM scale_binding")
    suspend fun clearScales()

    @Query("DELETE FROM profile_goal")
    suspend fun clearGoals()

    @Query("DELETE FROM profile_main_variant")
    suspend fun clearMainVariants()

    @Query("DELETE FROM profile_manual_value")
    suspend fun clearProfileManualValues()

    @Query("DELETE FROM profile_reminder")
    suspend fun clearReminders()

    @Query("DELETE FROM profile")
    suspend fun clearProfiles()

    @Query("DELETE FROM formula_set")
    suspend fun clearFormulaSets()
}
