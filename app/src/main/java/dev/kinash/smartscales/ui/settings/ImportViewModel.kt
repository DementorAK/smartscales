package dev.kinash.smartscales.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.kinash.smartscales.R
import dev.kinash.smartscales.core.data.Profile
import dev.kinash.smartscales.core.data.repository.MeasurementRepository
import dev.kinash.smartscales.core.data.repository.ProfileRepository
import dev.kinash.smartscales.core.data.repository.SettingsRepository
import dev.kinash.smartscales.importing.ImportLayout
import dev.kinash.smartscales.importing.ImportPlan
import dev.kinash.smartscales.importing.Table
import dev.kinash.smartscales.importing.Tables
import dev.kinash.smartscales.ui.common.displayName
import dev.kinash.smartscales.ui.common.readAtMost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.util.Locale
import javax.inject.Inject

/** A file read and how to read it: what the person sees and changes before importing. */
data class ImportDraft(val name: String, val table: Table, val layout: ImportLayout, val profileId: Long?)

/** Weighings from another app's table or one's own (requirements §4.3). */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ImportViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val profileRepository: ProfileRepository,
    settings: SettingsRepository,
    private val measurements: MeasurementRepository,
) : ViewModel() {
    val profiles: StateFlow<List<Profile>> =
        profileRepository.observeProfiles().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val selected: StateFlow<Long?> = settings.selectedProfileId.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _draft = MutableStateFlow<ImportDraft?>(null)
    val draft: StateFlow<ImportDraft?> = _draft.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)

    /** What the last action came to, shown once. */
    val message: StateFlow<String?> = _message.asStateFlow()

    fun messageShown() {
        _message.value = null
    }

    /** The plan of the draft against the chosen profile's weighings, worked out again as the person changes it. */
    val plan: StateFlow<ImportPlan?> = combine(_draft.filterNotNull(), this.profiles) { draft, all -> draft to all.firstOrNull { it.id == draft.profileId } }
        .mapLatest { (draft, profile) ->
            profile ?: return@mapLatest null
            val existing = measurements.observeHistory(profile.id).first()
            ImportPlan.of(draft.layout.rows(draft.table), profile, existing, ZoneId.systemDefault())
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun open(uri: Uri) = work {
        val read = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = context.contentResolver.openInputStream(uri)?.use { readAtMost(it, Tables.MAX_BYTES + 1) }
                    ?.takeIf { it.size <= Tables.MAX_BYTES } ?: return@runCatching null
                Tables.read(bytes)
            }.getOrNull()
        }
        if (read == null) {
            _message.value = context.getString(R.string.import_unreadable)
            return@work
        }
        val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
        val layout = withContext(Dispatchers.Default) { ImportLayout.detect(read, locale) }
        if (layout == null) {
            _message.value = context.getString(R.string.import_no_weighings)
            return@work
        }
        // Read now rather than waited for: with no profile yet the draft has none to go to, and says so
        val all = profileRepository.observeProfiles().first()
        val profile = all.firstOrNull { it.id == selected.value } ?: all.firstOrNull()
        _draft.value = ImportDraft(displayName(context, uri), read, layout, profile?.id)
    }

    fun change(transform: (ImportDraft) -> ImportDraft) {
        _draft.value = _draft.value?.let(transform)
    }

    fun cancel() {
        _draft.value = null
    }

    /** Adds the weighings of the draft as it is now, all of them or none. */
    fun import() {
        val draft = _draft.value ?: return
        work {
            val profile = profiles.value.firstOrNull { it.id == draft.profileId } ?: return@work
            // Worked out again rather than taken from [plan], which may be a change behind
            val plan = withContext(Dispatchers.Default) {
                ImportPlan.of(draft.layout.rows(draft.table), profile, measurements.observeHistory(profile.id).first(), ZoneId.systemDefault())
            }
            if (plan.add.isEmpty()) return@work
            val result = runCatching { withContext(NonCancellable) { measurements.addAll(plan.add) } }
            _message.value = if (result.isSuccess) {
                context.resources.getQuantityString(R.plurals.import_added, plan.add.size, plan.add.size)
            } else {
                context.getString(R.string.import_failed)
            }
            if (result.isSuccess) _draft.value = null
        }
    }

    private fun work(block: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                block()
            } finally {
                _busy.value = false
            }
        }
    }
}
