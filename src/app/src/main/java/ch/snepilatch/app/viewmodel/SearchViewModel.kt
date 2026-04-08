package ch.snepilatch.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.snepilatch.app.data.TrackInfo
import ch.snepilatch.app.data.toTrackInfo
import ch.snepilatch.app.playback.SessionHolder
import ch.snepilatch.app.util.LokiLogger
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
 * ViewModel for the search screen. Holds the current query, results, and an
 * "is searching" flag. Debounces the query by 400ms before hitting the API
 * so fast typists don't fire a request per keystroke.
 *
 * Reads its [Session] from [SessionHolder] — there is no need to inject one,
 * the holder is process-scoped and exists for the entire app lifetime after
 * login.
 *
 * First feature-scoped ViewModel extracted from the monolithic
 * [SpotifyViewModel]. The pattern: own the feature's state, depend on
 * [SessionHolder] for the session, and forward unrelated actions (like
 * playing a track from a result row) to the main ViewModel via the screen.
 */
class SearchViewModel : ViewModel() {

    private val tag = "SearchVM"

    val query = MutableStateFlow("")
    private val _results = MutableStateFlow<List<TrackInfo>>(emptyList())
    val results: StateFlow<List<TrackInfo>> = _results
    val isSearching = MutableStateFlow(false)

    private var searchJob: Job? = null

    fun updateQuery(text: String) {
        query.value = text
        if (text.length < MIN_QUERY_LENGTH) {
            _results.value = emptyList()
            searchJob?.cancel()
            return
        }
        searchJob?.cancel()
        searchJob = launchWithSession("search") { sess ->
            delay(DEBOUNCE_MS)
            isSearching.value = true
            try {
                val response = Song(sess).search(text, limit = SEARCH_LIMIT)
                _results.value = response.tracks.items.map { it.toTrackInfo() }
                LokiLogger.i(tag, "Search '$text': ${_results.value.size} results")
            } finally {
                isSearching.value = false
            }
        }
    }

    /**
     * Same shape as [SpotifyViewModel.launchWithSession] but local to this
     * ViewModel. When more feature ViewModels exist this will move into a
     * shared base class or extension function.
     */
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
        private const val MIN_QUERY_LENGTH = 2
        private const val DEBOUNCE_MS = 400L
        private const val SEARCH_LIMIT = 30
    }
}
