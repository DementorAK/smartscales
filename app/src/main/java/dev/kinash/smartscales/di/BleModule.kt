package dev.kinash.smartscales.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.kinash.smartscales.core.ble.AdvertisementScanner
import dev.kinash.smartscales.core.ble.BleScanner
import dev.kinash.smartscales.core.ble.BluetoothLog
import dev.kinash.smartscales.core.ble.ChipseaAdvertisementDriver
import dev.kinash.smartscales.core.ble.CloudV4GattDriver
import dev.kinash.smartscales.core.ble.GattConnector
import dev.kinash.smartscales.core.ble.LoggingScanner
import dev.kinash.smartscales.core.ble.OkokCloudGattDriver
import dev.kinash.smartscales.core.ble.OkokGattDriver
import dev.kinash.smartscales.core.ble.NordicGattTransport
import dev.kinash.smartscales.core.ble.ScaleDrivers
import dev.kinash.smartscales.core.ble.WeighingTracker
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object BleModule {
    /** Every scan in the app goes through the log, which records only while switched on. */
    @Provides
    @Singleton
    fun scanner(@ApplicationContext context: Context, log: BluetoothLog, drivers: ScaleDrivers): AdvertisementScanner =
        LoggingScanner(BleScanner(context), log, drivers)

    @Provides
    @Singleton
    fun log(@ApplicationContext context: Context): BluetoothLog = BluetoothLog(File(context.filesDir, "bluetooth-log"))

    @Provides
    @Singleton
    fun drivers(): ScaleDrivers = ScaleDrivers(listOf(ChipseaAdvertisementDriver()), listOf(CloudV4GattDriver(), OkokGattDriver(), OkokCloudGattDriver()))

    /** GATT traffic goes to the same log as advertisements. */
    @Provides
    @Singleton
    fun connector(@ApplicationContext context: Context, log: BluetoothLog): GattConnector =
        GattConnector(NordicGattTransport(context), log)

    /** One for the app: weighings finished on an earlier visit to the weighing screen stay finished. */
    @Provides
    @Singleton
    fun tracker(): WeighingTracker = WeighingTracker()
}
