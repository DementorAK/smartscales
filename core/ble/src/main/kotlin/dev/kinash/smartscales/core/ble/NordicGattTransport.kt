package dev.kinash.smartscales.core.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.content.Context
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.Request
import no.nordicsemi.android.ble.callback.FailCallback
import no.nordicsemi.android.ble.observer.ConnectionObserver
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Connections over Android GATT through the Nordic Android-BLE-Library, which queues the operations, waits for
 * each callback and times them out. The caller holds the `BLUETOOTH_CONNECT` permission on Android 12+.
 */
class NordicGattTransport(private val context: Context) : GattTransport {
    @SuppressLint("MissingPermission")
    override suspend fun connect(address: String): GattLink {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: throw GattException("Bluetooth LE is not available", retryable = false)
        if (!adapter.isEnabled) throw GattException("Bluetooth is off", retryable = false)
        val device = gattCall { adapter.getRemoteDevice(address) }
        val manager = ScaleBleManager(context)
        try {
            // The library tries again itself before giving up: the first connection to a scale that has just woken
            // often fails with status 133
            val request = gattCall {
                manager.connect(device)
                    .useAutoConnect(false)
                    .retry(CONNECT_RETRIES, CONNECT_RETRY_DELAY_MS)
                    .timeout(CONNECT_TIMEOUT_MS)
            }
            request.await { request.cancelPendingConnection() }
        } catch (e: Throwable) {
            // Cancelled too (the person left the screen, the scale stopped advertising): a connection nobody owns
            // would keep the scale, which takes only one, from everyone else
            manager.close()
            throw e
        }
        return manager
    }

    private class ScaleBleManager(context: Context) : BleManager(context), GattLink, GattSession {
        private var gatt: BluetoothGatt? = null

        /** Unlimited, so notifications that arrive while the protocol is still starting wait for the reader. */
        private val received = Channel<GattNotification>(Channel.UNLIMITED)

        /** Set once connected and set up; a disconnection between the library's own connection tries is not an end. */
        @Volatile
        private var ready = false

        init {
            setConnectionObserver(object : ConnectionObserver {
                override fun onDeviceConnecting(device: BluetoothDevice) = Unit
                override fun onDeviceConnected(device: BluetoothDevice) = Unit
                override fun onDeviceFailedToConnect(device: BluetoothDevice, reason: Int) = Unit
                override fun onDeviceReady(device: BluetoothDevice) {
                    ready = true
                }
                override fun onDeviceDisconnecting(device: BluetoothDevice) = Unit
                override fun onDeviceDisconnected(device: BluetoothDevice, reason: Int) {
                    if (ready) received.close()
                }
            })
        }

        override val session: GattSession get() = this

        override val notifications: Flow<GattNotification> = received.receiveAsFlow()

        /** Every service is accepted here: the protocol checks what it needs. */
        override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
            this.gatt = gatt
            return true
        }

        override fun onServicesInvalidated() {
            gatt = null
        }

        override fun hasCharacteristic(service: UUID, characteristic: UUID): Boolean =
            characteristic(service, characteristic) != null

        override suspend fun subscribe(service: UUID, characteristic: UUID) {
            val target = require(service, characteristic)
            setNotificationCallback(target).with { _, data ->
                data.value?.let { received.trySend(GattNotification(characteristic, it)) }
            }
            val request = if (target.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) {
                enableIndications(target)
            } else {
                enableNotifications(target)
            }
            gattCall { request.timeout(OPERATION_TIMEOUT_MS) }.await { cancelQueue() }
        }

        override suspend fun write(service: UUID, characteristic: UUID, value: ByteArray, withResponse: Boolean) {
            val target = require(service, characteristic)
            // A characteristic that takes writes only without response gets them so, whatever was asked
            val canRespond = target.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
            val type = if (withResponse && canRespond) {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            }
            gattCall { writeCharacteristic(target, value, type).timeout(OPERATION_TIMEOUT_MS) }
                .await { cancelQueue() }
        }

        override suspend fun release() {
            try {
                if (isConnected) gattCall { disconnect() }.await { cancelQueue() }
            } catch (_: GattException) {
                // Already gone
            } finally {
                close()
                received.close()
            }
        }

        private fun characteristic(service: UUID, characteristic: UUID): BluetoothGattCharacteristic? =
            gatt?.getService(service)?.getCharacteristic(characteristic)

        private fun require(service: UUID, characteristic: UUID): BluetoothGattCharacteristic =
            characteristic(service, characteristic) ?: throw GattException("The scale has no characteristic $characteristic")
    }

    private companion object {
        const val CONNECT_RETRIES = 3
        const val CONNECT_RETRY_DELAY_MS = 300
        const val CONNECT_TIMEOUT_MS = 15_000L
        const val OPERATION_TIMEOUT_MS = 5_000L
    }
}

/**
 * Runs the request and waits for it; a failure, an invalid request or a timeout throws [GattException]. When the
 * caller is cancelled, [onCancel] takes the request out of the library's queue, so it does not go on alone.
 */
private suspend fun Request.await(onCancel: () -> Unit) {
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { runCatching(onCancel) }
        done { if (continuation.isActive) continuation.resume(Unit) }
            .fail { _, status ->
                if (continuation.isActive) continuation.resumeWithException(GattException(failure(status), status))
            }
            .invalid {
                if (continuation.isActive) continuation.resumeWithException(GattException("The request is not valid"))
            }
            .let { gattCall { it.enqueue() } }
    }
}

/**
 * Android throws other exceptions than GATT failures: [SecurityException] when the `BLUETOOTH_CONNECT`
 * permission was revoked while the app runs, [IllegalArgumentException] for an address it cannot use. They
 * become [GattException], so a connection that cannot work ends as failed instead of breaking its caller.
 */
private inline fun <T> gattCall(block: () -> T): T = try {
    block()
} catch (e: SecurityException) {
    throw GattException("No permission to connect to Bluetooth devices", retryable = false, cause = e)
} catch (e: IllegalArgumentException) {
    throw GattException("Not a Bluetooth address", retryable = false, cause = e)
}

private fun failure(status: Int): String = when (status) {
    FailCallback.REASON_TIMEOUT -> "The scale did not answer in time"
    FailCallback.REASON_DEVICE_DISCONNECTED -> "The scale disconnected"
    FailCallback.REASON_BLUETOOTH_DISABLED -> "Bluetooth is off"
    FailCallback.REASON_DEVICE_NOT_SUPPORTED -> "The scale does not have the expected services"
    else -> "GATT error $status"
}
