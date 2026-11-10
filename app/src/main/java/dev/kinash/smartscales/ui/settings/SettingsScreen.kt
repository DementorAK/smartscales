package dev.kinash.smartscales.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kinash.smartscales.R
import dev.kinash.smartscales.core.data.Profile
import dev.kinash.smartscales.core.data.repository.ProfileRepository
import dev.kinash.smartscales.core.data.repository.SettingsRepository
import dev.kinash.smartscales.core.model.WeightUnit
import dev.kinash.smartscales.ui.common.BackButton
import dev.kinash.smartscales.ui.common.FieldSpacing
import dev.kinash.smartscales.ui.common.ProfileAvatar
import dev.kinash.smartscales.ui.common.ScreenPadding
import dev.kinash.smartscales.ui.common.formatNumber
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.math.roundToInt

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    profiles: ProfileRepository,
) : ViewModel() {
    /** Null until read from disk, so the slider does not jump from the default. */
    val matchThresholdKg: StateFlow<Double?> =
        settings.matchThresholdKg.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Everyone's profiles, to open their settings from here. */
    val profiles: StateFlow<List<Profile>> =
        profiles.observeProfiles().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The weight unit of the profile on the main screen, to show the threshold in it as well. */
    val weightUnit: StateFlow<WeightUnit> =
        combine(this.profiles, settings.selectedProfileId) { all, id ->
            (all.firstOrNull { it.id == id } ?: all.firstOrNull())?.weightUnit ?: WeightUnit.KILOGRAM
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WeightUnit.KILOGRAM)

    /** Saved even when the screen is left right after releasing the slider and this model is cleared. */
    fun setMatchThreshold(kg: Double) {
        viewModelScope.launch { withContext(NonCancellable) { settings.setMatchThreshold(snapThreshold(kg)) } }
    }

    companion object {
        const val MIN_THRESHOLD_KG = 0.5
        const val MAX_THRESHOLD_KG = 10.0
        const val THRESHOLD_STEP_KG = 0.5

        /** Within the slider range, on its steps. */
        fun snapThreshold(kg: Double): Double =
            ((kg / THRESHOLD_STEP_KG).roundToInt() * THRESHOLD_STEP_KG).coerceIn(MIN_THRESHOLD_KG, MAX_THRESHOLD_KG)
    }
}

/**
 * All settings in one place, opened by the gear on the main screen (requirements §6.2a): profiles with their
 * formulas, scales, formula sets, weighing and debugging. Profiles are edited and deleted only here; the weighing screen also
 * opens the scales.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenProfile: (Long) -> Unit,
    onAddProfile: () -> Unit,
    onOpenScales: () -> Unit,
    onOpenLog: () -> Unit,
    onOpenFormulaSets: () -> Unit,
    onOpenChildNorms: () -> Unit,
    onOpenBackup: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val stored by viewModel.matchThresholdKg.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val unit by viewModel.weightUnit.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = { BackButton(onBack) },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(vertical = ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(FieldSpacing),
        ) {
            Column(Modifier.padding(horizontal = ScreenPadding)) {
                SectionTitle(stringResource(R.string.settings_profiles))
            }
            profiles.forEach { profile ->
                SettingsItem(
                    title = profile.name,
                    hint = stringResource(R.string.settings_profile_hint),
                    leading = { ProfileAvatar(profile) },
                    onClick = { onOpenProfile(profile.id) },
                )
            }
            SettingsItem(
                title = stringResource(R.string.profile_new),
                leading = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
                onClick = onAddProfile,
            )
            Column(Modifier.padding(horizontal = ScreenPadding).padding(top = ScreenPadding)) {
                SectionTitle(stringResource(R.string.settings_scales))
            }
            SettingsItem(
                title = stringResource(R.string.scales_title),
                hint = stringResource(R.string.settings_scales_hint),
                onClick = onOpenScales,
            )
            Column(Modifier.padding(horizontal = ScreenPadding).padding(top = ScreenPadding)) {
                SectionTitle(stringResource(R.string.settings_formulas))
            }
            SettingsItem(
                title = stringResource(R.string.formula_sets_title),
                hint = stringResource(R.string.settings_formulas_hint),
                onClick = onOpenFormulaSets,
            )
            SettingsItem(
                title = stringResource(R.string.child_norms_title),
                hint = stringResource(R.string.settings_child_norms_hint),
                onClick = onOpenChildNorms,
            )
            Column(
                Modifier.padding(horizontal = ScreenPadding).padding(top = ScreenPadding),
                verticalArrangement = Arrangement.spacedBy(FieldSpacing),
            ) {
                SectionTitle(stringResource(R.string.settings_weighing))
                stored?.let { ThresholdSetting(it, unit, viewModel::setMatchThreshold) }
            }
            Column(Modifier.padding(horizontal = ScreenPadding).padding(top = ScreenPadding)) {
                SectionTitle(stringResource(R.string.settings_data))
            }
            SettingsItem(
                title = stringResource(R.string.backup_title),
                hint = stringResource(R.string.settings_backup_hint),
                onClick = onOpenBackup,
            )
            Column(Modifier.padding(horizontal = ScreenPadding).padding(top = ScreenPadding)) {
                SectionTitle(stringResource(R.string.settings_debug))
            }
            // People asked to "send the Bluetooth log" look for it here; it is also on the scales screen
            SettingsItem(
                title = stringResource(R.string.btlog_title),
                hint = stringResource(R.string.settings_btlog_hint),
                onClick = onOpenLog,
            )
        }
    }
}

@Composable
private fun SettingsItem(title: String, onClick: () -> Unit, hint: String? = null, leading: (@Composable () -> Unit)? = null) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = hint?.let { { Text(it) } },
        leadingContent = leading,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

/**
 * The slider saves when released, so dragging does not write on every step. The dragged value stays shown
 * until the saved one arrives, so the slider does not jump back for a moment.
 */
@Composable
private fun ThresholdSetting(storedKg: Double, unit: WeightUnit, onChange: (Double) -> Unit) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(storedKg) { dragging = null }
    val kg = dragging?.toDouble()?.let(SettingsViewModel::snapThreshold) ?: storedKg
    val kgText = formatNumber(kg, 1, 1)
    // A threshold of a few kilograms reads in pounds for stones too
    val value = if (unit == WeightUnit.KILOGRAM) {
        stringResource(R.string.settings_match_threshold_kg, kgText)
    } else {
        stringResource(R.string.settings_match_threshold_kg_lb, kgText, formatNumber(WeightUnit.POUND.fromKilograms(kg), 1, 1))
    }
    Text(stringResource(R.string.settings_match_threshold), style = MaterialTheme.typography.bodyLarge)
    Text(value, style = MaterialTheme.typography.headlineSmall)
    Slider(
        value = dragging ?: storedKg.toFloat(),
        onValueChange = { dragging = it },
        onValueChangeFinished = {
            val released = dragging?.toDouble()?.let(SettingsViewModel::snapThreshold)
            when (released) {
                null -> Unit
                // Released where it was: nothing to save, and no saved value will come to clear the drag
                storedKg -> dragging = null
                else -> onChange(released)
            }
        },
        valueRange = SettingsViewModel.MIN_THRESHOLD_KG.toFloat()..SettingsViewModel.MAX_THRESHOLD_KG.toFloat(),
        steps = ((SettingsViewModel.MAX_THRESHOLD_KG - SettingsViewModel.MIN_THRESHOLD_KG) / SettingsViewModel.THRESHOLD_STEP_KG).roundToInt() - 1,
        modifier = Modifier.semantics { stateDescription = value },
    )
    Text(
        stringResource(R.string.settings_match_threshold_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
