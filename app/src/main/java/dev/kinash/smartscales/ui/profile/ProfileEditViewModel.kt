package dev.kinash.smartscales.ui.profile

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kinash.smartscales.core.data.Profile
import dev.kinash.smartscales.core.data.repository.ProfileRepository
import dev.kinash.smartscales.core.data.repository.SettingsRepository
import dev.kinash.smartscales.core.model.LengthUnit
import dev.kinash.smartscales.core.model.Sex
import dev.kinash.smartscales.core.model.WeightUnit
import dev.kinash.smartscales.ui.ProfileEditRoute
import dev.kinash.smartscales.ui.common.Prefill
import dev.kinash.smartscales.ui.common.ProfileColors
import dev.kinash.smartscales.ui.common.WeightInput
import dev.kinash.smartscales.ui.common.lengthInput
import dev.kinash.smartscales.ui.common.parseLength
import dev.kinash.smartscales.ui.common.resolve
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

data class ProfileForm(
    val name: String = "",
    val sex: Sex? = null,
    val birthDate: LocalDate? = null,
    val height: String = "",
    val weightUnit: WeightUnit = WeightUnit.KILOGRAM,
    val lengthUnit: LengthUnit = LengthUnit.CENTIMETRE,
    val color: Int = ProfileColors.first(),
    val avatar: String = "",
    val goal: WeightInput = WeightInput(),
)

enum class ProfileField { NAME, SEX, BIRTH_DATE, HEIGHT, GOAL }

/** Sex or birth date changed: whether past measurements get them too. */
data class ApplyToPastQuestion(
    /** Past measurements dated before the new birth date; when there are any, only new ones can get it. */
    val beforeBirthDate: Int,
)

data class ProfileEditState(
    val isNew: Boolean,
    val loaded: Boolean = false,
    val form: ProfileForm = ProfileForm(),
    val errors: Set<ProfileField> = emptySet(),
    val applyToPast: ApplyToPastQuestion? = null,
    /** Number of measurements the profile deletion would remove, while the warning is shown. */
    val deleteWarning: Int? = null,
    /** A save or delete is running: further taps are ignored. */
    val saving: Boolean = false,
    val done: Boolean = false,
)

@HiltViewModel
class ProfileEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val profiles: ProfileRepository,
    private val settings: SettingsRepository,
    private val clock: Clock,
) : ViewModel() {
    private val profileId = savedStateHandle.toRoute<ProfileEditRoute>().profileId
    private var stored: Profile? = null
    private var pending: Profile? = null
    private var heightPrefill: Prefill<String>? = null
    private var goalPrefill: Prefill<WeightInput>? = null

    private val _state = MutableStateFlow(ProfileEditState(isNew = profileId == null, loaded = profileId == null))
    val state: StateFlow<ProfileEditState> = _state.asStateFlow()

    init {
        if (profileId != null) {
            viewModelScope.launch {
                val profile = profiles.get(profileId)
                if (profile == null) {
                    _state.update { it.copy(done = true) }
                    return@launch
                }
                stored = profile
                val height = lengthInput(profile.heightCm, profile.lengthUnit)
                heightPrefill = Prefill(height, profile.heightCm)
                val goalKg = profiles.getGoal(profile.id, ProfileRepository.WEIGHT_GOAL)
                val goal = WeightInput.of(goalKg, profile.weightUnit)
                goalPrefill = goalKg?.let { Prefill(goal, it) }
                val form = ProfileForm(
                    name = profile.name,
                    sex = profile.sex,
                    birthDate = profile.birthDate,
                    height = height,
                    weightUnit = profile.weightUnit,
                    lengthUnit = profile.lengthUnit,
                    color = profile.color,
                    avatar = profile.avatar.orEmpty(),
                    goal = goal,
                )
                _state.update { it.copy(loaded = true, form = form) }
            }
        }
    }

    fun edit(transform: (ProfileForm) -> ProfileForm) {
        _state.update { it.copy(form = transform(it.form)) }
    }

    /** Switches the unit and converts the height already typed. */
    fun setLengthUnit(unit: LengthUnit) {
        val cm = heightCm(_state.value.form)
        if (cm == null) {
            edit { it.copy(lengthUnit = unit) }
            return
        }
        val height = lengthInput(cm, unit)
        heightPrefill = Prefill(height, cm)
        edit { it.copy(lengthUnit = unit, height = height) }
    }

    /** Switches the unit and converts the goal already typed. */
    fun setWeightUnit(unit: WeightUnit) {
        val kg = goalKg(_state.value.form)
        val goal = WeightInput.of(kg, unit)
        goalPrefill = kg?.let { Prefill(goal, it) }
        edit { it.copy(weightUnit = unit, goal = goal) }
    }

    fun save() {
        if (_state.value.saving) return
        val form = _state.value.form
        val today = LocalDate.now(clock.withZone(ZoneId.systemDefault()))
        val heightCm = heightCm(form)
        val errors = buildSet {
            if (form.name.isBlank()) add(ProfileField.NAME)
            if (form.sex == null) add(ProfileField.SEX)
            if (form.birthDate == null || form.birthDate.isAfter(today) || form.birthDate.year < MIN_BIRTH_YEAR) {
                add(ProfileField.BIRTH_DATE)
            }
            if (heightCm == null || heightCm !in HEIGHT_RANGE_CM) add(ProfileField.HEIGHT)
            if (!form.goal.isBlank && goalKg(form)?.let { it in WEIGHT_RANGE_KG } != true) add(ProfileField.GOAL)
        }
        _state.update { it.copy(errors = errors) }
        if (errors.isNotEmpty()) return

        val profile = Profile(
            id = profileId ?: 0,
            name = form.name.trim(),
            sex = form.sex!!,
            birthDate = form.birthDate!!,
            heightCm = heightCm!!,
            weightUnit = form.weightUnit,
            lengthUnit = form.lengthUnit,
            color = form.color,
            avatar = form.avatar.trim().ifEmpty { null },
            createdAt = stored?.createdAt ?: clock.instant(),
        )
        val old = stored
        if (old != null && ProfileRepository.changesPastSnapshots(old, profile)) {
            pending = profile
            _state.update { it.copy(saving = true) }
            viewModelScope.launch {
                val beforeBirthDate = profiles.countMeasurementsBefore(profile.id, profile.birthDate)
                _state.update { it.copy(saving = false, applyToPast = ApplyToPastQuestion(beforeBirthDate)) }
            }
        } else {
            persist(profile, applyToPastMeasurements = false)
        }
    }

    /** Answer to "apply to past measurements?"; past ones get the change only when nothing predates the birth date. */
    fun applyToPast(apply: Boolean) {
        val profile = pending ?: return
        val question = _state.value.applyToPast ?: return
        pending = null
        _state.update { it.copy(applyToPast = null) }
        persist(profile, apply && question.beforeBirthDate == 0)
    }

    fun dismissApplyToPast() {
        pending = null
        _state.update { it.copy(applyToPast = null) }
    }

    fun requestDelete() {
        val id = profileId ?: return
        viewModelScope.launch {
            val count = profiles.countMeasurements(id)
            _state.update { it.copy(deleteWarning = count) }
        }
    }

    fun dismissDelete() = _state.update { it.copy(deleteWarning = null) }

    fun confirmDelete() {
        val id = profileId ?: return
        if (_state.value.saving) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            profiles.delete(id)
            _state.update { it.copy(deleteWarning = null, done = true) }
        }
    }

    private fun persist(profile: Profile, applyToPastMeasurements: Boolean) {
        val goalKg = goalKg(_state.value.form)
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            val id = if (profile.id == 0L) {
                profiles.add(profile).also { settings.selectProfile(it) }
            } else {
                profiles.update(profile, applyToPastMeasurements)
                profile.id
            }
            profiles.setGoal(id, ProfileRepository.WEIGHT_GOAL, goalKg)
            _state.update { it.copy(done = true) }
        }
    }

    private fun heightCm(form: ProfileForm): Double? = heightPrefill.resolve(form.height) { parseLength(it, form.lengthUnit) }

    private fun goalKg(form: ProfileForm): Double? = goalPrefill.resolve(form.goal) { it.toKilograms(form.weightUnit) }

    companion object {
        val HEIGHT_RANGE_CM = 30.0..250.0
        val WEIGHT_RANGE_KG = 1.0..400.0
        const val MIN_BIRTH_YEAR = 1900
    }
}
