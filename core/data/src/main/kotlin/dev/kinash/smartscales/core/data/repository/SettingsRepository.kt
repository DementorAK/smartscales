package dev.kinash.smartscales.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

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

    companion object {
        const val DEFAULT_MATCH_THRESHOLD_KG = 3.0
        private val SELECTED_PROFILE = longPreferencesKey("selected_profile")
        private val MATCH_THRESHOLD = doublePreferencesKey("match_threshold_kg")
    }
}
