package dev.kinash.smartscales.ui.formulas.editor

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.kinash.smartscales.core.data.FormulaSet
import dev.kinash.smartscales.core.data.Profile
import dev.kinash.smartscales.core.data.repository.FormulaSetRepository
import dev.kinash.smartscales.core.data.repository.ProfileRepository
import dev.kinash.smartscales.core.data.repository.SettingsRepository
import dev.kinash.smartscales.core.formula.FormulaIssue
import dev.kinash.smartscales.core.formula.FormulaSetException
import dev.kinash.smartscales.core.formula.FormulaSetFile
import dev.kinash.smartscales.core.formula.text
import dev.kinash.smartscales.indicators.EditCheck
import dev.kinash.smartscales.indicators.Indicators
import dev.kinash.smartscales.indicators.checkEdit
import dev.kinash.smartscales.ui.FormulaSetEditRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/** What the editor shows: the set, or one of its parts by its place in the set. */
@Serializable
sealed interface EditorPage {
    @Serializable
    data object Set : EditorPage

    @Serializable
    data class Indicator(val index: Int) : EditorPage

    @Serializable
    data class Patch(val index: Int) : EditorPage

    @Serializable
    data class Input(val index: Int) : EditorPage

    @Serializable
    data class Group(val index: Int) : EditorPage
}

/**
 * What the editor keeps so that a long edit outlives the app being unloaded in the background. It goes to a file, not
 * to the saved state: a big imported set would not fit in the saved state's Binder transaction.
 */
@Serializable
private class EditorSnapshot(val draft: SetDraft, val pages: List<EditorPage>)

/** The draft [draft] checked: the fields that do not read, and what the model says of the [file] they make. */
class EditorCheck(val draft: SetDraft, val file: FormulaSetFile, val fieldErrors: List<FormulaIssue>, val result: EditCheck) {
    val errors: List<FormulaIssue> = fieldErrors + result.errors
    val warnings: List<FormulaIssue> get() = result.warnings
}

/** The selected profile's last weighing, which a formula is tried on as it is typed. */
class TrialWeighing(val profile: Profile, val takenAt: Instant, val zone: ZoneId, val inputs: Map<String, Any?>, val primary: Map<String, String>)

data class EditorState(val draft: SetDraft, val isNew: Boolean, val changed: Boolean, val pages: List<EditorPage>) {
    val page: EditorPage get() = pages.last()
}

/**
 * The editor of one of the person's formula sets, or of a new one (requirements §5.1): the draft is checked as the
 * person types, as importing it would be, and saved only without errors, as an ordinary set in its place.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class FormulaSetEditViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val sets: FormulaSetRepository,
    private val indicators: Indicators,
    private val settings: SettingsRepository,
    private val profiles: ProfileRepository,
    @param:ApplicationContext context: Context,
) : ViewModel() {
    private val route = savedStateHandle.toRoute<FormulaSetEditRoute>()

    private val snapshots = File(context.cacheDir, SNAPSHOT_DIR)

    /** The file of this editor's snapshot; only its name is in the saved state. */
    private val snapshot = File(snapshots, (savedStateHandle.get<String>(SNAPSHOT) ?: UUID.randomUUID().toString().also { savedStateHandle[SNAPSHOT] = it }) + ".json")

    /** The phone's language, the one texts are written in beside English. */
    val language: String = context.resources.configuration.locales[0].language

    private val draft = MutableStateFlow<SetDraft?>(null)
    private var original: SetDraft? = null
    private var editing: FormulaSet? = null
    private val pages = MutableStateFlow<List<EditorPage>>(listOf(EditorPage.Set))

    private val _state = MutableStateFlow<EditorState?>(null)

    /** Null until the set is read. */
    val state: StateFlow<EditorState?> = _state.asStateFlow()

    private val _saved = MutableStateFlow(false)

    /** True once the set is saved, and the editor can close. */
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    /**
     * Whether saving was tried: until then fields left blank are not shown as errors, as the person may be about
     * to fill them in. Kept in the saved state with the snapshot's name.
     */
    val tried: StateFlow<Boolean> = savedStateHandle.getStateFlow(TRIED, false)

    private val _rejected = MutableStateFlow(0)

    /** Counts the saves refused for errors, for the screen to show them each time. */
    val rejected: StateFlow<Int> = _rejected.asStateFlow()

    init {
        viewModelScope.launch {
            val stored = sets.observeAll().first()
            editing = route.setId?.let { id -> stored.firstOrNull { it.id == id } }
            val file = editing?.let { set ->
                try {
                    FormulaSetFile.parse(set.json)
                } catch (e: FormulaSetException) {
                    null
                }
            }
            val restored = withContext(Dispatchers.IO) { readSnapshot() }
            // Rebuilt from the stored set rather than kept: had the set been replaced meanwhile (a file with its id
            // imported), the restored draft counts as changed against the new version, and saving it replaces that
            val start = file?.let { SetDraft.of(it, language) } ?: SetDraft(id = freeId(stored))
            original = start
            pages.value = restored?.pages ?: listOf(EditorPage.Set)
            draft.value = restored?.draft ?: start
            launch { keepSnapshot() }
            combine(draft.filterNotNull(), pages) { d, p -> EditorState(d, editing == null, d != original, p) }
                .collect { _state.value = it }
        }
    }

    /** The draft's check, a moment after the typing stops; null until the first. */
    val check: StateFlow<EditorCheck?> = combine(draft.filterNotNull(), sets.observeAll()) { d, stored -> d to stored }
        .debounce(CHECK_DELAY_MS)
        .mapLatest { (d, stored) ->
            val converted = d.toFile(language)
            EditorCheck(d, converted.file, converted.errors, checkEdit(converted.file, editing, stored))
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val trial: StateFlow<TrialWeighing?> = flow {
        val profile = settings.selectedProfileId.first()?.let { profiles.get(it) } ?: profiles.observeProfiles().first().firstOrNull()
        val last = profile?.let { indicators.observeHistory(it.id).first().firstOrNull { item -> item.inputs != null } }
        emit(last?.let { TrialWeighing(profile, it.measurement.takenAt, it.measurement.zone, it.inputs!!, it.primary) })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun update(change: (SetDraft) -> SetDraft) = draft.update { it?.let(change) }

    fun updateIndicator(index: Int, change: (IndicatorDraft) -> IndicatorDraft) =
        update { it.copy(indicators = it.indicators.changed(index, change)) }

    fun updatePatch(index: Int, change: (PatchDraft) -> PatchDraft) = update { it.copy(patches = it.patches.changed(index, change)) }

    fun updateInput(index: Int, change: (InputDraft) -> InputDraft) = update { it.copy(inputs = it.inputs.changed(index, change)) }

    fun updateGroup(index: Int, change: (GroupDraft) -> GroupDraft) = update { it.copy(groups = it.groups.changed(index, change)) }

    fun open(page: EditorPage) = pages.update { it + page }

    /** Goes back to the set from one of its parts; false on the set itself. */
    fun back(): Boolean {
        if (pages.value.size == 1) return false
        pages.update { it.dropLast(1) }
        return true
    }

    fun addIndicator() {
        val current = draft.value ?: return
        val below = check.value?.result?.below
        // Indicators, groups and inputs share one set of names
        val taken = (current.indicators.map { it.id } + current.inputs.map { it.id } + current.groups.map { it.id }).toSet() +
            below?.indicators?.keys.orEmpty() + below?.groups?.keys.orEmpty() + below?.inputs?.keys.orEmpty()
        update { it.copy(indicators = it.indicators + IndicatorDraft(id = freeName("my_indicator", taken))) }
        open(EditorPage.Indicator(current.indicators.size))
    }

    /** Opens the change of indicator [id] from below, adding it when the set has none yet. */
    fun changeIndicator(id: String) {
        val current = draft.value ?: return
        val index = current.patches.indexOfFirst { it.id == id }
        if (index >= 0) return open(EditorPage.Patch(index))
        update { it.copy(patches = it.patches + PatchDraft(id)) }
        open(EditorPage.Patch(current.patches.size))
    }

    fun addInput() {
        val current = draft.value ?: return
        update { it.copy(inputs = it.inputs + InputDraft(id = freeName("my_field", current.inputs.map { input -> input.id }.toSet()))) }
        open(EditorPage.Input(current.inputs.size))
    }

    fun addGroup() {
        val current = draft.value ?: return
        update { it.copy(groups = it.groups + GroupDraft()) }
        open(EditorPage.Group(current.groups.size))
    }

    /** Removes the part [page] shows from the set and goes back to it. */
    fun remove(page: EditorPage) {
        pages.value = listOf(EditorPage.Set)
        update {
            when (page) {
                is EditorPage.Indicator -> it.copy(indicators = it.indicators.without(page.index))
                is EditorPage.Patch -> it.copy(patches = it.patches.without(page.index))
                is EditorPage.Input -> it.copy(inputs = it.inputs.without(page.index))
                is EditorPage.Group -> it.copy(groups = it.groups.without(page.index))
                EditorPage.Set -> it
            }
        }
    }

    fun hide(id: String) = update { if (id in it.hidden) it else it.copy(hidden = it.hidden + id) }

    fun unhide(id: String) = update { it.copy(hidden = it.hidden - id) }

    /**
     * Saves the draft when its latest check has no errors: in the edited set's place, or on top for a new one. With
     * errors, they are shown instead, those of blank fields too.
     */
    fun save() {
        val checked = check.value ?: return
        if (checked.draft != draft.value) return
        if (checked.errors.isNotEmpty()) {
            savedStateHandle[TRIED] = true
            _rejected.update { it + 1 }
            return
        }
        viewModelScope.launch {
            withContext(NonCancellable) {
                val json = FormulaSetFile.encode(checked.file)
                val name = checked.file.name.text("en")
                val set = editing
                if (set == null) sets.add(name, json, FormulaSetFile.FORMAT) else sets.replace(set.id, name, json, FormulaSetFile.FORMAT)
            }
            _saved.value = true
        }
    }

    /** Set when the editor is closed for good, for a write that was already under way then. */
    @Volatile
    private var closed = false

    override fun onCleared() {
        // The editor is closed for good: the snapshot is needed no more. Unloaded in the background, it is not cleared
        closed = true
        snapshot.delete()
    }

    private fun readSnapshot(): EditorSnapshot? {
        // Left behind by editors that were never come back to
        snapshots.listFiles()?.filter { it != snapshot && it.lastModified() < System.currentTimeMillis() - SNAPSHOT_LIFE_MS }?.forEach { it.delete() }
        return try {
            if (snapshot.exists()) snapshotJson.decodeFromString(EditorSnapshot.serializer(), snapshot.readText()) else null
        } catch (e: IOException) {
            null
        } catch (e: IllegalArgumentException) {
            // Also a snapshot of another version of the app: the edit starts over from the stored set
            null
        }
    }

    /** Writes the snapshot a moment after each change, off the main thread, so that typing in a big set stays quick. */
    private suspend fun keepSnapshot() {
        combine(draft.filterNotNull(), pages) { d, p -> EditorSnapshot(d, p) }
            .drop(1)
            .debounce(SNAPSHOT_DELAY_MS)
            .collectLatest { state ->
                withContext(Dispatchers.IO) {
                    try {
                        snapshots.mkdirs()
                        val next = File(snapshots, snapshot.name + ".tmp")
                        next.writeText(snapshotJson.encodeToString(EditorSnapshot.serializer(), state))
                        if (!next.renameTo(snapshot)) next.delete()
                        // Cancelling does not stop a write: one that ends after the editor closed removes its file
                        if (closed) snapshot.delete()
                    } catch (e: IOException) {
                        // Without the snapshot only an edit unloaded in the background is lost
                    }
                }
            }
    }

    private fun freeId(stored: List<FormulaSet>): String {
        val taken = stored.mapNotNull { set ->
            try {
                FormulaSetFile.parse(set.json).id
            } catch (e: FormulaSetException) {
                null
            }
        }.toSet()
        return freeName("my_formulas", taken)
    }

    private companion object {
        const val CHECK_DELAY_MS = 250L
        const val SNAPSHOT = "editor"
        const val TRIED = "editor_tried"
        const val SNAPSHOT_DIR = "editor"
        const val SNAPSHOT_DELAY_MS = 500L
        val SNAPSHOT_LIFE_MS = TimeUnit.DAYS.toMillis(7)
        val snapshotJson = Json { ignoreUnknownKeys = true }

        fun freeName(base: String, taken: Set<String>): String =
            generateSequence(1) { it + 1 }.map { if (it == 1) base else "${base}_$it" }.first { it !in taken }

        fun <T> List<T>.changed(index: Int, change: (T) -> T): List<T> = mapIndexed { i, item -> if (i == index) change(item) else item }

        fun <T> List<T>.without(index: Int): List<T> = filterIndexed { i, _ -> i != index }
    }
}
