package dev.kinash.smartscales.ui.profile

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kinash.smartscales.core.data.Profile
import dev.kinash.smartscales.core.data.repository.MeasurementRepository
import dev.kinash.smartscales.core.data.repository.ProfileRepository
import dev.kinash.smartscales.core.data.repository.SettingsRepository
import dev.kinash.smartscales.core.formula.ChildGrowth
import dev.kinash.smartscales.core.formula.FormulaModel
import dev.kinash.smartscales.core.formula.LocalizedText
import dev.kinash.smartscales.core.formula.text
import dev.kinash.smartscales.core.model.LengthUnit
import dev.kinash.smartscales.core.model.Sex
import dev.kinash.smartscales.core.model.WeightUnit
import dev.kinash.smartscales.indicators.Indicators
import dev.kinash.smartscales.indicators.MeasuredIndicators
import dev.kinash.smartscales.ui.history.DEFAULT_CARDS
import dev.kinash.smartscales.ui.ProfileEditRoute
import dev.kinash.smartscales.ui.common.Prefill
import dev.kinash.smartscales.ui.common.ProfileColors
import dev.kinash.smartscales.ui.common.WeightInput
import dev.kinash.smartscales.ui.common.avatarText
import dev.kinash.smartscales.ui.common.lengthInput
import dev.kinash.smartscales.ui.common.parseLength
import dev.kinash.smartscales.ui.common.resolve
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
    /** Variant chosen for an indicator group; a group left out uses the formula set's first variant. */
    val variants: Map<String, String> = emptyMap(),
)

/**
 * An indicator group the profile can choose a variant for, with its variants in the set's order. [sexOnly]
 * marks variants that apply to one sex only, such as equations for women.
 */
data class VariantGroup(
    val id: String,
    val name: LocalizedText,
    val variants: List<Pair<String, LocalizedText>>,
    val sexOnly: Map<String, Sex> = emptyMap(),
    /** Variants that give a value only with an impedance from the scale. */
    val needsImpedance: Set<String> = emptySet(),
) {
    /** Whether the variants differ in needing an impedance, so the ones that need it are worth marking. */
    val marksImpedance: Boolean get() = needsImpedance.isNotEmpty() && needsImpedance.size < variants.size

    val default: String get() = variants.first().first

    /** Variants to offer a person of [sex]: those for one sex only are not offered to the other. */
    fun options(sex: Sex?): List<String> =
        variants.map { it.first }.filter { sex == null || (sexOnly[it] ?: sex) == sex }

    /** Whether [variant] is one of this group's and can be chosen for [sex]. */
    fun allows(variant: String, sex: Sex?): Boolean = variant in options(sex)

    /**
     * The variant's name without the group's: under the label "Body fat", "Body fat (Gallagher 2000, from BMI)"
     * reads as "Gallagher 2000, from BMI". A name that does not start with the group's is shown whole.
     */
    fun method(variant: String, language: String): String {
        val full = variants.firstOrNull { it.first == variant }?.second?.text(language) ?: return variant
        val group = name.text(language)
        val rest = full.removePrefix(group).trim()
        if (rest == full || !rest.startsWith("(") || !rest.endsWith(")")) return full
        return rest.removeSurrounding("(", ")").trim().replaceFirstChar { it.uppercase() }
    }
}

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
    val variantGroups: List<VariantGroup> = emptyList(),
    val errors: Set<ProfileField> = emptySet(),
    val applyToPast: ApplyToPastQuestion? = null,
    /** Number of measurements the profile deletion would remove, while the warning is shown. */
    val deleteWarning: Int? = null,
    /** The new profile gets an inbox weighing taken before the birth date entered. */
    val bornAfterWeighing: Boolean = false,
    /**
     * The profile's last weighing, when it is an adult's: the screen shows which variant it actually got where the
     * chosen one has no data for it, such as an equation by impedance for a weighing without one.
     */
    val lastWeighing: MeasuredIndicators? = null,
    /** A save or delete is running: further taps are ignored. */
    val saving: Boolean = false,
    val done: Boolean = false,
)

@HiltViewModel
class ProfileEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val profiles: ProfileRepository,
    private val measurements: MeasurementRepository,
    private val settings: SettingsRepository,
    private val clock: Clock,
    private val indicators: Indicators,
) : ViewModel() {
    private val route = savedStateHandle.toRoute<ProfileEditRoute>()
    private val profileId = route.profileId
    private var stored: Profile? = null
    private var pending: Profile? = null
    private var heightPrefill: Prefill<String>? = null
    private var goalPrefill: Prefill<WeightInput>? = null

    private val _state = MutableStateFlow(
        ProfileEditState(
            isNew = profileId == null,
            loaded = profileId == null,
            variantGroups = variantGroups(indicators.model, DEFAULT_CARDS),
        ),
    )
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
                    // A stored choice the formula sets no longer offer (a variant renamed, a set switched off)
                    // is dropped here, and saving clears it
                    variants = profiles.observeMainVariants(profile.id).first().filter { (group, variant) ->
                        _state.value.variantGroups.any { it.id == group && it.allows(variant, profile.sex) }
                    },
                )
                val last = indicators.observeHistory(profile.id).first().firstOrNull()?.takeIf { measured ->
                    (measured.inputs?.get("age") as? Double)?.let { it * MONTHS_PER_YEAR >= ChildGrowth.ADULT_MONTHS } == true
                }
                _state.update { it.copy(loaded = true, form = form, lastWeighing = last) }
            }
        }
    }

    fun edit(transform: (ProfileForm) -> ProfileForm) {
        _state.update { state ->
            val form = transform(state.form)
            // The warning is about the date that was saved; a new date clears it
            val bornAfterWeighing = state.bornAfterWeighing && form.birthDate == state.form.birthDate
            state.copy(form = form, bornAfterWeighing = bornAfterWeighing)
        }
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

    /**
     * For each group whose chosen variant (in [variants]) the last weighing could not use, the variant it used
     * instead, or null when none of them had data.
     */
    fun lastWeighingUse(variants: Map<String, String>, sex: Sex?): Map<String, String?> {
        val last = _state.value.lastWeighing ?: return emptyMap()
        val stored = last.inputs ?: return emptyMap()
        // The sex in the form, which may be changed before saving, instead of the one in the weighing's snapshot
        val inputs = if (sex == null) {
            stored
        } else {
            stored + mapOf("sex" to if (sex == Sex.MALE) 1.0 else 0.0, "male" to (sex == Sex.MALE), "female" to (sex == Sex.FEMALE))
        }
        val results = indicators.model.evaluate(inputs, variants)
        return _state.value.variantGroups.mapNotNull { group ->
            val chosen = variants[group.id] ?: group.default
            val used = results.group(group.id)?.first
            if (used == chosen) null else group.id to used
        }.toMap()
    }

    /** Chooses the variant of [group]; its first variant means "as the formula set says" and is not stored. */
    fun setVariant(group: VariantGroup, variant: String) = edit { form ->
        val variants = if (variant == group.default) form.variants - group.id else form.variants + (group.id to variant)
        form.copy(variants = variants)
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
        val assignId = route.assignMeasurementId
        if (profileId == null && assignId != null) {
            _state.update { it.copy(saving = true) }
            viewModelScope.launch {
                val weighing = measurements.get(assignId)
                val bornAfter = weighing != null && form.birthDate!!.isAfter(weighing.localDate)
                _state.update {
                    it.copy(
                        saving = false,
                        bornAfterWeighing = bornAfter,
                        errors = if (bornAfter) setOf(ProfileField.BIRTH_DATE) else emptySet(),
                    )
                }
                if (!bornAfter) persist(profileOf(form, heightCm!!), applyToPastMeasurements = false)
            }
            return
        }

        val profile = profileOf(form, heightCm!!)
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

    private fun profileOf(form: ProfileForm, heightCm: Double) = Profile(
        id = profileId ?: 0,
        name = form.name.trim(),
        sex = form.sex!!,
        birthDate = form.birthDate!!,
        heightCm = heightCm,
        weightUnit = form.weightUnit,
        lengthUnit = form.lengthUnit,
        color = form.color,
        avatar = avatarText(form.avatar).ifEmpty { null },
        createdAt = stored?.createdAt ?: clock.instant(),
    )

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
            val assignId = route.assignMeasurementId
            val id = when {
                profile.id != 0L -> {
                    profiles.update(profile, applyToPastMeasurements)
                    profile.id
                }
                assignId != null -> measurements.claimForNewProfile(assignId) { profiles.add(profile) }
                else -> profiles.add(profile)
            }
            if (profile.id == 0L) settings.selectProfile(id)
            profiles.setGoal(id, ProfileRepository.WEIGHT_GOAL, goalKg)
            // Every offered group is written, so a choice set back to the first variant or not offered to the
            // profile's sex is cleared; one transaction, one recalculation of the history
            val groups = _state.value.variantGroups
            val choices = _state.value.form.variants.filter { (group, variant) ->
                groups.any { it.id == group && it.allows(variant, profile.sex) }
            }
            profiles.setMainVariants(id, groups.map { it.id }, choices)
            _state.update { it.copy(done = true) }
        }
    }

    private fun heightCm(form: ProfileForm): Double? = heightPrefill.resolve(form.height) { parseLength(it, form.lengthUnit) }

    private fun goalKg(form: ProfileForm): Double? = goalPrefill.resolve(form.goal) { it.toKilograms(form.weightUnit) }

    companion object {
        /** Groups in the order a person thinks of them; groups of other sets follow in their own order. */
        private val GROUP_ORDER = listOf("bf", "ffm", "tbw", "bmr", "lbm")

        /**
         * Groups with more than one variant that change something on the screens: a group among [shown] or one
         * the shown values depend on (fat-free mass by impedance feeds body fat). Lean body mass is on no card
         * until profiles choose their cards in 0.6, so its choice would change nothing visible.
         */
        fun variantGroups(model: FormulaModel, shown: List<String>): List<VariantGroup> {
            val used = shown.flatMap { listOf(it) + model.dependencies(it) }.toSet()
            return model.groups
                .filter { (id, variants) -> variants.size > 1 && id in used }
                .entries
                .sortedBy { (id, _) -> GROUP_ORDER.indexOf(id).takeIf { it >= 0 } ?: GROUP_ORDER.size }
                .map { (id, variants) ->
                    VariantGroup(
                        id = id,
                        name = model.groupNames[id] ?: model.indicators.getValue(variants.first()).def.name,
                        variants = variants.map { it to model.indicators.getValue(it).def.name },
                        sexOnly = variants.mapNotNull { v -> sexOnly(model, v)?.let { v to it } }.toMap(),
                        needsImpedance = variants.filter { needsImpedance(model, it) }.toSet(),
                    )
                }
        }

        /**
         * The sex a variant is for, or null when it is for both: tried on a typical adult of each sex with every
         * standard input, a variant that gives a value for one sex only is for that sex. A variant that needs
         * inputs the try does not have gives no value for either and counts as for both.
         */
        private fun sexOnly(model: FormulaModel, variant: String): Sex? {
            val forMen = model.evaluate(typicalAdult(Sex.MALE)).value(variant) != null
            val forWomen = model.evaluate(typicalAdult(Sex.FEMALE)).value(variant) != null
            return when {
                forMen && !forWomen -> Sex.MALE
                forWomen && !forMen -> Sex.FEMALE
                else -> null
            }
        }

        /** Whether [variant] gives a value for a typical adult of either sex with an impedance and none without. */
        private fun needsImpedance(model: FormulaModel, variant: String): Boolean = Sex.entries.any { sex ->
            val with = typicalAdult(sex)
            model.evaluate(with).value(variant) != null && model.evaluate(with + ("impedance" to null)).value(variant) == null
        }

        private const val MONTHS_PER_YEAR = 12.0

        private fun typicalAdult(sex: Sex): Map<String, Any?> = mapOf(
            "weight" to 70.0, "height" to 170.0, "age" to 40.0, "sex" to if (sex == Sex.MALE) 1.0 else 0.0,
            "male" to (sex == Sex.MALE), "female" to (sex == Sex.FEMALE), "impedance" to 500.0,
            "heart_rate" to 70.0, "waist" to 85.0, "hips" to 100.0, "neck" to 37.0,
        )

        val HEIGHT_RANGE_CM = 30.0..250.0
        val WEIGHT_RANGE_KG = 1.0..400.0
        const val MIN_BIRTH_YEAR = 1900
    }
}
