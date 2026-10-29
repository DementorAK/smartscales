package dev.kinash.smartscales.core.data.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.kinash.smartscales.core.data.db.SmartScalesDatabase
import java.time.Clock
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): SmartScalesDatabase =
        Room.databaseBuilder(context, SmartScalesDatabase::class.java, SmartScalesDatabase.NAME).build()

    @Provides
    fun clock(): Clock = Clock.systemUTC()
}
