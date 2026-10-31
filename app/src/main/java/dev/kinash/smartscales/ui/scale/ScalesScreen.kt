package dev.kinash.smartscales.ui.scale

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kinash.smartscales.R
import dev.kinash.smartscales.core.ble.DeviceCapability
import dev.kinash.smartscales.core.ble.ScaleDrivers
import dev.kinash.smartscales.core.data.ScaleBinding
import dev.kinash.smartscales.core.data.repository.ScaleBindingRepository
import dev.kinash.smartscales.ui.common.BackButton
import dev.kinash.smartscales.ui.history.EmptyState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** A paired scale with the name to show. */
data class ScaleItem(val binding: ScaleBinding, val title: String)

/** Name to show for a paired scale: the one the user gave, or the protocol's. */
fun ScaleDrivers.titleOf(binding: ScaleBinding): String =
    binding.name ?: describe(binding.protocol)?.displayName ?: binding.protocol

@StringRes
fun DeviceCapability.title(): Int = when (this) {
    DeviceCapability.LIVE_WEIGHT -> R.string.cap_live_weight
    DeviceCapability.BODY_COMPOSITION -> R.string.cap_body_composition
    DeviceCapability.HEART_RATE -> R.string.cap_heart_rate
    DeviceCapability.USER_SYNC -> R.string.cap_user_sync
    DeviceCapability.HISTORY_READ -> R.string.cap_history_read
    DeviceCapability.TIME_SYNC -> R.string.cap_time_sync
    DeviceCapability.UNIT_CONFIG -> R.string.cap_unit_config
}

@HiltViewModel
class ScalesViewModel @Inject constructor(bindings: ScaleBindingRepository, drivers: ScaleDrivers) : ViewModel() {
    val scales: StateFlow<List<ScaleItem>?> = bindings.observeBound()
        .map { list -> list.map { ScaleItem(it, drivers.titleOf(it)) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScalesScreen(
    onBack: () -> Unit,
    onAddScale: () -> Unit,
    onOpenScale: (Long) -> Unit,
    viewModel: ScalesViewModel = hiltViewModel(),
) {
    val scales by viewModel.scales.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.scales_title)) }, navigationIcon = { BackButton(onBack) })
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddScale) {
                Icon(painterResource(R.drawable.ic_add), stringResource(R.string.scales_add))
            }
        },
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            val list = scales ?: return@Box
            if (list.isEmpty()) {
                EmptyState(stringResource(R.string.scales_empty), stringResource(R.string.scales_add), onAddScale)
            } else {
                LazyColumn {
                    items(list, key = { it.binding.id }) { item ->
                        ListItem(
                            headlineContent = { Text(item.title) },
                            supportingContent = { Text(stringResource(R.string.scale_mac, item.binding.mac)) },
                            leadingContent = { Icon(painterResource(R.drawable.ic_scale), null) },
                            modifier = Modifier.clickable { onOpenScale(item.binding.id) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
