package dev.kinash.smartscales.ui.weighing

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kinash.smartscales.core.ble.AdvertisementScanner
import dev.kinash.smartscales.core.ble.BluetoothLog
import dev.kinash.smartscales.core.ble.ConnectionUpdate
import dev.kinash.smartscales.core.ble.GattConnector
import dev.kinash.smartscales.core.ble.GattContext
import dev.kinash.smartscales.core.ble.GattEvent
import dev.kinash.smartscales.core.ble.KeyProvider
import dev.kinash.smartscales.core.ble.ProtocolDescription
import dev.kinash.smartscales.core.ble.ScaleDrivers
import dev.kinash.smartscales.core.ble.ScaleReading
import dev.kinash.smartscales.core.ble.ScaleUser
import dev.kinash.smartscales.core.ble.WeighingTracker
import dev.kinash.smartscales.core.data.Measurement
import dev.kinash.smartscales.core.data.Profile
import dev.kinash.smartscales.core.data.ScaleBinding
import dev.kinash.smartscales.core.data.ScaleDisplayUnit
import dev.kinash.smartscales.core.data.repository.MeasurementRepository
import dev.kinash.smartscales.core.data.repository.ProfileRepository
import dev.kinash.smartscales.core.data.repository.ScaleBindingRepository
import dev.kinash.smartscales.core.data.repository.SettingsRepository
import dev.kinash.smartscales.core.model.MeasurementSource
import dev.kinash.smartscales.core.model.Sex
import dev.kinash.smartscales.core.model.WeightUnit
import dev.kinash.smartscales.core.protocol.ScaleUnit
import dev.kinash.smartscales.core.protocol.ScaleWeight
import dev.kinash.smartscales.core.protocol.V2Cipher
import dev.kinash.smartscales.indicators.Indicators
import dev.kinash.smartscales.ui.scale.scanUntilError
import dev.kinash.smartscales.ui.scale.titleOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.Period
import java.time.ZoneId
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Profiles whose last weight is within [thresholdKg] of [weightKg], closest first. Exactly one match
 * means the weighing is theirs; none or several mean the app has to ask.
 */
fun matchProfiles(weightKg: Double, latestWeights: Map<Long, Double>, thresholdKg: Double): List<Long> =
    latestWeights.entries
        .filter { abs(it.value - weightKg) <= thresholdKg }
        .sortedBy { abs(it.value - weightKg) }
        .map { it.key }

/** The connection to a scale read over GATT. */
enum class ConnectionStatus {
    CONNECTING,

    /** Connected and set up; the scale sends once someone stands on it. */
    CONNECTED,

    /** The last try did not connect; the next advertisement tries again after a pause. */
    FAILED,
}

/** What a paired scale shows right now. */
enum class ScaleStatus {
    /** Nothing received yet in this session. */
    WAITING,

    /** The weight while the person stands on the scale. */
    LIVE,

    /** The weighing finished, or its result was saved already. */
    FINISHED,

    /** The person said the decrypted weight differs from the display: the V2 key is wrong. */
    WRONG_KEY,
}

data class ScaleView(
    val binding: ScaleBinding,
    val title: String,
    val description: ProtocolDescription?,
    val status: ScaleStatus = ScaleStatus.WAITING,
    val reading: ScaleReading? = null,
    /** Null for scales read from advertisements, and while a GATT scale is not being connected. */
    val connection: ConnectionStatus? = null,
    /** Records the scale kept and sent in this session; they went to the inbox. */
    val historySaved: Int = 0,
)

/**
 * The app asks whose weighing it is. The weighing is in the inbox already, so it stays there when the
 * question is dismissed or lost with the process; answering moves it to a profile.
 */
data class WhoQuestion(
    val measurementId: Long,
    val weight: ScaleWeight,
    /** Profiles to choose from, likely ones first. */
    val choices: List<Profile>,
)

/** A weighing saved just now, for the result with "Not me". */
data class SavedWeighing(
    val measurementId: Long,
    val profile: Profile?,
    val weight: ScaleWeight,
    val choices: List<Profile>,
    /** What to say about it, once worked out from the profile's history; null in the inbox. */
    val notes: WeighingNotes? = null,
)

/**
 * The scale asks which of [candidates] stands on it (OKOK v1.1), to work out its own numbers for them. Without
 * an answer within [ScaleUserQuestion.TIMEOUT] the app picks the one closest by last weight.
 */
class ScaleUserQuestion(
    val bindingId: Long,
    val weight: ScaleWeight,
    val candidates: List<Profile>,
    internal val answer: suspend (Int) -> Unit,
) {
    val timeoutSeconds: Int get() = TIMEOUT.seconds.toInt()

    companion object {
        val TIMEOUT: Duration = Duration.ofSeconds(10)
    }
}

/**
 * The first weighing with a new V2 key: does the decrypted weight match the scale display? It waits in the inbox
 * as [measurementId] meanwhile.
 */
data class KeyCheck(val bindingId: Long, val reading: ScaleReading, val measurementId: Long)

data class WeighingState(
    val loaded: Boolean = false,
    val scales: List<ScaleView> = emptyList(),
    val questions: List<WhoQuestion> = emptyList(),
    val keyChecks: List<KeyCheck> = emptyList(),
    val saved: SavedWeighing? = null,
    val scanError: Int? = null,
    val scaleQuestion: ScaleUserQuestion? = null,
)

/** The weighing screen: the app's [WeighingSession] while the screen is visible. */
@HiltViewModel
class WeighingViewModel @Inject constructor(
    val scanner: AdvertisementScanner,
    private val session: WeighingSession,
    log: BluetoothLog,
) : ViewModel() {
    /** The Bluetooth log records this session's frames. */
    val logRecording: StateFlow<Boolean> = log.recording

    val state: StateFlow<WeighingState> = session.state

    /** Listens while the screen is visible; the background, if on, goes on listening after it. */
    suspend fun scan() {
        session.listen(onScreen = true)
    }

    fun answerScale(question: ScaleUserQuestion, profile: Profile) = session.answerScale(question, profile)

    fun answerKeyCheck(check: KeyCheck, matches: Boolean) = session.answerKeyCheck(check, matches)

    fun answer(question: WhoQuestion, profile: Profile?) = session.answer(question, profile)

    fun answerWithNewProfile(question: WhoQuestion) = session.answerWithNewProfile(question)

    fun notMe(saved: SavedWeighing) = session.notMe(saved)

    fun savedShown() = session.savedShown()
}
