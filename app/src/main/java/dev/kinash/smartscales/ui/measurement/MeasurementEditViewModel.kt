package dev.kinash.smartscales.ui.measurement

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kinash.smartscales.core.data.Measurement
import dev.kinash.smartscales.core.data.Profile
import dev.kinash.smartscales.core.data.repository.MeasurementRepository
import dev.kinash.smartscales.core.data.repository.ProfileRepository
import dev.kinash.smartscales.core.formula.InputDef
import dev.kinash.smartscales.core.model.LengthUnit
import dev.kinash.smartscales.core.model.ManualFields
import dev.kinash.smartscales.core.model.MeasurementSource
import dev.kinash.smartscales.core.model.PersonSnapshot
import dev.kinash.smartscales.core.model.WeightUnit
import dev.kinash.smartscales.indicators.Indicators
import dev.kinash.smartscales.ui.MeasurementEditRoute
import dev.kinash.smartscales.ui.common.Prefill
import dev.kinash.smartscales.ui.common.WeightInput
import dev.kinash.smartscales.ui.common.lengthInput
import dev.kinash.smartscales.ui.common.parseLength
import dev.kinash.smartscales.ui.common.resolve
import dev.kinash.smartscales.ui.profile.ProfileEditViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject

/** Circumferences from [ManualFields] shown in the form. */
val CircumferenceFields = listOf(ManualFields.WAIST_CM, ManualFields.HIP_CM, ManualFields.NECK_CM)

data class MeasurementForm(
    val date: LocalDate,
    val time: LocalTime,
    val weight: WeightInput = WeightInput(),
    /** Height in the snapshot; empty for inbox measurements that have no snapshot yet. */
    val height: String = "",
    val circumferences: Map<String, String> = emptyMap(),
    /** Text of the fields the formula sets declare, by input id. */
    val inputs: Map<String, String> = emptyMap(),
    val note: String = "",
    val tags: List<String> = emptyList(),
    val newTag: String = "",
)

enum class MeasurementField {
    DATE_TIME,

    /** The date is before the birth date in the snapshot; also marks [DATE_TIME]. */
    BEFORE_BIRTH,
    WEIGHT,
    HEIGHT,
    CIRCUMFERENCES,

    /** A field of a formula set; [MeasurementEditState.invalidInputs] says which. */
    INPUTS,
}

data class MeasurementEditState(
    val isNew: Boolean,
    val loaded: Boolean = false,
    /** Profile the measurement belongs to; null in the inbox. */
    val profile: Profile? = null,
    val hasSnapshot: Boolean = true,
    val form: MeasurementForm = MeasurementForm(LocalDate.MIN, LocalTime.MIDNIGHT),
    /** Fields the enabled formula sets declare, in their order, the lowest set first. */
    val inputs: List<InputDef> = emptyList(),
    val errors: Set<MeasurementField> = emptySet(),
    /** Ids of the set fields whose text is not a valid value. */
    val invalidInputs: Set<String> = emptySet(),
    val confirmDelete: Boolean = false,
    /** Profiles to move the measurement to, while the choice is shown. */
    val transferTargets: List<Profile>? = null,
    /** Name of the profile the measurement was just moved to. */
    val transferredTo: String? = null,
    /** A save, move or delete is running: further taps are ignored. */
    val saving: Boolean = false,
    val done: Boolean = false,
) {
    val weightUnit: WeightUnit get() = profile?.weightUnit ?: WeightUnit.KILOGRAM
    val lengthUnit: LengthUnit get() = profile?.lengthUnit ?: LengthUnit.CENTIMETRE
}

@HiltViewModel
class MeasurementEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val measurements: MeasurementRepository,
    private val profiles: ProfileRepository,
    private val indicators: Indicators,
    private val clock: Clock,
) : ViewModel() {
    private val route = savedStateHandle.toRoute<MeasurementEditRoute>()
    private var stored: Measurement? = null
    private var zone: ZoneId = ZoneId.systemDefault()
    private var weightPrefill: Prefill<WeightInput>? = null
    private var heightPrefill: Prefill<String>? = null
    private var circumferencePrefills: Map<String, Prefill<String>> = emptyMap()
    private var inputPrefills: Map<String, Prefill<String>> = emptyMap()

    private val _state = MutableStateFlow(MeasurementEditState(isNew = route.measurementId == null))
    val state: StateFlow<MeasurementEditState> = _state.asStateFlow()

    /** Tags to pick from: all known ones plus those just added in the form. */
    val tagChoices: StateFlow<List<String>> = combine(measurements.observeTags(), _state) { known, state ->
        (known + state.form.tags).distinctBy { it.lowercase() }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        // A field the sets have as a standard one (only `hip`, stored under its own id) is the form's own
        val inputs = indicators.loadedModel().inputs.values.filter { it.id !in CircumferenceFields }
        _state.update { it.copy(inputs = inputs) }
        val id = route.measurementId
        if (id == null) {
            val profile = route.profileId?.let { profiles.get(it) }
            if (profile == null) {
                _state.update { it.copy(done = true) }
                return
            }
            val now = clock.instant().atZone(zone)
            val height = lengthInput(profile.heightCm, profile.lengthUnit)
            heightPrefill = Prefill(height, profile.heightCm)
            // The circumferences the profile keeps, as a weighing from the scale would get them
            val kept = profiles.getManualValues(profile.id).filterKeys { it in CircumferenceFields }
            val circumferences = CircumferenceFields.associateWith { lengthInput(kept[it], profile.lengthUnit) }
            circumferencePrefills = kept.mapValues { (field, cm) -> Prefill(circumferences.getValue(field), cm) }
            val form = MeasurementForm(
                date = now.toLocalDate(),
                time = now.toLocalTime().truncatedTo(ChronoUnit.MINUTES),
                height = height,
                circumferences = circumferences,
            )
            _state.update { it.copy(loaded = true, profile = profile, form = form) }
            return
        }
        val measurement = measurements.get(id)
        if (measurement == null) {
            _state.update { it.copy(done = true) }
            return
        }
        stored = measurement
        zone = measurement.zone
        val profile = measurement.profileId?.let { profiles.get(it) }
        val weightUnit = profile?.weightUnit ?: WeightUnit.KILOGRAM
        val lengthUnit = profile?.lengthUnit ?: LengthUnit.CENTIMETRE
        val local = measurement.takenAt.atZone(zone)
        val weight = WeightInput.of(measurement.weightKg, weightUnit)
        weightPrefill = Prefill(weight, measurement.weightKg)
        val height = lengthInput(measurement.person?.heightCm, lengthUnit)
        heightPrefill = measurement.person?.let { Prefill(height, it.heightCm) }
        val circumferences = CircumferenceFields.associateWith { lengthInput(measurement.manualValues[it], lengthUnit) }
        circumferencePrefills = CircumferenceFields.mapNotNull { field ->
            measurement.manualValues[field]?.let { field to Prefill(circumferences.getValue(field), it) }
        }.toMap()
        val inputTexts = inputs.associate { input ->
            input.id to setInputText(measurement.manualValues[input.id], input.unit, weightUnit, lengthUnit)
        }
        inputPrefills = inputs.mapNotNull { input ->
            measurement.manualValues[input.id]?.let { input.id to Prefill(inputTexts.getValue(input.id), it) }
        }.toMap()
        val form = MeasurementForm(
            date = local.toLocalDate(),
            time = local.toLocalTime().truncatedTo(ChronoUnit.MINUTES),
            weight = weight,
            height = height,
            circumferences = circumferences,
            inputs = inputTexts,
            note = measurement.note.orEmpty(),
            tags = measurement.tags,
        )
        _state.update {
            it.copy(loaded = true, profile = profile, hasSnapshot = measurement.person != null, form = form)
        }
    }

    fun edit(transform: (MeasurementForm) -> MeasurementForm) {
        _state.update { it.copy(form = transform(it.form)) }
    }

    fun toggleTag(tag: String) = edit { form ->
        val present = form.tags.any { it.equals(tag, ignoreCase = true) }
        form.copy(tags = if (present) form.tags.filterNot { it.equals(tag, ignoreCase = true) } else form.tags + tag)
    }

    fun addNewTag() = edit { form ->
        val tag = form.newTag.trim()
        if (tag.isEmpty() || form.tags.any { it.equals(tag, ignoreCase = true) }) {
            form.copy(newTag = "")
        } else {
            form.copy(tags = form.tags + tag, newTag = "")
        }
    }

    fun save() {
        if (_state.value.saving) return
        val measurement = validated() ?: return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            if (measurement.id == 0L) measurements.add(measurement) else measurements.update(measurement)
            _state.update { it.copy(done = true) }
        }
    }

    /** The measurement as edited in the form, or null with [MeasurementEditState.errors] set. */
    private fun validated(): Measurement? {
        val state = _state.value
        val form = state.form
        // Values stay exactly as stored while their rounded text is not edited
        val weightKg = weightPrefill.resolve(form.weight) { it.toKilograms(state.weightUnit) }
        val heightCm = heightPrefill.resolve(form.height) { parseLength(it, state.lengthUnit) }
        // Blank means not measured; text that does not parse becomes an invalid value
        val circumferences = CircumferenceFields.associateWith { field ->
            form.circumferences[field]
                ?.takeIf { it.isNotBlank() }
                ?.let { text -> circumferencePrefills[field].resolve(text) { parseLength(it, state.lengthUnit) } ?: INVALID }
        }
        // The same for the sets' fields
        val inputs = state.inputs.associate { input ->
            input.id to form.inputs[input.id]?.takeIf { it.isNotBlank() }?.let { text ->
                inputPrefills[input.id].resolve(text) { parseSetInput(it, input.unit, state.weightUnit, state.lengthUnit) }
            }
        }
        val invalidInputs = state.inputs
            .filter { form.inputs[it.id]?.isNotBlank() == true && inputs[it.id] == null }
            .map { it.id }
            .toSet()
        val takenAt = takenAt(form)
        val birthDate = if (stored == null) state.profile?.birthDate else stored?.person?.birthDate
        val errors = buildSet {
            if (takenAt.isAfter(clock.instant().plus(1, ChronoUnit.MINUTES))) add(MeasurementField.DATE_TIME)
            if (birthDate != null && form.date.isBefore(birthDate)) {
                add(MeasurementField.DATE_TIME)
                add(MeasurementField.BEFORE_BIRTH)
            }
            if (weightKg == null || weightKg !in ProfileEditViewModel.WEIGHT_RANGE_KG) add(MeasurementField.WEIGHT)
            if (state.hasSnapshot && (heightCm == null || heightCm !in ProfileEditViewModel.HEIGHT_RANGE_CM)) {
                add(MeasurementField.HEIGHT)
            }
            if (circumferences.values.any { it != null && it !in CIRCUMFERENCE_RANGE_CM }) {
                add(MeasurementField.CIRCUMFERENCES)
            }
            if (invalidInputs.isNotEmpty()) add(MeasurementField.INPUTS)
        }
        _state.update { it.copy(errors = errors, invalidInputs = invalidInputs) }
        if (errors.isNotEmpty()) return null

        val old = stored
        // Values of fields no enabled set declares now stay as they were: switching the set on brings them back
        val manualValues = (old?.manualValues.orEmpty() - CircumferenceFields.toSet() - inputs.keys) +
            circumferences.mapNotNull { (field, cm) -> cm?.let { field to it } } +
            inputs.mapNotNull { (id, value) -> value?.let { id to it } }
        val note = form.note.trim().ifEmpty { null }
        return if (old == null) {
            val profile = state.profile!!
            Measurement(
                profileId = profile.id,
                takenAt = takenAt,
                zone = zone,
                source = MeasurementSource.MANUAL,
                weightKg = weightKg!!,
                person = PersonSnapshot(heightCm!!, profile.sex, profile.birthDate),
                manualValues = manualValues,
                note = note,
                tags = form.tags,
            )
        } else {
            old.copy(
                takenAt = takenAt,
                weightKg = weightKg!!,
                person = old.person?.copy(heightCm = heightCm!!),
                manualValues = manualValues,
                note = note,
                tags = form.tags,
            )
        }
    }

    /** The stored instant stays exact (with seconds) unless the date or time was changed. */
    private fun takenAt(form: MeasurementForm): Instant {
        val original = stored?.takenAt?.atZone(zone)
        val unchanged = original != null &&
            original.toLocalDate() == form.date &&
            original.toLocalTime().truncatedTo(ChronoUnit.MINUTES) == form.time
        return if (unchanged) original.toInstant() else LocalDateTime.of(form.date, form.time).atZone(zone).toInstant()
    }

    fun requestDelete() = _state.update { it.copy(confirmDelete = true) }

    fun dismissDelete() = _state.update { it.copy(confirmDelete = false) }

    fun confirmDelete() {
        val id = stored?.id ?: return
        if (_state.value.saving) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            measurements.delete(id)
            _state.update { it.copy(confirmDelete = false, done = true) }
        }
    }

    fun requestTransfer() {
        viewModelScope.launch {
            val current = _state.value.profile?.id
            val targets = profiles.observeProfiles().first().filter { it.id != current }
            _state.update { it.copy(transferTargets = targets) }
        }
    }

    fun dismissTransfer() = _state.update { it.copy(transferTargets = null) }

    /**
     * Saves the edits in the form, so none are lost, and moves the measurement to [target] with the snapshot
     * taken from it in one step, then reloads the form, so the height suggested for the target profile can be
     * checked. Nothing moves while the form has errors or the target was born after the measurement date.
     */
    fun transfer(target: Profile) {
        if (stored == null || _state.value.saving) return
        val edited = validated()
        if (edited == null || !target.isBornBy(edited.localDate)) {
            dismissTransfer()
            return
        }
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            measurements.updateAndTransfer(edited, target.id)
            _state.update { it.copy(transferTargets = null, transferredTo = target.name, saving = false) }
            load()
        }
    }

    fun transferMessageShown() = _state.update { it.copy(transferredTo = null) }

    companion object {
        val CIRCUMFERENCE_RANGE_CM = 10.0..300.0
        private const val INVALID = -1.0
    }
}
