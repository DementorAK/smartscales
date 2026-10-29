package dev.kinash.smartscales.ui

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import dev.kinash.smartscales.core.data.db.SmartScalesDatabase
import dev.kinash.smartscales.core.data.di.DataModule
import java.io.File
import java.time.Clock
import javax.inject.Singleton

/** In-memory database and a settings file of its own for every test. */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [DataModule::class])
object TestDataModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): SmartScalesDatabase =
        Room.inMemoryDatabaseBuilder(context, SmartScalesDatabase::class.java).allowMainThreadQueries().build()

    @Provides
    @Singleton
    fun settings(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create {
            File.createTempFile("settings", ".preferences_pb", context.cacheDir).also { it.delete() }
        }

    @Provides
    fun clock(): Clock = Clock.systemUTC()
}
