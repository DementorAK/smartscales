package dev.kinash.smartscales.ui

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import dev.kinash.smartscales.core.ble.AdvertisementScanner
import dev.kinash.smartscales.core.ble.BluetoothLog
import dev.kinash.smartscales.core.ble.ChipseaAdvertisementDriver
import dev.kinash.smartscales.core.ble.CloudV4GattDriver
import dev.kinash.smartscales.core.ble.GattConnector
import dev.kinash.smartscales.core.ble.GattLink
import dev.kinash.smartscales.core.ble.GattNotification
import dev.kinash.smartscales.core.ble.GattSession
import dev.kinash.smartscales.core.ble.GattTransport
import dev.kinash.smartscales.core.ble.LoggingScanner
import dev.kinash.smartscales.core.ble.OkokGattDriver
import dev.kinash.smartscales.core.ble.ScaleDrivers
import dev.kinash.smartscales.core.ble.ScannedAdvertisement
import dev.kinash.smartscales.core.ble.WeighingTracker
import dev.kinash.smartscales.core.protocol.ChipseaGatt
import dev.kinash.smartscales.di.BleModule
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.receiveAsFlow
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Singleton

/** Bluetooth that delivers whatever advertisements a test sends. */
class FakeScanner : AdvertisementScanner {
    private val advertisements = MutableSharedFlow<ScannedAdvertisement>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val collectors = AtomicInteger()

    /** Number of collectors, so a test can wait until the screen listens. */
    val listeners: Int get() = collectors.get()

    override val isSupported = true

    override fun observeEnabled(): Flow<Boolean> = flowOf(true)

    override fun scan(): Flow<ScannedAdvertisement> = advertisements
        .onSubscription { collectors.incrementAndGet() }
        .onCompletion { collectors.decrementAndGet() }

    /** Sends a scale advertisement with [manufacturerData] (with the company id bytes) from [address]. */
    fun advertise(manufacturerData: ByteArray, address: String = "AA:BB:CC:00:11:22") {
        val record = byteArrayOf(2, 1, 6, (manufacturerData.size + 1).toByte(), 0xFF.toByte()) + manufacturerData
        advertisements.tryEmit(ScannedAdvertisement(address, null, -60, record, 0))
    }
}

/**
 * GATT scales for tests: every connection subscribes like the real scale, then receives [frames] and ends,
 * as a scale that goes to sleep after the weighing. What the app writes is kept in [writes].
 */
class FakeGattTransport : GattTransport {
    @Volatile
    var frames: List<GattNotification> = emptyList()

    /** The subscription after which the scale sends: the last one its protocol makes. */
    @Volatile
    var sendAfter: UUID = ChipseaGatt.BODY_COMPOSITION_MEASUREMENT

    /** Whether the scale sleeps after [frames]; a test that answers the scale keeps it awake. */
    @Volatile
    var sleepAfterFrames = true

    val writes = CopyOnWriteArrayList<Pair<UUID, ByteArray>>()
    val connections = AtomicInteger()

    override suspend fun connect(address: String): GattLink {
        connections.incrementAndGet()
        val toSend = frames
        val trigger = sendAfter
        val sleep = sleepAfterFrames
        val channel = Channel<GattNotification>(Channel.UNLIMITED)
        return object : GattLink, GattSession {
            override val session: GattSession get() = this
            override val notifications: Flow<GattNotification> = channel.receiveAsFlow()

            override fun hasCharacteristic(service: UUID, characteristic: UUID) = true

            override suspend fun subscribe(service: UUID, characteristic: UUID) {
                if (characteristic == trigger) {
                    toSend.forEach { channel.send(it) }
                    if (sleep) channel.close()
                }
            }

            override suspend fun write(service: UUID, characteristic: UUID, value: ByteArray, withResponse: Boolean) {
                writes += characteristic to value
            }

            override suspend fun release() {
                channel.close()
            }
        }
    }
}

@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [BleModule::class])
object TestBleModule {
    @Provides
    @Singleton
    fun fake(): FakeScanner = FakeScanner()

    /** Through the log, as in the app; Robolectric gives each test new app folders, so the log starts empty. */
    @Provides
    @Singleton
    fun scanner(fake: FakeScanner, log: BluetoothLog, drivers: ScaleDrivers): AdvertisementScanner =
        LoggingScanner(fake, log, drivers)

    @Provides
    @Singleton
    fun log(@ApplicationContext context: Context): BluetoothLog = BluetoothLog(File(context.filesDir, "bluetooth-log"))

    @Provides
    @Singleton
    fun drivers(): ScaleDrivers = ScaleDrivers(listOf(ChipseaAdvertisementDriver()), listOf(CloudV4GattDriver(), OkokGattDriver()))

    @Provides
    @Singleton
    fun gatt(): FakeGattTransport = FakeGattTransport()

    @Provides
    @Singleton
    fun connector(transport: FakeGattTransport, log: BluetoothLog): GattConnector = GattConnector(transport, log)

    /** One for the app: weighings finished on an earlier visit to the weighing screen stay finished. */
    @Provides
    @Singleton
    fun tracker(): WeighingTracker = WeighingTracker()
}
