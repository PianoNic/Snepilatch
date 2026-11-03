package ch.snepilatch.app.viewmodel

import androidx.annotation.StringRes
import androidx.lifecycle.viewModelScope
import ch.snepilatch.app.R
import ch.snepilatch.app.logic.shared.AppMessages
import ch.snepilatch.app.logic.shared.SessionViewModel
import kotify.api.playlist.CurationContext
import kotify.api.playlist.Playlist
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The web player's "Saved in" sheet for one track (#710): what can hold it, what does, and the ticks
 * the user changed, written in one go on Done.
 */
class SavedInViewModel : SessionViewModel("SavedIn") {

    data class State(
        val rows: List<CurationContext> = emptyList(),
        val total: Int = 0,
        val folder: CurationContext? = null,
        val filter: String = "",
        val loading: Boolean = false,
        /** The ticks that differ from what the server said, by uri. */
        val changes: Map<String, Boolean> = emptyMap(),
    ) {
        fun ticked(row: CurationContext) = changes[row.uri] ?: row.isCurated

        /** Ticking back to what the server said is no change at all, so nothing is written for it. */
        fun toggled(row: CurationContext): State {
            val ticked = !ticked(row)
            return copy(changes = if (ticked == row.isCurated) changes - row.uri else changes + (row.uri to ticked))
        }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var trackUri = ""
    private var loadJob: Job? = null

    /** Every row seen, so a change made before searching or in a folder still saves and can be named. */
    private val seen = mutableMapOf<String, CurationContext>()

    fun open(uri: String) {
        trackUri = uri
        seen.clear()
        _state.value = State()
        reload()
    }

    /** Asks once typing pauses, 200 ms as the web player's filter box, so a word is not one request per letter. */
    fun search(filter: String) {
        _state.value = _state.value.copy(filter = filter, rows = emptyList(), total = 0)
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            delay(SEARCH_PAUSE_MS)
            load(offset = 0)
        }
    }

    fun openFolder(folder: CurationContext) {
        _state.value = _state.value.copy(folder = folder, filter = "")
        reload()
    }

    fun closeFolder() {
        _state.value = _state.value.copy(folder = null, filter = "")
        reload()
    }

    private fun reload() {
        _state.value = _state.value.copy(rows = emptyList(), total = 0)
        load(offset = 0)
    }

    fun loadMore() {
        val current = _state.value
        if (!current.loading && current.rows.size < current.total) load(current.rows.size)
    }

    private fun load(offset: Int) {
        loadJob?.cancel()
        val asked = _state.value
        _state.value = asked.copy(loading = true)
        loadJob = launchWithSession("load", onFailure = { _state.value = _state.value.copy(loading = false) }) { session ->
            val page = Playlist(session).getCurationContexts(
                itemUris = listOf(trackUri),
                offset = offset,
                textFilter = asked.filter,
                folderUri = asked.folder?.uri,
            )
            page.items.forEach { seen.putIfAbsent(it.uri, it) }
            _state.value = _state.value.copy(rows = _state.value.rows + page.items, total = page.total, loading = false)
        }
    }

    fun toggle(row: CurationContext) {
        _state.value = _state.value.toggled(row)
    }

    /** Writes the changes. [onLiked] hears whether the track is now in Liked Songs, when that changed. */
    fun save(onLiked: (Boolean) -> Unit) {
        val changes = _state.value.changes
        if (changes.isEmpty()) return
        val add = changes.filterValues { it }.keys.toList()
        val remove = changes.filterValues { !it }.keys.toList()
        val uri = trackUri
        launchWithSession("save", onFailure = { AppMessages.show(R.string.error_add_playlist) }) { session ->
            val refused = Playlist(session).applyCurations(uri, add, remove)
            if (LIKED_SONGS in changes && LIKED_SONGS !in refused) onLiked(changes.getValue(LIKED_SONGS))
            if (refused.isNotEmpty()) AppMessages.show(R.string.error_add_playlist) else announce(add, remove)
        }
    }

    /** The web player's feedback: the one place when there is one, otherwise that the changes are in. */
    private fun announce(add: List<String>, remove: List<String>) {
        val only = (add + remove).singleOrNull()
        when {
            only == null -> AppMessages.show(R.string.changes_saved)
            only == LIKED_SONGS -> AppMessages.show(likedMessage(only in add))
            only in add -> AppMessages.show(R.string.added_to_playlist, seen[only]?.name.orEmpty())
            else -> AppMessages.show(R.string.removed_from_named, seen[only]?.name.orEmpty())
        }
    }

    @StringRes
    private fun likedMessage(added: Boolean) = if (added) R.string.added_to_liked_songs else R.string.removed_from_liked_songs

    private companion object {
        const val LIKED_SONGS = "spotify:collection:tracks"
        const val SEARCH_PAUSE_MS = 200L
    }
}
