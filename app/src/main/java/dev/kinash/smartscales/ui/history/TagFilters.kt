package dev.kinash.smartscales.ui.history

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The tags each profile's history and charts are filtered by, normalized: one filter for both screens, kept in
 * memory only. A weighing from the scale comes without tags, and a filter kept from yesterday would hide it from
 * the list while the cards show its values; after a restart the history is whole again.
 */
@Singleton
class TagFilters @Inject constructor() {
    private val filters = MutableStateFlow<Map<Long, Set<String>>>(emptyMap())

    fun of(profileId: Long): Flow<Set<String>> = filters.map { it[profileId].orEmpty() }.distinctUntilChanged()

    /** Turns [tag] on or off in one step, so two quick taps on two chips both count. */
    fun toggle(profileId: Long, tag: String) = filters.update { all ->
        val tags = all[profileId].orEmpty()
        all + (profileId to if (tag in tags) tags - tag else tags + tag)
    }
}
