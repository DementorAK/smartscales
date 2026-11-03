package dev.kinash.smartscales.core.ble

import dev.kinash.smartscales.core.protocol.ScaleUnit
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.util.UUID

/**
 * A GATT operation the scale refused or did not answer in time; [status] is the Android GATT status when known.
 * [retryable] is false when trying again cannot help: Bluetooth is off, the permission is missing.
 */
class GattException(
    message: String,
    val status: Int? = null,
    val retryable: Boolean = true,
    cause: Throwable? = null,
) : Exception(message, cause)

/** A value the scale sent by notification or indication. */
class GattNotification(val characteristic: UUID, val value: ByteArray)

/**
 * A connected scale. Operations run one at a time, each waiting for the scale's answer, because Android GATT
 * loses requests sent before the previous one completed.
 */
interface GattSession {
    fun hasCharacteristic(service: UUID, characteristic: UUID): Boolean

    /** Turns on notifications or indications, whichever the characteristic has; its values then arrive in [GattLink.notifications]. */
    suspend fun subscribe(service: UUID, characteristic: UUID)

    suspend fun write(service: UUID, characteristic: UUID, value: ByteArray, withResponse: Boolean = true)
}

/** An open connection: the [session] for operations and what the scale sends, until it disconnects. */
interface GattLink {
    val session: GattSession

    /** Values from subscribed characteristics; the flow completes when the scale disconnects. */
    val notifications: Flow<GattNotification>

    /** Disconnects, if the scale has not yet, and frees the connection. */
    suspend fun release()
}

/** Opens connections; the Android implementation is [NordicGattTransport], tests use a fake. */
fun interface GattTransport {
    /** Connects to the device Android knows by [address] and discovers its services, or throws [GattException]. */
    suspend fun connect(address: String): GattLink
}

/**
 * The person the scale measures, for scales that take the user before weighing. [userId] is the number this
 * app gives the person on that scale.
 */
data class ScaleUser(
    val userId: Int,
    val male: Boolean,
    val ageYears: Int,
    val heightCm: Int,
    val weightKg: Double? = null,
)

/**
 * What the app tells the scale on connecting: the person on the scale ([user], the profile on the main screen)
 * and, for scales that keep several, everyone ([users]).
 */
data class GattContext(val now: Instant, val unit: ScaleUnit, val user: ScaleUser?, val users: List<ScaleUser> = listOfNotNull(user))

/** What a GATT scale reports. */
sealed interface GattEvent {
    val reading: ScaleReading

    /** The weight while the person stands on the scale. */
    data class Live(override val reading: ScaleReading) : GattEvent

    /** The final result of this weighing, at [takenAt]. */
    data class Result(override val reading: ScaleReading, val takenAt: Instant) : GattEvent

    /** A weighing the scale kept while no phone was near. */
    data class History(override val reading: ScaleReading, val takenAt: Instant) : GattEvent

    /**
     * The weight fits several people and the scale asks which of [candidates] (user ids) stands on it, so it can
     * work out its own numbers for them. [answer] sends the choice.
     */
    class UserQuestion(override val reading: ScaleReading, val candidates: List<Int>, val answer: suspend (Int) -> Unit) : GattEvent
}

/**
 * The protocol of one connection: it talks to the scale after connecting and reads what it sends. It may keep
 * state between notifications (a result in two packets), so a new one is made for every connection.
 */
interface GattProtocol {
    /** Writes and subscriptions in the order the scale expects, right after the services were discovered. */
    suspend fun start(session: GattSession, context: GattContext)

    /**
     * Events in a notification; none while a multi-packet value is incomplete or for frames it does not know.
     * It may answer the scale through the session it was started with.
     */
    suspend fun onNotification(notification: GattNotification): List<GattEvent>
}

/** A driver of scales that are read over a GATT connection. */
interface GattScaleDriver {
    /**
     * A protocol for a new connection to a scale bound with [protocol] and [version] (as its advertisement gave
     * it), or null when it is not this driver's.
     */
    fun gattProtocol(protocol: String, scaleMac: String, version: String?): GattProtocol?
}
