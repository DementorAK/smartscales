package dev.kinash.smartscales.ui.weighing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kinash.smartscales.R
import dev.kinash.smartscales.core.data.Profile
import dev.kinash.smartscales.core.model.Impedance
import dev.kinash.smartscales.ui.common.BackButton
import dev.kinash.smartscales.ui.common.FieldSpacing
import dev.kinash.smartscales.ui.common.ProfileChoiceList
import dev.kinash.smartscales.ui.common.ScreenPadding
import dev.kinash.smartscales.ui.common.formatNumber
import dev.kinash.smartscales.ui.common.scaleWeightText
import dev.kinash.smartscales.ui.history.EmptyState
import dev.kinash.smartscales.ui.scale.BluetoothGate
import dev.kinash.smartscales.ui.scale.ScanWhileVisible

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeighingScreen(
    onBack: () -> Unit,
    onOpenScales: () -> Unit,
    onAddScale: () -> Unit,
    onOpenScale: (Long) -> Unit,
    onCreateProfile: (Long) -> Unit,
    onOpenLog: () -> Unit,
    viewModel: WeighingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val logRecording by viewModel.logRecording.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    var retry by rememberSaveable { mutableIntStateOf(0) }

    LaunchedEffect(state.saved) {
        val saved = state.saved ?: return@LaunchedEffect
        val message = saved.profile?.let { resources.getString(R.string.weighing_saved, it.name) }
            ?: resources.getString(R.string.weighing_saved_inbox)
        // Cleared only after showing: clearing first changes the key and cancels this effect
        val result = snackbar.showSnackbar(
            message = message,
            actionLabel = saved.profile?.let { resources.getString(R.string.weighing_not_me) },
            duration = SnackbarDuration.Long,
        )
        if (result == SnackbarResult.ActionPerformed) viewModel.notMe(saved) else viewModel.savedShown()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.weighing_title)) },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    TextButton(onClick = onOpenScales) { Text(stringResource(R.string.scales_title)) }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            when {
                !state.loaded -> Unit
                state.scales.isEmpty() -> EmptyState(
                    text = stringResource(R.string.weighing_no_scales),
                    action = stringResource(R.string.scales_add),
                    onAction = onAddScale,
                )
                else -> BluetoothGate(viewModel.scanner) {
                    ScanWhileVisible(retry) { viewModel.scan() }
                    val error = state.scanError
                    if (error != null) {
                        EmptyState(stringResource(R.string.bt_scan_failed, error), stringResource(R.string.bt_retry)) { retry++ }
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(ScreenPadding),
                            verticalArrangement = Arrangement.spacedBy(ScreenPadding),
                        ) {
                            // A forgotten log keeps growing with every weighing, so it is visible here
                            if (logRecording) {
                                item {
                                    AssistChip(
                                        onClick = onOpenLog,
                                        label = { Text(stringResource(R.string.weighing_log_recording)) },
                                    )
                                }
                            }
                            items(state.scales, key = { it.binding.id }) { scale ->
                                ScaleCard(
                                    scale = scale,
                                    onOpenScale = { onOpenScale(scale.binding.id) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    val keyCheck = state.keyChecks.firstOrNull()
    val scaleQuestion = state.scaleQuestion
    if (keyCheck != null) {
        AlertDialog(
            // Only an answer decides whether the weighing is saved
            onDismissRequest = {},
            title = { Text(stringResource(R.string.weighing_key_check_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(ScreenPadding)) {
                    keyCheck.reading.weight?.let {
                        Text(scaleWeightText(it), style = MaterialTheme.typography.headlineSmall)
                    }
                    Text(stringResource(R.string.weighing_key_check_text))
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.answerKeyCheck(keyCheck, matches = true) }) {
                    Text(stringResource(R.string.weighing_key_check_yes))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.answerKeyCheck(keyCheck, matches = false) }) {
                    Text(stringResource(R.string.weighing_key_check_no))
                }
            },
        )
    } else if (scaleQuestion != null) {
        // The scale waits for this answer, so it comes before questions about weighings already saved
        ScaleQuestionDialog(scaleQuestion, onAnswer = { viewModel.answerScale(scaleQuestion, it) })
    } else {
        state.questions.firstOrNull()?.let { question ->
            WhoDialog(
                question = question,
                onAnswer = { viewModel.answer(question, it) },
                onCreateProfile = {
                    viewModel.answerWithNewProfile(question)
                    onCreateProfile(question.measurementId)
                },
            )
        }
    }
}

/**
 * The scale asks who stands on it. Dismissing leaves the choice to the app, which picks the closest by last
 * weight when the time is up.
 */
@Composable
private fun ScaleQuestionDialog(question: ScaleUserQuestion, onAnswer: (Profile) -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.weighing_scale_question_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(ScreenPadding)) {
                Text(scaleWeightText(question.weight), style = MaterialTheme.typography.headlineSmall)
                Text(
                    pluralStringResource(R.plurals.weighing_scale_question_text, question.timeoutSeconds, question.timeoutSeconds),
                    style = MaterialTheme.typography.bodyMedium,
                )
                ProfileChoiceList(question.candidates, onSelect = onAnswer)
            }
        },
        confirmButton = {},
    )
}

/** The weighing is in the inbox already: dismissing leaves it there. */
@Composable
private fun WhoDialog(question: WhoQuestion, onAnswer: (Profile?) -> Unit, onCreateProfile: () -> Unit) {
    AlertDialog(
        onDismissRequest = { onAnswer(null) },
        title = { Text(stringResource(R.string.inbox_assign_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(ScreenPadding)) {
                Text(scaleWeightText(question.weight), style = MaterialTheme.typography.headlineSmall)
                ProfileChoiceList(question.choices, onSelect = onAnswer, onCreate = onCreateProfile)
            }
        },
        confirmButton = {
            TextButton(onClick = { onAnswer(null) }) {
                Text(stringResource(R.string.weighing_to_inbox))
            }
        },
    )
}

@Composable
private fun ScaleCard(scale: ScaleView, onOpenScale: () -> Unit) {
    val reading = scale.reading
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(FieldSpacing),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(scale.title, style = MaterialTheme.typography.titleMedium)
            val weight = reading?.weight
            when {
                reading?.needsKey == true || scale.status == ScaleStatus.WRONG_KEY -> {
                    Text(
                        stringResource(
                            if (scale.status == ScaleStatus.WRONG_KEY) R.string.weighing_wrong_key else R.string.weighing_needs_key,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = onOpenScale) { Text(stringResource(R.string.scale_edit_title)) }
                }
                weight == null -> Text(
                    stringResource(
                        when {
                            reading != null -> R.string.weighing_measuring
                            scale.connection == ConnectionStatus.CONNECTING -> R.string.weighing_connecting
                            else -> R.string.weighing_step_on
                        },
                    ),
                    style = MaterialTheme.typography.headlineSmall,
                )
                else -> {
                    Text(
                        scaleWeightText(weight),
                        style = MaterialTheme.typography.displayMedium,
                        color = if (scale.status == ScaleStatus.LIVE) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                    reading.impedances.firstOrNull { it.channel == Impedance.FOOT_TO_FOOT }?.let {
                        Text(
                            stringResource(R.string.weighing_impedance, formatNumber(it.ohms, 0, 1)),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    reading.heartRateBpm?.let {
                        Text(stringResource(R.string.weighing_heart_rate, it), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (scale.connection == ConnectionStatus.FAILED) {
                Text(
                    stringResource(R.string.weighing_connection_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (scale.historySaved > 0) {
                Text(
                    pluralStringResource(R.plurals.weighing_history_saved, scale.historySaved, scale.historySaved),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (scale.description?.canWeigh == false) {
                Text(stringResource(R.string.weighing_needs_connection), style = MaterialTheme.typography.bodySmall)
            }
            if (scale.description?.let { it.canWeigh && !it.testedOnRealScale } == true) {
                Text(
                    stringResource(R.string.scale_untested),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
