package dev.kinash.smartscales.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.kinash.smartscales.core.data.backup.SettingsBackup
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** The growth charts children are placed on (requirements §3). */
enum class ChildNorms {
    /** CDC 2000, built in. */
    CDC,

    /** The WHO tables the person imported. */
    WHO,
}

/** App settings that are not part of the measurement history. */
@Singleton
class SettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    /** Profile shown on the main screen; may point to a deleted profile, callers fall back to another one. */
    val selectedProfileId: Flow<Long?> = dataStore.data.map { it[SELECTED_PROFILE] }

    suspend fun selectProfile(id: Long) {
        dataStore.edit { it[SELECTED_PROFILE] = id }
    }

    /**
     * A weighing goes straight to a profile when it is the only one whose last weight is within this many
     * kilograms; otherwise the app asks whose weighing it is.
     */
    val matchThresholdKg: Flow<Double> = dataStore.data.map { it[MATCH_THRESHOLD] ?: DEFAULT_MATCH_THRESHOLD_KG }

    suspend fun setMatchThreshold(kg: Double) {
        require(kg > 0) { "Threshold must be positive: $kg" }
        dataStore.edit { it[MATCH_THRESHOLD] = kg }
    }

    /** The history has told how to compare two weighings, and the person has done it or closed the hint. */
    val compareHintSeen: Flow<Boolean> = dataStore.data.map { it[COMPARE_HINT_SEEN] ?: false }

    suspend fun setCompareHintSeen() {
        dataStore.edit { it[COMPARE_HINT_SEEN] = true }
    }

    /** The person's choice of children's norms; null until chosen, which means WHO once its tables are imported. */
    val childNorms: Flow<ChildNorms?> = dataStore.data.map { prefs -> prefs[CHILD_NORMS]?.let { name -> ChildNorms.entries.firstOrNull { it.name == name } } }

    suspend fun setChildNorms(norms: ChildNorms) {
        dataStore.edit { it[CHILD_NORMS] = norms.name }
    }

    /** The settings as a backup keeps them. */
    suspend fun backup(): SettingsBackup = dataStore.data.first().let { prefs ->
        SettingsBackup(
            selectedProfileId = prefs[SELECTED_PROFILE],
            matchThresholdKg = prefs[MATCH_THRESHOLD],
            childNorms = prefs[CHILD_NORMS],
            compareHintSeen = prefs[COMPARE_HINT_SEEN] ?: false,
        )
    }

    /** Replaces the settings with those of a backup; one it does not have goes back to its default. */
    suspend fun restore(settings: SettingsBackup) {
        dataStore.edit { prefs ->
            prefs.clear()
            settings.selectedProfileId?.let { prefs[SELECTED_PROFILE] = it }
            settings.matchThresholdKg?.takeIf { it > 0 }?.let { prefs[MATCH_THRESHOLD] = it }
            settings.childNorms?.takeIf { name -> ChildNorms.entries.any { it.name == name } }?.let { prefs[CHILD_NORMS] = it }
            if (settings.compareHintSeen) prefs[COMPARE_HINT_SEEN] = true
        }
    }

    companion object {
        const val DEFAULT_MATCH_THRESHOLD_KG = 3.0
        private val CHILD_NORMS = stringPreferencesKey("child_norms")
        private val SELECTED_PROFILE = longPreferencesKey("selected_profile")
        private val MATCH_THRESHOLD = doublePreferencesKey("match_threshold_kg")
        private val COMPARE_HINT_SEEN = booleanPreferencesKey("compare_hint_seen")
    }
}
