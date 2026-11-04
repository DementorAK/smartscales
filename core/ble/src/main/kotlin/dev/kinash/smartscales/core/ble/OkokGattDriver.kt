package dev.kinash.smartscales.core.ble

import dev.kinash.smartscales.core.protocol.ChipseaGatt
import dev.kinash.smartscales.core.protocol.ChipseaProtocol
import dev.kinash.smartscales.core.protocol.OkokCommands
import dev.kinash.smartscales.core.protocol.OkokFrame
import dev.kinash.smartscales.core.protocol.OkokParser
import dev.kinash.smartscales.core.protocol.OkokUser
import dev.kinash.smartscales.core.protocol.ScaleComposition
import dev.kinash.smartscales.core.protocol.ScaleRecords
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * OKOK scales over GATT (`CA 10` and `CA 11` advertisements), written from the protocol description without a
 * scale at hand. They work out body composition themselves and send no impedance: the app keeps their numbers
 * as the scale's data and works out its own indicators from the weight.
 */
class OkokGattDriver(
    private val now: () -> Instant = Instant::now,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) : GattScaleDriver {
    override fun gattProtocol(protocol: String, scaleMac: String, version: String?, productId: Int?): GattProtocol? =
        if (protocol == ChipseaProtocol.OKOK.name) OkokProtocol(scaleMac, multiUser = version == VERSION_11, now, zone) else null

    companion object {
        /** The version a `CA 11` advertisement gives; everything else is v1.0. */
        const val VERSION_11 = "0x11"

        val IMPLEMENTED = setOf(
            DeviceCapability.LIVE_WEIGHT,
            DeviceCapability.USER_SYNC,
            DeviceCapability.HISTORY_READ,
            DeviceCapability.TIME_SYNC,
        )
    }
}

/**
 * One connection to an OKOK scale. v1.0 takes the person on the scale, sent four times as the vendor app does;
 * v1.1 takes everyone, twice, and after the first result is asked for the weighings it kept. Both answer on
 * `FFF1` only.
 */
internal class OkokProtocol(
    private val scaleMac: String,
    private val multiUser: Boolean,
    private val now: () -> Instant,
    private val zone: () -> ZoneId,
) : GattProtocol {
    private val parser = OkokParser()
    private var session: GattSession? = null
    private var historyRequested = false

    /** A v1.0 scale repeats its locked frame: only the first after a weight in progress is the result. */
    private var lockedShown = false

    /** Set when a weight arrives after a user question, so the answer stops repeating. */
    @Volatile
    private var answered = false

    override suspend fun start(session: GattSession, context: GattContext) {
        this.session = session
        session.subscribe(ChipseaGatt.CHIPSEA_SERVICE, ChipseaGatt.CHIPSEA_RX)
        if (multiUser) {
            val users = context.users.map(::okokUser)
            if (users.isEmpty()) return
            val packets = OkokCommands.usersV11(context.now, users)
            repeat(USERS_REPEATS) {
                packets.forEach { packet ->
                    delay(USERS_GAP_MS)
                    write(session, packet)
                }
            }
        } else {
            val user = context.user ?: return
            val clock = LocalDateTime.ofInstant(context.now, zone())
            repeat(USER_V10_REPEATS) {
                delay(USER_V10_GAP_MS)
                write(session, OkokCommands.userV10(clock, okokUser(user)))
            }
        }
    }

    override suspend fun onNotification(notification: GattNotification): List<GattEvent> {
        if (notification.characteristic != ChipseaGatt.CHIPSEA_RX) return emptyList()
        val frame = parser.parse(notification.value) ?: return emptyList()
        return listOfNotNull(
            when (frame) {
                is OkokFrame.Weight -> weight(frame)
                is OkokFrame.Result -> {
                    answered = true
                    requestHistory()
                    GattEvent.Result(reading(frame, frame.userId, frame.composition, locked = true), now())
                }
                is OkokFrame.History -> frame.takenAt.takeIf { ScaleRecords.isPlausibleTime(it, now()) }
                    ?.let { GattEvent.History(reading(frame, frame.userId, frame.composition, locked = true), it) }
                is OkokFrame.UserQuestion -> {
                    answered = false
                    GattEvent.UserQuestion(reading(frame, null, null, locked = false), frame.candidates, ::answer)
                }
            },
        )
    }

    /**
     * v1.0: a locked weight is the result, with the composition; v1.1 sends its result separately, as `12`.
     * Any weight means the scale took the answer to its question.
     */
    private fun weight(frame: OkokFrame.Weight): GattEvent? {
        answered = true
        if (!frame.locked || multiUser) {
            lockedShown = false
            return GattEvent.Live(reading(frame, null, null, locked = false))
        }
        if (lockedShown) return null
        lockedShown = true
        return reading(frame, null, frame.composition, locked = true)
            .takeIf { it.weight != null }
            ?.let { GattEvent.Result(it, now()) }
    }

    private suspend fun requestHistory() {
        if (!multiUser || historyRequested) return
        historyRequested = true
        session?.let { write(it, OkokCommands.historyRequest()) }
    }

    /**
     * Sends the choice until the scale answers with a weight (live or result), at most ten times, as the vendor
     * app does. A scale
     * that disconnected meanwhile ends it quietly: there is no one to answer.
     */
    private suspend fun answer(userId: Int) {
        val session = session ?: return
        answered = false
        repeat(SELECT_REPEATS) {
            if (answered) return
            try {
                write(session, OkokCommands.selectUser(userId))
            } catch (_: GattException) {
                return
            }
            delay(SELECT_GAP_MS)
        }
    }

    private fun reading(frame: OkokFrame, userId: Int?, composition: ScaleComposition?, locked: Boolean): ScaleReading {
        val weight = frame.weight.takeIf { ScaleRecords.isPlausibleWeight(it.kilograms) }
        return ScaleReading(
            scaleMac = scaleMac,
            weight = weight,
            locked = locked,
            propertyBits = frame.property.bits,
            userId = userId,
            scaleValues = if (weight != null) composition?.toValues().orEmpty() else emptyMap(),
        )
    }

    private suspend fun write(session: GattSession, value: ByteArray) =
        session.write(ChipseaGatt.CHIPSEA_SERVICE, ChipseaGatt.CHIPSEA_TX, value, withResponse = false)

    private fun okokUser(user: ScaleUser) = OkokUser(user.userId, user.male, user.ageYears, user.heightCm, user.weightKg)

    private companion object {
        const val USER_V10_REPEATS = 4
        const val USER_V10_GAP_MS = 150L
        const val USERS_REPEATS = 2
        const val USERS_GAP_MS = 200L
        const val SELECT_REPEATS = 10
        const val SELECT_GAP_MS = 800L
    }
}
