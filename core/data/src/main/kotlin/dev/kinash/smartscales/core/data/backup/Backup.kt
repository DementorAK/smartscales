package dev.kinash.smartscales.core.data.backup

import kotlinx.serialization.Serializable

/**
 * A whole phone's data, as a backup file holds it (requirements §4.3): profiles, scales with their keys, the
 * weighings' raw data, the formula sets, the settings and the app's own [files] (the WHO tables the person
 * imported). Its own schema rather than a copy of the database, so that a backup outlives the database's
 * migrations; times are ISO-8601 text, enums their names. Ids are those of the phone it was made on, and tie the
 * parts together; restoring keeps them.
 */
@Serializable
data class Backup(
    val format: String = FORMAT,
    val version: Int = VERSION,
    val createdAt: String,
    /** The app's version that wrote it, for a report of a problem. */
    val appVersion: String,
    val profiles: List<ProfileBackup> = emptyList(),
    val scales: List<ScaleBackup> = emptyList(),
    val tags: List<TagBackup> = emptyList(),
    val measurements: List<MeasurementBackup> = emptyList(),
    val formulaSets: List<FormulaSetBackup> = emptyList(),
    val settings: SettingsBackup = SettingsBackup(),
    /** Files of the app by name, such as `who_growth.csv`, as text. */
    val files: Map<String, String> = emptyMap(),
) {
    companion object {
        const val FORMAT = "smartscales-backup"

        /** The version this app writes and the newest it reads. */
        const val VERSION = 1
    }
}

@Serializable
data class ProfileBackup(
    val id: Long,
    val name: String,
    val sex: String,
    val birthDate: String,
    val heightCm: Double,
    val weightUnit: String,
    val lengthUnit: String,
    val color: Int,
    val avatar: String? = null,
    val createdAt: String,
    /** Card ids of the main screen joined by commas; null for the default set. */
    val cards: String? = null,
    val goals: List<GoalBackup> = emptyList(),
    /** Circumferences the profile keeps, in centimetres, by field id. */
    val manualValues: Map<String, Double> = emptyMap(),
    /** The variant chosen for each indicator group. */
    val mainVariants: Map<String, String> = emptyMap(),
    val reminder: ReminderBackup? = null,
)

/** A profile's reminder to weigh: days as their English names, the time as `07:30`. */
@Serializable
data class ReminderBackup(
    val time: String,
    val onSchedule: Boolean,
    val days: List<String>,
    val onGap: Boolean,
    val gapDays: Int,
)

@Serializable
data class GoalBackup(val indicatorId: String, val targetValue: Double, val setAt: String)

@Serializable
data class ScaleBackup(
    val id: Long,
    val mac: String,
    val bleAddress: String? = null,
    val name: String? = null,
    val protocol: String,
    val protocolVersion: String? = null,
    val productId: Int? = null,
    /** The scale's encryption key: a backup restores weighing on the same scale at once. */
    val v2Key: String? = null,
    val v2KeyConfirmed: Boolean = false,
    val impedanceFactor: Double = 1.0,
    val unbound: Boolean = false,
    val boundAt: String,
    val historyAcceptedUntil: String? = null,
    val displayUnit: String = "PROFILE",
    val syncTime: Boolean = true,
    val invertsSex: Boolean = false,
    /** Profiles the scale is not told about. */
    val excludedProfiles: List<Long> = emptyList(),
)

@Serializable
data class TagBackup(val id: Long, val name: String)

@Serializable
data class MeasurementBackup(
    val id: Long,
    /** Null for a weighing in the inbox. */
    val profileId: Long? = null,
    val takenAt: String,
    val zone: String,
    val source: String,
    val weightKg: Double,
    val heartRateBpm: Int? = null,
    val heightCm: Double? = null,
    val sex: String? = null,
    val birthDate: String? = null,
    val scaleId: Long? = null,
    val scaleProperties: Int? = null,
    val note: String? = null,
    val impedances: List<ImpedanceBackup> = emptyList(),
    val manualValues: Map<String, Double> = emptyMap(),
    val importedValues: Map<String, Double> = emptyMap(),
    val tags: List<Long> = emptyList(),
)

@Serializable
data class ImpedanceBackup(val channel: Int, val frequencyIndex: Int, val frequencyKhz: Int? = null, val ohms: Double)

@Serializable
data class FormulaSetBackup(
    val id: Long,
    val name: String,
    /** The set's file as stored. */
    val json: String,
    val formatVersion: Int,
    val origin: String,
    val enabled: Boolean,
    val position: Int,
    val addedAt: String,
)

@Serializable
data class SettingsBackup(
    val selectedProfileId: Long? = null,
    val matchThresholdKg: Double? = null,
    val childNorms: String? = null,
    val compareHintSeen: Boolean = false,
)
