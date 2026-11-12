package dev.kinash.smartscales.core.data.backup

import androidx.room.withTransaction
import dev.kinash.smartscales.core.data.db.SmartScalesDatabase
import dev.kinash.smartscales.core.data.db.entity.FormulaSetEntity
import dev.kinash.smartscales.core.data.db.entity.FormulaSetOrigin
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
import dev.kinash.smartscales.core.data.Reminder
import dev.kinash.smartscales.core.data.toEntity
import dev.kinash.smartscales.core.data.toReminder
import java.time.DayOfWeek
import java.time.LocalTime
import dev.kinash.smartscales.core.data.db.entity.ScaleBindingEntity
import dev.kinash.smartscales.core.data.db.entity.ScaleExcludedProfileEntity
import dev.kinash.smartscales.core.data.db.entity.TagEntity
import dev.kinash.smartscales.core.data.repository.SettingsRepository
import dev.kinash.smartscales.core.model.LengthUnit
import dev.kinash.smartscales.core.model.MeasurementSource
import dev.kinash.smartscales.core.model.Sex
import dev.kinash.smartscales.core.model.WeightUnit
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** A backup that does not hold together: a value that does not read, or a part pointing at one it does not have. */
class BrokenBackupException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Writes the whole database and settings into a [Backup], and replaces them with one. */
@Singleton
class BackupRepository @Inject constructor(
    private val database: SmartScalesDatabase,
    private val settings: SettingsRepository,
) {
    private val dao get() = database.backupDao()

    /** Everything stored now, read at one moment, with the app's [files]. */
    suspend fun backup(createdAt: Instant, appVersion: String, files: Map<String, String> = emptyMap()): Backup {
        val backup = database.withTransaction {
            val goals = dao.goals().groupBy { it.profileId }
            val variants = dao.mainVariants().groupBy { it.profileId }
            val kept = dao.profileManualValues().groupBy { it.profileId }
            val reminders = dao.reminders().associateBy { it.profileId }
            val excluded = dao.excludedProfiles().groupBy { it.bindingId }
            val impedances = dao.impedances().groupBy { it.measurementId }
            val manual = dao.manualValues().groupBy { it.measurementId }
            val imported = dao.importedValues().groupBy { it.measurementId }
            val tagged = dao.measurementTags().groupBy { it.measurementId }
            Backup(
                createdAt = createdAt.toString(),
                appVersion = appVersion,
                profiles = dao.profiles().map { p ->
                    ProfileBackup(
                        id = p.id, name = p.name, sex = p.sex.name, birthDate = p.birthDate.toString(), heightCm = p.heightCm,
                        weightUnit = p.weightUnit.name, lengthUnit = p.lengthUnit.name, color = p.color, avatar = p.avatar,
                        createdAt = p.createdAt.toString(), cards = p.cards,
                        goals = goals[p.id].orEmpty().map { GoalBackup(it.indicatorId, it.targetValue, it.setAt.toString()) },
                        manualValues = kept[p.id].orEmpty().associate { it.fieldId to it.value },
                        mainVariants = variants[p.id].orEmpty().associate { it.groupId to it.indicatorId },
                        reminder = reminders[p.id]?.toReminder()?.let { r ->
                            ReminderBackup(r.time.toString(), r.onSchedule, r.days.sortedBy { it.ordinal }.map { it.name }, r.onGap, r.gapDays)
                        },
                    )
                },
                scales = dao.scales().map { s ->
                    ScaleBackup(
                        id = s.id, mac = s.mac, bleAddress = s.bleAddress, name = s.name, protocol = s.protocol,
                        protocolVersion = s.protocolVersion, productId = s.productId, v2Key = s.v2Key,
                        v2KeyConfirmed = s.v2KeyConfirmed, impedanceFactor = s.impedanceFactor, unbound = s.unbound,
                        boundAt = s.boundAt.toString(), historyAcceptedUntil = s.historyAcceptedUntil?.toString(),
                        displayUnit = s.displayUnit, syncTime = s.syncTime, invertsSex = s.invertsSex,
                        excludedProfiles = excluded[s.id].orEmpty().map { it.profileId },
                    )
                },
                tags = dao.tags().map { TagBackup(it.id, it.name) },
                measurements = dao.measurements().map { m ->
                    MeasurementBackup(
                        id = m.id, profileId = m.profileId, takenAt = m.takenAt.toString(), zone = m.zone.id,
                        source = m.source.name, weightKg = m.weightKg, heartRateBpm = m.heartRateBpm,
                        heightCm = m.heightCm, sex = m.sex?.name, birthDate = m.birthDate?.toString(),
                        scaleId = m.scaleBindingId, scaleProperties = m.scaleProperties, note = m.note,
                        impedances = impedances[m.id].orEmpty().map { ImpedanceBackup(it.channel, it.frequencyIndex, it.frequencyKhz, it.ohms) },
                        manualValues = manual[m.id].orEmpty().associate { it.fieldId to it.value },
                        importedValues = imported[m.id].orEmpty().associate { it.indicatorId to it.value },
                        tags = tagged[m.id].orEmpty().map { it.tagId },
                    )
                },
                formulaSets = dao.formulaSets().map { f ->
                    FormulaSetBackup(
                        id = f.id, name = f.name, json = f.json, formatVersion = f.formatVersion, origin = f.origin.name,
                        enabled = f.enabled, position = f.position, addedAt = f.addedAt.toString(),
                    )
                },
            )
        }
        return backup.copy(settings = settings.backup(), files = files)
    }

    /**
     * Replaces everything stored with [backup], in one transaction: a backup that does not hold together leaves
     * the data as it was and throws [BrokenBackupException]. The settings follow once the data is in, outside the
     * transaction (they are in DataStore): should the app stop between the two, the data is the backup's and the
     * settings the earlier ones, which is harmless. Readers of the database get the new rows through their flows.
     */
    suspend fun restore(backup: Backup) {
        val rows = try {
            Rows.of(backup)
        } catch (e: RuntimeException) {
            throw BrokenBackupException("A value does not read: ${e.message}", e)
        }
        try {
            database.withTransaction {
                dao.clearMeasurementTags()
                dao.clearTags()
                dao.clearImpedances()
                dao.clearManualValues()
                dao.clearImportedValues()
                dao.clearMeasurements()
                dao.clearExcludedProfiles()
                dao.clearScales()
                dao.clearGoals()
                dao.clearMainVariants()
                dao.clearProfileManualValues()
                dao.clearReminders()
                dao.clearProfiles()
                dao.clearFormulaSets()

                dao.insertProfiles(rows.profiles)
                dao.insertGoals(rows.goals)
                dao.insertMainVariants(rows.mainVariants)
                dao.insertProfileManualValues(rows.profileManualValues)
                dao.insertReminders(rows.reminders)
                dao.insertScales(rows.scales)
                dao.insertExcludedProfiles(rows.excluded)
                dao.insertTags(rows.tags)
                dao.insertMeasurements(rows.measurements)
                dao.insertImpedances(rows.impedances)
                dao.insertManualValues(rows.manualValues)
                dao.insertImportedValues(rows.importedValues)
                dao.insertMeasurementTags(rows.measurementTags)
                dao.insertFormulaSets(rows.formulaSets)
            }
        } catch (e: android.database.SQLException) {
            // A part pointing at one the backup does not have, or the same id twice
            throw BrokenBackupException("The backup does not hold together: ${e.message}", e)
        }
        settings.restore(backup.settings)
    }

    /** The backup's rows for the tables, read before anything is deleted, so that a value that does not read changes nothing. */
    private class Rows(
        val profiles: List<ProfileEntity>,
        val goals: List<ProfileGoalEntity>,
        val mainVariants: List<ProfileMainVariantEntity>,
        val profileManualValues: List<ProfileManualValueEntity>,
        val reminders: List<ProfileReminderEntity>,
        val scales: List<ScaleBindingEntity>,
        val excluded: List<ScaleExcludedProfileEntity>,
        val tags: List<TagEntity>,
        val measurements: List<MeasurementEntity>,
        val impedances: List<ImpedanceEntity>,
        val manualValues: List<ManualValueEntity>,
        val importedValues: List<ImportedValueEntity>,
        val measurementTags: List<MeasurementTagEntity>,
        val formulaSets: List<FormulaSetEntity>,
    ) {
        companion object {
            fun of(b: Backup) = Rows(
                profiles = b.profiles.map { p ->
                    ProfileEntity(
                        id = p.id, name = p.name, sex = Sex.valueOf(p.sex), birthDate = LocalDate.parse(p.birthDate),
                        heightCm = p.heightCm, weightUnit = WeightUnit.valueOf(p.weightUnit), lengthUnit = LengthUnit.valueOf(p.lengthUnit),
                        color = p.color, avatar = p.avatar, createdAt = Instant.parse(p.createdAt), cards = p.cards,
                    )
                },
                goals = b.profiles.flatMap { p -> p.goals.map { ProfileGoalEntity(p.id, it.indicatorId, it.targetValue, Instant.parse(it.setAt)) } },
                mainVariants = b.profiles.flatMap { p -> p.mainVariants.map { (group, variant) -> ProfileMainVariantEntity(p.id, group, variant) } },
                profileManualValues = b.profiles.flatMap { p -> p.manualValues.map { (field, value) -> ProfileManualValueEntity(p.id, field, value) } },
                reminders = b.profiles.mapNotNull { p ->
                    p.reminder?.let { r ->
                        Reminder(
                            profileId = p.id, time = LocalTime.parse(r.time), onSchedule = r.onSchedule,
                            days = r.days.map(DayOfWeek::valueOf).toSet(), onGap = r.onGap, gapDays = r.gapDays,
                        ).toEntity()
                    }
                },
                scales = b.scales.map { s ->
                    ScaleBindingEntity(
                        id = s.id, mac = s.mac, bleAddress = s.bleAddress, name = s.name, protocol = s.protocol,
                        protocolVersion = s.protocolVersion, productId = s.productId, v2Key = s.v2Key, v2KeyConfirmed = s.v2KeyConfirmed,
                        impedanceFactor = s.impedanceFactor, unbound = s.unbound, boundAt = Instant.parse(s.boundAt),
                        historyAcceptedUntil = s.historyAcceptedUntil?.let(Instant::parse), displayUnit = s.displayUnit, syncTime = s.syncTime,
                        invertsSex = s.invertsSex,
                    )
                },
                excluded = b.scales.flatMap { s -> s.excludedProfiles.map { ScaleExcludedProfileEntity(s.id, it) } },
                tags = b.tags.map { TagEntity(id = it.id, name = it.name) },
                measurements = b.measurements.map { m ->
                    MeasurementEntity(
                        id = m.id, profileId = m.profileId, takenAt = Instant.parse(m.takenAt), zone = ZoneId.of(m.zone),
                        source = MeasurementSource.valueOf(m.source), weightKg = m.weightKg, heartRateBpm = m.heartRateBpm,
                        heightCm = m.heightCm, sex = m.sex?.let(Sex::valueOf), birthDate = m.birthDate?.let(LocalDate::parse),
                        scaleBindingId = m.scaleId, scaleProperties = m.scaleProperties, note = m.note,
                    )
                },
                impedances = b.measurements.flatMap { m -> m.impedances.map { ImpedanceEntity(m.id, it.channel, it.frequencyIndex, it.frequencyKhz, it.ohms) } },
                manualValues = b.measurements.flatMap { m -> m.manualValues.map { (field, value) -> ManualValueEntity(m.id, field, value) } },
                importedValues = b.measurements.flatMap { m -> m.importedValues.map { (id, value) -> ImportedValueEntity(m.id, id, value) } },
                measurementTags = b.measurements.flatMap { m -> m.tags.map { MeasurementTagEntity(m.id, it) } },
                formulaSets = b.formulaSets.map { f ->
                    FormulaSetEntity(
                        id = f.id, name = f.name, json = f.json, formatVersion = f.formatVersion, origin = FormulaSetOrigin.valueOf(f.origin),
                        enabled = f.enabled, position = f.position, addedAt = Instant.parse(f.addedAt),
                    )
                },
            )
        }
    }
}
