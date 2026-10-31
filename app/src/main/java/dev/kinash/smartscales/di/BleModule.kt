package dev.kinash.smartscales.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.kinash.smartscales.core.ble.AdvertisementScanner
import dev.kinash.smartscales.core.ble.BleScanner
import dev.kinash.smartscales.core.ble.ChipseaAdvertisementDriver
import dev.kinash.smartscales.core.ble.ScaleDrivers
import dev.kinash.smartscales.core.ble.WeighingTracker
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object BleModule {
    @Provides
    @Singleton
    fun scanner(@ApplicationContext context: Context): AdvertisementScanner = BleScanner(context)

    @Provides
    @Singleton
    fun drivers(): ScaleDrivers = ScaleDrivers(listOf(ChipseaAdvertisementDriver()))

    /** One for the app: weighings finished on an earlier visit to the weighing screen stay finished. */
    @Provides
    @Singleton
    fun tracker(): WeighingTracker = WeighingTracker()
}
