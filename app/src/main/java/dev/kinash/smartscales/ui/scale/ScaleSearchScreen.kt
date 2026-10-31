package dev.kinash.smartscales.ui.scale

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kinash.smartscales.R
import dev.kinash.smartscales.core.ble.AdvertisementScanner
import dev.kinash.smartscales.core.ble.DeviceSupport
import dev.kinash.smartscales.core.ble.KeyProvider
import dev.kinash.smartscales.core.ble.ScaleDrivers
import dev.kinash.smartscales.core.data.repository.ScaleBindingRepository
import dev.kinash.smartscales.core.protocol.ScaleWeight
import dev.kinash.smartscales.ui.common.BackButton
import dev.kinash.smartscales.ui.common.ScreenPadding
import dev.kinash.smartscales.ui.common.scaleWeightText
import dev.kinash.smartscales.ui.history.EmptyState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A Chipsea scale seen nearby, with its latest weight when the advertisement carries one. */
data class FoundScale(
    val support: DeviceSupport,
    val address: String,
    val rssi: Int,
    val weight: ScaleWeight?,
    val added: Boolean = false,
)

data class ScaleSearchState(
    val found: List<FoundScale> = emptyList(),
    val scanError: Int? = null,
    val done: Boolean = false,
)

@HiltViewModel
class ScaleSearchViewModel @Inject constructor(
    val scanner: AdvertisementScanner,
    private val drivers: ScaleDrivers,
    private val bindings: ScaleBindingRepository,
) : ViewModel() {
    private val found = MutableStateFlow<Map<String, FoundScale>>(emptyMap())
    private val status = MutableStateFlow(ScaleSearchState())

    val state: StateFlow<ScaleSearchState> = combine(found, bindings.observeBound(), status) { found, bound, status ->
        val boundMacs = bound.map { it.mac }.toSet()
        status.copy(
            found = found.values
                .map { it.copy(added = it.support.scaleMac in boundMacs) }
                .sortedByDescending { it.rssi },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScaleSearchState())

    /** Scans until cancelled; encrypted weights are not shown here, since no key is known yet. */
    suspend fun scan() {
        status.update { it.copy(scanError = null) }
        val error = scanner.scanUntilError { advertisement ->
            val (driver, support) = drivers.recognise(advertisement) ?: return@scanUntilError
            val reading = driver.onAdvertisement(advertisement, KeyProvider { null })
            found.update { scales ->
                // Keep the last weight while frames without one (measuring, encrypted) come in
                val weight = reading?.weight ?: scales[support.scaleMac]?.weight
                scales + (support.scaleMac to FoundScale(support, advertisement.address, advertisement.rssi, weight))
            }
        }
        if (error != null) status.update { it.copy(scanError = error) }
    }

    fun add(scale: FoundScale) {
        if (scale.added) return
        viewModelScope.launch {
            val support = scale.support
            bindings.bind(support.scaleMac, scale.address, support.protocol, support.protocolVersion, support.productId)
            status.update { it.copy(done = true) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScaleSearchScreen(onDone: () -> Unit, viewModel: ScaleSearchViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.done) { if (state.done) onDone() }
    var retry by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.scale_search_title)) }, navigationIcon = { BackButton(onDone) })
        },
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            BluetoothGate(viewModel.scanner) {
                ScanWhileVisible(retry) { viewModel.scan() }
                val error = state.scanError
                if (error != null) {
                    EmptyState(stringResource(R.string.bt_scan_failed, error), stringResource(R.string.bt_retry)) { retry++ }
                    return@BluetoothGate
                }
                LazyColumn {
                    item {
                        LinearProgressIndicator(
                            Modifier
                                .padding(horizontal = ScreenPadding)
                                .fillMaxWidth(),
                        )
                        Text(
                            stringResource(R.string.scale_search_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(ScreenPadding),
                        )
                    }
                    items(state.found, key = { it.support.scaleMac }) { scale ->
                        FoundScaleRow(scale, onClick = { viewModel.add(scale) })
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun FoundScaleRow(scale: FoundScale, onClick: () -> Unit) {
    val details = buildList {
        add(scale.support.scaleMac)
        scale.weight?.let { add(scaleWeightText(it)) }
        if (!scale.support.description.canWeigh) add(stringResource(R.string.scale_search_preview_only))
        if (scale.added) add(stringResource(R.string.scale_search_added))
    }
    ListItem(
        headlineContent = { Text(scale.support.description.displayName) },
        supportingContent = { Text(details.joinToString(" · ")) },
        leadingContent = { Icon(painterResource(R.drawable.ic_scale), null) },
        modifier = if (scale.added) Modifier else Modifier.clickable(onClick = onClick),
    )
}
