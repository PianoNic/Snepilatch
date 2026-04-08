package ch.snepilatch.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.snepilatch.app.playback.SessionHolder
import ch.snepilatch.app.util.LokiLogger
import kotify.api.song.SearchResult
import kotify.api.song.SearchSuggestion
import kotify.api.song.Song
import kotify.session.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for the Spotify-style search screen.
 *
 * State machine:
 *  - **Idle** (`query.isEmpty()`): show the browse-categories grid
 *  - **Suggesting** (`query.isNotEmpty()` and `submittedQuery.isEmpty()`):
 *    show the autocomplete dropdown driven by [suggestions]. New keystrokes
 *    debounce SUGGEST_DEBOUNCE_MS before hitting Spotify so a fast typist
 *    doesn't fire one request per character.
 *  - **Submitted** (`submittedQuery.isNotEmpty()`): show the categorized
 *    results from [results]. The user got here by tapping a suggestion or
 *    pressing the keyboard's search action.
 */
class SearchViewModel : ViewModel() {

    private val tag = "SearchVM"

    val query = MutableStateFlow("")

    /** The query the user has actually committed to (via tap/IME action). */
    val submittedQuery = MutableStateFlow("")

    val suggestions = MutableStateFlow<List<SearchSuggestion>>(emptyList())
    val isSuggesting = MutableStateFlow(false)

    private val _results = MutableStateFlow<SearchResult?>(null)
    val results: StateFlow<SearchResult?> = _results
    val isSearching = MutableStateFlow(false)

    val selectedFilter = MutableStateFlow(SearchFilter.ALL)

    enum class SearchFilter { ALL, ARTISTS, ALBUMS, SONGS, PLAYLISTS, PODCASTS, PROFILES }

    private var suggestJob: Job? = null
    private var searchJob: Job? = null

    /**
     * Called from the search TextField on every keystroke. Updates the
     * displayed query and triggers a debounced suggestions fetch. Clearing
     * the field also clears the submitted state so the screen returns to
     * the browse view.
     */
    fun updateQuery(text: String) {
        query.value = text
        if (text.isEmpty()) {
            suggestJob?.cancel()
            suggestions.value = emptyList()
            submittedQuery.value = ""
            _results.value = null
            return
        }
        // Length-1 queries are too noisy and Spotify's suggestions for
        // single letters are mostly garbage; wait for at least 2 chars.
        if (text.length < MIN_SUGGEST_LENGTH) return
        scheduleSuggest(text)
    }

    /**
     * Called when the user picks a suggestion or hits the keyboard's
     * "search" action. Fires the full categorized search.
     */
    fun submitQuery(text: String) {
        if (text.isBlank()) return
        query.value = text
        submittedQuery.value = text
        selectedFilter.value = SearchFilter.ALL
        suggestJob?.cancel()
        suggestions.value = emptyList()
        scheduleFullSearch(text)
    }

    fun setFilter(filter: SearchFilter) {
        selectedFilter.value = filter
    }

    /**
     * Step backward from the submitted state to the suggestions state — used
     * when the user starts typing again after viewing results.
     */
    fun clearSubmitted() {
        submittedQuery.value = ""
        _results.value = null
    }

    private fun scheduleSuggest(text: String) {
        suggestJob?.cancel()
        suggestJob = launchWithSession("suggest") { sess ->
            delay(SUGGEST_DEBOUNCE_MS)
            isSuggesting.value = true
            try {
                val response = Song(sess).searchSuggestions(text, limit = SUGGEST_LIMIT)
                // Drop the response if the query has moved on while we were waiting
                if (query.value == text && submittedQuery.value.isEmpty()) {
                    suggestions.value = response.items
                }
            } finally {
                isSuggesting.value = false
            }
        }
    }

    private fun scheduleFullSearch(text: String) {
        searchJob?.cancel()
        searchJob = launchWithSession("search") { sess ->
            delay(SEARCH_DEBOUNCE_MS)
            isSearching.value = true
            try {
                val response = Song(sess).search(text, limit = SEARCH_LIMIT)
                if (submittedQuery.value == text) {
                    _results.value = response
                    LokiLogger.i(
                        tag,
                        "Search '$text': tracks=${response.tracks.items.size}, " +
                            "artists=${response.artists.items.size}, " +
                            "albums=${response.albums.items.size}, " +
                            "playlists=${response.playlists.items.size}, " +
                            "podcasts=${response.podcasts.items.size}, " +
                            "users=${response.users.items.size}"
                    )
                }
            } finally {
                isSearching.value = false
            }
        }
    }

    private fun launchWithSession(label: String, block: suspend (Session) -> Unit): Job =
        viewModelScope.launch(Dispatchers.IO) {
            val sess = SessionHolder.session ?: return@launch
            try {
                block(sess)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LokiLogger.e(tag, label, e)
            }
        }

    companion object {
        private const val MIN_SUGGEST_LENGTH = 2
        private const val SUGGEST_DEBOUNCE_MS = 150L
        private const val SEARCH_DEBOUNCE_MS = 250L
        private const val SUGGEST_LIMIT = 10
        private const val SEARCH_LIMIT = 10
    }
}
