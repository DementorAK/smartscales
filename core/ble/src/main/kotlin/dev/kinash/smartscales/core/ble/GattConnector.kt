package dev.kinash.smartscales.core.ble

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.util.UUID

/** Where a connection to a scale is. */
sealed interface ConnectionUpdate {
    /** Connecting, try [attempt] of [attempts]. */
    data class Connecting(val attempt: Int, val attempts: Int) : ConnectionUpdate

    /** The scale took the app's time, unit and user and is sending. */
    data object Connected : ConnectionUpdate

    data class Event(val event: GattEvent) : ConnectionUpdate

    /** The scale went away, as scales do after a weighing to save the battery. */
    data object Disconnected : ConnectionUpdate

    /** Connecting or starting the protocol failed after all tries. */
    data class Failed(val cause: GattException) : ConnectionUpdate
}

/** Writes GATT traffic to the Bluetooth log, so a scale that is read wrong can be fixed from a report. */
fun interface GattLogger {
    fun log(address: String, protocol: String, characteristic: UUID, value: ByteArray, outgoing: Boolean)
}

/**
 * Connects to a scale and runs its protocol until the scale disconnects. The transport already retries a
 * failed connection quickly (Android often fails the first one with status 133 when the scale has just woken
 * up); this tries the whole connection [attempts] times, with a pause, after a timeout. Connect only while the
 * scale advertises, that is, while it is awake: a sleeping scale only makes every try time out.
 */
class GattConnector(
    private val transport: GattTransport,
    private val logger: GattLogger = GattLogger { _, _, _, _, _ -> },
    private val attempts: Int = 2,
    private val retryDelayMs: Long = 500,
) {
    fun connect(address: String, protocolName: String, protocol: GattProtocol, context: GattContext): Flow<ConnectionUpdate> = flow {
        var link: GattLink? = null
        var lastError: GattException? = null
        for (attempt in 1..attempts) {
            emit(ConnectionUpdate.Connecting(attempt, attempts))
            try {
                link = transport.connect(address)
                break
            } catch (e: GattException) {
                lastError = e
                if (!e.retryable) break
                if (attempt < attempts) delay(retryDelayMs * attempt)
            }
        }
        if (link == null) {
            emit(ConnectionUpdate.Failed(lastError ?: GattException("Not connected")))
            return@flow
        }
        try {
            try {
                protocol.start(LoggingSession(link.session, address, protocolName, logger), context)
            } catch (e: GattException) {
                emit(ConnectionUpdate.Failed(e))
                return@flow
            }
            emit(ConnectionUpdate.Connected)
            // The link buffers notifications, so none sent while the protocol was starting is lost
            link.notifications.collect { notification ->
                logger.log(address, protocolName, notification.characteristic, notification.value, outgoing = false)
                protocol.onNotification(notification).forEach { emit(ConnectionUpdate.Event(it)) }
            }
            emit(ConnectionUpdate.Disconnected)
        } finally {
            withContext(NonCancellable) { runCatching { link.release() } }
        }
    }

    /** Logs what the app writes to the scale. */
    private class LoggingSession(
        private val session: GattSession,
        private val address: String,
        private val protocolName: String,
        private val logger: GattLogger,
    ) : GattSession by session {
        override suspend fun write(service: UUID, characteristic: UUID, value: ByteArray, withResponse: Boolean) {
            logger.log(address, protocolName, characteristic, value, outgoing = true)
            session.write(service, characteristic, value, withResponse)
        }
    }
}
