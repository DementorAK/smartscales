package dev.kinash.smartscales.ui.weighing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kinash.smartscales.core.ble.AdvertisementScanner
import dev.kinash.smartscales.core.ble.BluetoothLog
import dev.kinash.smartscales.core.ble.KeyProvider
import dev.kinash.smartscales.core.ble.ProtocolDescription
import dev.kinash.smartscales.core.ble.ScaleDrivers
import dev.kinash.smartscales.core.ble.ScaleReading
import dev.kinash.smartscales.core.ble.WeighingTracker
import dev.kinash.smartscales.core.data.Measurement
import dev.kinash.smartscales.core.data.Profile
import dev.kinash.smartscales.core.data.ScaleBinding
import dev.kinash.smartscales.core.data.repository.MeasurementRepository
import dev.kinash.smartscales.core.data.repository.ProfileRepository
import dev.kinash.smartscales.core.data.repository.ScaleBindingRepository
import dev.kinash.smartscales.core.data.repository.SettingsRepository
import dev.kinash.smartscales.core.model.MeasurementSource
import dev.kinash.smartscales.core.protocol.ScaleWeight
import dev.kinash.smartscales.core.protocol.V2Cipher
import dev.kinash.smartscales.ui.scale.scanUntilError
import dev.kinash.smartscales.ui.scale.titleOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Duration
import java.time.ZoneId
import javax.inject.Inject
import kotlin.math.abs

/**
 * Profiles whose last weight is within [thresholdKg] of [weightKg], closest first. Exactly one match
 * means the weighing is theirs; none or several mean the app has to ask.
 */
fun matchProfiles(weightKg: Double, latestWeights: Map<Long, Double>, thresholdKg: Double): List<Long> =
    latestWeights.entries
        .filter { abs(it.value - weightKg) <= thresholdKg }
        .sortedBy { abs(it.value - weightKg) }
        .map { it.key }

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

/** A weighing saved just now, for the message with "Not me". */
data class SavedWeighing(
    val measurementId: Long,
    val profile: Profile?,
    val weight: ScaleWeight,
    val choices: List<Profile>,
)

/** The first weighing with a new V2 key: does the decrypted weight match the scale display? */
data class KeyCheck(val bindingId: Long, val reading: ScaleReading)

data class WeighingState(
    val loaded: Boolean = false,
    val scales: List<ScaleView> = emptyList(),
    val questions: List<WhoQuestion> = emptyList(),
    val keyChecks: List<KeyCheck> = emptyList(),
    val saved: SavedWeighing? = null,
    val scanError: Int? = null,
)

@HiltViewModel
class WeighingViewModel @Inject constructor(
    val scanner: AdvertisementScanner,
    private val drivers: ScaleDrivers,
    private val tracker: WeighingTracker,
    private val bindings: ScaleBindingRepository,
    private val profiles: ProfileRepository,
    private val measurements: MeasurementRepository,
    private val settings: SettingsRepository,
    private val clock: Clock,
    log: BluetoothLog,
) : ViewModel() {
    /** The Bluetooth log records this session's frames. */
    val logRecording: StateFlow<Boolean> = log.recording

    private val readings = MutableStateFlow<Map<String, Pair<ScaleStatus, ScaleReading>>>(emptyMap())
    private val session = MutableStateFlow(WeighingState())
    private val bound: StateFlow<List<ScaleBinding>?> =
        bindings.observeBound().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val state: StateFlow<WeighingState> = combine(bound.filterNotNull(), readings, session) { bound, readings, session ->
        session.copy(
            loaded = true,
            scales = bound.map { binding ->
                val (status, reading) = readings[binding.mac] ?: (ScaleStatus.WAITING to null)
                ScaleView(binding, drivers.titleOf(binding), drivers.describe(binding.protocol), status, reading)
            },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WeighingState())

    private val keys = KeyProvider { mac -> boundByMac(mac)?.v2Key?.let(V2Cipher::parseKey) }

    private fun boundByMac(mac: String): ScaleBinding? = bound.value?.firstOrNull { it.mac == mac }

    /** Listens to the paired scales until cancelled. */
    suspend fun scan() {
        session.update { it.copy(scanError = null) }
        val error = scanner.scanUntilError { advertisement ->
            val (driver, support) = drivers.recognise(advertisement) ?: return@scanUntilError
            val binding = boundByMac(support.scaleMac) ?: return@scanUntilError
            val reading = driver.onAdvertisement(advertisement, keys) ?: return@scanUntilError
            if (binding.bleAddress != advertisement.address.uppercase()) {
                bindings.updateAddress(binding.id, advertisement.address)
            }
            when (val event = tracker.onReading(reading)) {
                null -> Unit
                is WeighingTracker.Event.Live -> show(reading, ScaleStatus.LIVE)
                is WeighingTracker.Event.FirstSeen -> {
                    show(reading, ScaleStatus.FINISHED)
                    if (support.description.canWeigh && !alreadySaved(binding, reading)) finished(binding, reading)
                }
                is WeighingTracker.Event.Finished -> {
                    show(reading, ScaleStatus.FINISHED)
                    if (support.description.canWeigh) finished(binding, reading)
                }
            }
        }
        if (error != null) session.update { it.copy(scanError = error) }
    }

    private fun show(reading: ScaleReading, status: ScaleStatus) {
        readings.update { it + (reading.scaleMac to (status to reading)) }
    }

    /**
     * Whether the newest weighing from this scale has the same values: it was saved before the app was
     * restarted, and the scale keeps advertising it for a while.
     */
    private suspend fun alreadySaved(binding: ScaleBinding, reading: ScaleReading): Boolean {
        val latest = measurements.latestFromScale(binding.id) ?: return false
        return latest.weightKg == reading.weight?.kilograms &&
            latest.impedances == reading.impedances &&
            Duration.between(latest.takenAt, clock.instant()) <= SAME_RESULT_WINDOW
    }

    /** A finished weighing, saved unless its key still has to be confirmed. */
    private suspend fun finished(binding: ScaleBinding, reading: ScaleReading) {
        if (reading.decrypted && !binding.v2KeyConfirmed) {
            session.update { it.copy(keyChecks = it.keyChecks + KeyCheck(binding.id, reading)) }
        } else {
            store(binding, reading)
        }
    }

    /**
     * Answer to [KeyCheck]: a matching weight confirms the key and saves the weighing. A different one saves
     * nothing; the scale keeps advertising that weighing, but it is finished, so after fixing the key the
     * person weighs again. Once the key is confirmed, other weighings from the same scale waiting for the
     * check are saved without asking.
     */
    fun answerKeyCheck(check: KeyCheck, matches: Boolean) {
        if (!matches) {
            session.update { it.copy(keyChecks = it.keyChecks - check) }
            show(check.reading, ScaleStatus.WRONG_KEY)
            return
        }
        val sameScale = session.value.keyChecks.filter { it.bindingId == check.bindingId }
        session.update { it.copy(keyChecks = it.keyChecks - sameScale.toSet()) }
        viewModelScope.launch {
            val binding = bindings.get(check.bindingId) ?: return@launch
            binding.v2Key?.let { bindings.confirmKey(binding.id, it) }
            sameScale.forEach { store(binding, it.reading) }
        }
    }

    /**
     * Saves a weighing for the only profile whose weight matches. Otherwise it goes to the inbox at once,
     * so it is not lost with the process, and the app asks whose it is.
     */
    private suspend fun store(binding: ScaleBinding, reading: ScaleReading) {
        val weight = reading.weight ?: return
        val all = profiles.observeProfiles().first()
        val matches = matchProfiles(weight.kilograms, measurements.latestWeights(), settings.matchThresholdKg.first())
        val choices = all.sortedBy { profile -> matches.indexOf(profile.id).takeIf { it >= 0 } ?: Int.MAX_VALUE }
        val match = matches.singleOrNull()?.let { id -> all.firstOrNull { it.id == id } }
        val id = measurements.add(
            Measurement(
                profileId = match?.id,
                takenAt = clock.instant(),
                zone = ZoneId.systemDefault(),
                source = MeasurementSource.SCALE,
                weightKg = weight.kilograms,
                person = match?.snapshot(),
                impedances = reading.impedances,
                scaleBindingId = binding.id,
                scaleProperties = reading.propertyBits,
            ),
        )
        if (match != null) {
            session.update { it.copy(saved = SavedWeighing(id, match, weight, choices)) }
        } else {
            session.update { it.copy(questions = it.questions + WhoQuestion(id, weight, choices)) }
        }
    }

    /** Answer to "whose weighing is it?"; null leaves it in the inbox. */
    fun answer(question: WhoQuestion, profile: Profile?) {
        session.update { state -> state.copy(questions = state.questions - question) }
        viewModelScope.launch {
            if (profile != null) measurements.transfer(question.measurementId, profile.id)
            val saved = SavedWeighing(question.measurementId, profile, question.weight, question.choices)
            session.update { it.copy(saved = saved) }
        }
    }

    /** The weighing goes to a profile created for it; until then it waits in the inbox. */
    fun answerWithNewProfile(question: WhoQuestion) {
        session.update { state -> state.copy(questions = state.questions - question) }
    }

    /**
     * "Not me" on a weighing saved automatically: it goes to the inbox and the app asks again. The profile
     * it went to stays in the list, last, in case "Not me" was tapped by mistake.
     */
    fun notMe(saved: SavedWeighing) {
        session.update { it.copy(saved = null) }
        val profile = saved.profile ?: return
        viewModelScope.launch {
            measurements.moveToInbox(saved.measurementId)
            val choices = saved.choices.filter { it.id != profile.id } + profile
            val question = WhoQuestion(saved.measurementId, saved.weight, choices)
            session.update { it.copy(questions = it.questions + question) }
        }
    }

    fun savedShown() = session.update { it.copy(saved = null) }

    private companion object {
        /** A result equal to the newest weighing from the scale within this time is that weighing. */
        val SAME_RESULT_WINDOW: Duration = Duration.ofMinutes(10)
    }
}
