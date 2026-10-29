package dev.kinash.smartscales.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
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

    private companion object {
        val SELECTED_PROFILE = longPreferencesKey("selected_profile")
    }
}
