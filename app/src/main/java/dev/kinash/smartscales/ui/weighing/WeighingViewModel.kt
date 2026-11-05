package dev.kinash.smartscales.ui.weighing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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

/** The first weighing with a new V2 key: does the decrypted weight match the scale display? */
data class KeyCheck(val bindingId: Long, val reading: ScaleReading)

data class WeighingState(
    val loaded: Boolean = false,
    val scales: List<ScaleView> = emptyList(),
    val questions: List<WhoQuestion> = emptyList(),
    val keyChecks: List<KeyCheck> = emptyList(),
    val saved: SavedWeighing? = null,
    val scanError: Int? = null,
    val scaleQuestion: ScaleUserQuestion? = null,
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
    private val connector: GattConnector,
    private val indicators: Indicators,
    log: BluetoothLog,
) : ViewModel() {
    /** The Bluetooth log records this session's frames. */
    val logRecording: StateFlow<Boolean> = log.recording

    private val readings = MutableStateFlow<Map<String, Pair<ScaleStatus, ScaleReading>>>(emptyMap())

    /** Connection and history records saved, by scale MAC, for GATT scales. */
    private val links = MutableStateFlow<Map<String, LinkState>>(emptyMap())

    private data class LinkState(val connection: ConnectionStatus? = null, val historySaved: Int = 0)

    /**
     * Running connections by binding id, and when the next one may start after a disconnection or a failure.
     * Plain maps: [scan] and the connections it starts run on the main thread of [viewModelScope], so they are
     * used from one thread only. Moving that work to another dispatcher needs them made thread-safe.
     */
    private val connections = mutableMapOf<Long, Job>()
    private val pausedUntil = mutableMapOf<Long, Instant>()

    /**
     * Records of one connection: those up to [acceptedUntil] were taken on earlier connections and are skipped,
     * even if the person deleted them since; [newest] moves the mark when the connection ends.
     */
    private class HistoryWindow(val acceptedUntil: Instant?) {
        var newest: Instant? = null
            private set

        fun isNew(takenAt: Instant): Boolean = acceptedUntil == null || takenAt.isAfter(acceptedUntil)

        fun saw(takenAt: Instant) {
            if (newest?.isAfter(takenAt) != true) newest = takenAt
        }
    }
    private val session = MutableStateFlow(WeighingState())
    private val bound: StateFlow<List<ScaleBinding>?> =
        bindings.observeBound().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val state: StateFlow<WeighingState> = combine(bound.filterNotNull(), readings, links, session) { bound, readings, links, session ->
        session.copy(
            loaded = true,
            scales = bound.map { binding ->
                val (status, reading) = readings[binding.mac] ?: (ScaleStatus.WAITING to null)
                val link = links[binding.mac] ?: LinkState()
                ScaleView(
                    binding, drivers.titleOf(binding), drivers.describe(binding.protocol), status, reading,
                    link.connection, link.historySaved,
                )
            },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WeighingState())

    private val keys = KeyProvider { mac -> boundByMac(mac)?.v2Key?.let(V2Cipher::parseKey) }

    private fun boundByMac(mac: String): ScaleBinding? = bound.value?.firstOrNull { it.mac == mac }

    /**
     * Listens to the paired scales until cancelled. A scale read over GATT is connected to when it advertises,
     * that is, when it has woken up; the connections end with the scan.
     */
    suspend fun scan() = coroutineScope {
        session.update { it.copy(scanError = null) }
        val error = scanner.scanUntilError { advertisement ->
            val (driver, support) = drivers.recognise(advertisement) ?: return@scanUntilError
            val binding = boundByMac(support.scaleMac) ?: return@scanUntilError
            val reading = driver.onAdvertisement(advertisement, keys) ?: return@scanUntilError
            if (binding.bleAddress != advertisement.address.uppercase()) {
                bindings.updateAddress(binding.id, advertisement.address)
            }
            if (connectIfNeeded(binding, advertisement.address)) return@scanUntilError
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
        coroutineContext.cancelChildren()
    }

    /**
     * Connects to [binding] when its protocol is read over GATT, unless it is connected already or pausing after
     * the last connection. Returns true for such a scale: its advertisements are then only a sign it is awake,
     * since the connection shows the weight.
     */
    private fun CoroutineScope.connectIfNeeded(binding: ScaleBinding, address: String): Boolean {
        if (connections[binding.id]?.isActive == true) return true
        val protocol = drivers.gattProtocol(binding.protocol, binding.mac, binding.protocolVersion, binding.productId) ?: return false
        if (pausedUntil[binding.id]?.let { clock.instant().isBefore(it) } == true) return true
        connections[binding.id] = launch {
            // The stored binding: its settings may have changed since the list was read
            val stored = bindings.get(binding.id) ?: binding
            val context = gattContext(stored)
            val history = HistoryWindow(stored.historyAcceptedUntil)
            try {
                connector.connect(address, binding.protocol, protocol, context).collect { update -> onUpdate(binding, update, history) }
            } finally {
                // Also when the person leaves the screen mid-connection: what was saved is taken
                history.newest?.let { withContext(NonCancellable) { bindings.acceptHistoryUntil(binding.id, it) } }
            }
        }
        return true
    }

    private suspend fun CoroutineScope.onUpdate(binding: ScaleBinding, update: ConnectionUpdate, history: HistoryWindow) {
        when (update) {
            is ConnectionUpdate.Connecting -> link(binding) { it.copy(connection = ConnectionStatus.CONNECTING) }
            ConnectionUpdate.Connected -> link(binding) { it.copy(connection = ConnectionStatus.CONNECTED) }
            ConnectionUpdate.Disconnected -> {
                // The scale went to sleep after the weighing; it advertises a little longer, which is no reason to reconnect
                pausedUntil[binding.id] = clock.instant().plus(RECONNECT_PAUSE)
                link(binding) { it.copy(connection = null) }
                dropQuestion(binding)
            }
            is ConnectionUpdate.Failed -> {
                pausedUntil[binding.id] = clock.instant().plus(FAILED_PAUSE)
                link(binding) { it.copy(connection = ConnectionStatus.FAILED) }
                dropQuestion(binding)
            }
            is ConnectionUpdate.Event -> when (val event = update.event) {
                is GattEvent.Live -> show(event.reading, ScaleStatus.LIVE)
                is GattEvent.Result -> {
                    show(event.reading, ScaleStatus.FINISHED)
                    if (!measurements.existsFromScale(binding.id, event.takenAt)) store(binding, event.reading, event.takenAt)
                    // A scale may keep the result in its memory too and send it again as a record later
                    history.saw(event.takenAt)
                }
                is GattEvent.History -> {
                    if (history.isNew(event.takenAt)) storeHistory(binding, event.reading, event.takenAt)
                    history.saw(event.takenAt)
                }
                is GattEvent.UserQuestion -> ask(binding, event)
            }
        }
    }

    /** A question of a scale that went away cannot be answered any more. */
    private fun dropQuestion(binding: ScaleBinding) {
        session.update { if (it.scaleQuestion?.bindingId == binding.id) it.copy(scaleQuestion = null) else it }
    }

    private fun link(binding: ScaleBinding, change: (LinkState) -> LinkState) {
        links.update { it + (binding.mac to change(it[binding.mac] ?: LinkState())) }
    }

    /**
     * What a GATT scale is told on connecting, by its settings. Of the profiles it may know (all but the
     * excluded ones), it gets the profile on the main screen as the person on it, as the vendor app passes its
     * current user, or else the first one; scales that keep several get them all. The unit is the binding's,
     * by default that of the profile on the main screen; without profiles, kilograms.
     */
    private suspend fun gattContext(binding: ScaleBinding): GattContext {
        val now = clock.instant()
        val excluded = bindings.excludedProfiles(binding.id)
        val all = profiles.observeProfiles().first()
        val known = all.filter { it.id !in excluded }
        val selected = settings.selectedProfileId.first()
        val profile = known.firstOrNull { it.id == selected } ?: known.firstOrNull()
        val shown = all.firstOrNull { it.id == selected } ?: all.firstOrNull()
        val unit = when (binding.displayUnit) {
            ScaleDisplayUnit.KEEP -> null
            ScaleDisplayUnit.KILOGRAM -> ScaleUnit.KILOGRAM
            ScaleDisplayUnit.POUND -> ScaleUnit.POUND
            ScaleDisplayUnit.STONE -> ScaleUnit.STONE
            ScaleDisplayUnit.PROFILE -> when (shown?.weightUnit) {
                WeightUnit.POUND -> ScaleUnit.POUND
                WeightUnit.STONE -> ScaleUnit.STONE
                else -> ScaleUnit.KILOGRAM
            }
        }
        val today = now.atZone(ZoneId.systemDefault()).toLocalDate()
        val weights = measurements.latestWeights()
        fun userOf(it: Profile) = ScaleUser(
            userId = it.id.toInt(),
            male = it.sex == Sex.MALE,
            ageYears = Period.between(it.birthDate, today).years.coerceAtLeast(0),
            heightCm = it.heightCm.roundToInt(),
            weightKg = weights[it.id],
        )
        // Scales that keep several people (OKOK v1.1) get everyone, the profile on the main screen first
        val everyone = listOfNotNull(profile) + known.filter { it.id != profile?.id }
        return GattContext(now, unit, profile?.let(::userOf), everyone.map(::userOf), syncTime = binding.syncTime)
    }

    /**
     * Shows the scale's question. The answer goes back to the scale; without one, the closest candidate by last
     * weight is chosen, as the vendor app does. Ids the app does not know are left out.
     */
    private suspend fun CoroutineScope.ask(binding: ScaleBinding, event: GattEvent.UserQuestion) {
        val weight = event.reading.weight ?: return
        val all = profiles.observeProfiles().first()
        val candidates = event.candidates.mapNotNull { id -> all.firstOrNull { it.id.toInt() == id } }
        if (candidates.isEmpty()) return
        val question = ScaleUserQuestion(binding.id, weight, candidates, event.answer)
        session.update { it.copy(scaleQuestion = question) }
        launch {
            delay(ScaleUserQuestion.TIMEOUT.toMillis())
            if (session.value.scaleQuestion !== question) return@launch
            val weights = measurements.latestWeights()
            val closest = candidates.minByOrNull { profile ->
                weights[profile.id]?.let { kotlin.math.abs(it - weight.kilograms) } ?: Double.MAX_VALUE
            } ?: return@launch
            answerScale(question, closest)
        }
    }

    /** The person's answer to [ScaleUserQuestion]; it is sent to the scale until the scale answers. */
    fun answerScale(question: ScaleUserQuestion, profile: Profile) {
        session.update { if (it.scaleQuestion === question) it.copy(scaleQuestion = null) else it }
        viewModelScope.launch { question.answer(profile.id.toInt()) }
    }

    /**
     * The profile a scale named for its weighing ([ScaleReading.userId], the id the app gave the person), if it
     * still exists and was born by then.
     */
    private suspend fun namedProfile(reading: ScaleReading, takenAt: Instant): Profile? {
        val id = reading.userId ?: return null
        val profile = profiles.observeProfiles().first().firstOrNull { it.id.toInt() == id } ?: return null
        return profile.takeIf { it.isBornBy(takenAt.atZone(ZoneId.systemDefault()).toLocalDate()) }
    }

    /**
     * A record the scale kept while no phone was near. A record that names its person (OKOK v1.1) goes to their
     * profile; one that does not (V4) goes to the inbox (requirements §2.2). The first connection takes the
     * scale's whole memory, weighings from before the scale was paired included (the author's choice); later
     * ones only what is newer than the binding's mark.
     *
     * A record from before the scale was paired goes to the inbox even when it names someone: the scale kept
     * it with the user numbers of another app, which may happen to equal the id of one of our profiles.
     */
    private suspend fun storeHistory(binding: ScaleBinding, reading: ScaleReading, takenAt: Instant) {
        val weight = reading.weight ?: return
        if (measurements.existsFromScale(binding.id, takenAt)) return
        val profile = namedProfile(reading, takenAt)?.takeUnless { takenAt.isBefore(binding.boundAt) }
        measurements.add(
            Measurement(
                profileId = profile?.id,
                takenAt = takenAt,
                zone = ZoneId.systemDefault(),
                source = MeasurementSource.SCALE,
                weightKg = weight.kilograms,
                person = profile?.snapshot(),
                impedances = reading.impedances,
                heartRateBpm = reading.heartRateBpm,
                importedValues = reading.scaleValues,
                scaleBindingId = binding.id,
                scaleProperties = reading.propertyBits,
            ),
        )
        if (profile == null) link(binding) { it.copy(historySaved = it.historySaved + 1) }
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
     * Saves a weighing for the profile the scale named, or else for the only profile whose weight matches.
     * Otherwise it goes to the inbox at once, so it is not lost with the process, and the app asks whose it is.
     */
    private suspend fun store(binding: ScaleBinding, reading: ScaleReading, takenAt: Instant = clock.instant()) {
        val weight = reading.weight ?: return
        val all = profiles.observeProfiles().first()
        val named = namedProfile(reading, takenAt)
        val matches = named?.let { listOf(it.id) }
            ?: matchProfiles(weight.kilograms, measurements.latestWeights(), settings.matchThresholdKg.first())
        val choices = all.sortedBy { profile -> matches.indexOf(profile.id).takeIf { it >= 0 } ?: Int.MAX_VALUE }
        val match = matches.singleOrNull()?.let { id -> all.firstOrNull { it.id == id } }
        val id = measurements.add(
            Measurement(
                profileId = match?.id,
                takenAt = takenAt,
                zone = ZoneId.systemDefault(),
                source = MeasurementSource.SCALE,
                weightKg = weight.kilograms,
                person = match?.snapshot(),
                impedances = reading.impedances,
                heartRateBpm = reading.heartRateBpm,
                importedValues = reading.scaleValues,
                scaleBindingId = binding.id,
                scaleProperties = reading.propertyBits,
            ),
        )
        if (match != null) {
            showSaved(SavedWeighing(id, match, weight, choices))
        } else {
            session.update { it.copy(questions = it.questions + WhoQuestion(id, weight, choices)) }
        }
    }

    /** Answer to "whose weighing is it?"; null leaves it in the inbox. */
    fun answer(question: WhoQuestion, profile: Profile?) {
        session.update { state -> state.copy(questions = state.questions - question) }
        viewModelScope.launch {
            if (profile != null) measurements.transfer(question.measurementId, profile.id)
            showSaved(SavedWeighing(question.measurementId, profile, question.weight, question.choices))
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

    /**
     * Shows [saved], then what to say about it: the change, goals reached and a weight far from the usual one
     * (requirements §6.2), here and not in a notification.
     */
    private suspend fun showSaved(saved: SavedWeighing) {
        session.update { it.copy(saved = saved) }
        val profile = saved.profile ?: return
        val history = indicators.observeHistory(profile.id).first()
        val goals = profiles.observeGoals(profile.id).first()
        val notes = weighingNotes(indicators.model, history, saved.measurementId, goals, settings.matchThresholdKg.first()) ?: return
        // Only if it is still the one shown: "Not me" or the next weighing may have come meanwhile
        session.update { state -> if (state.saved?.measurementId == saved.measurementId) state.copy(saved = state.saved.copy(notes = notes)) else state }
    }

    private companion object {
        /** A result equal to the newest weighing from the scale within this time is that weighing. */
        val SAME_RESULT_WINDOW: Duration = Duration.ofMinutes(10)

        /** After a scale disconnected: it keeps advertising for a while on its way to sleep. */
        val RECONNECT_PAUSE: Duration = Duration.ofSeconds(10)

        /** After a failed connection, before the next advertisement tries again. */
        val FAILED_PAUSE: Duration = Duration.ofSeconds(5)
    }
}
