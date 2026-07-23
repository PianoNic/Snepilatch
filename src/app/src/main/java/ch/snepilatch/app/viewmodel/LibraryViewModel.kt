package ch.snepilatch.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.snepilatch.app.data.LibraryItem
import ch.snepilatch.app.data.toUiLibraryList
import ch.snepilatch.app.playback.SessionHolder
import ch.snepilatch.app.util.LokiLogger
import kotify.api.album.Album
import kotify.api.artist.Artist
import kotify.api.playlist.Playlist
import kotify.session.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for the "Your Library" screen: the saved list + pagination, plus create/remove.
 *
 * Reads the session (and, for mutations, the username) from [SessionHolder], and loads the first
 * page in [init] — the old eager load lived in `SpotifyViewModel.initialize`, which runs before any
 * composable exists; loading here instead fires as soon as the post-login shell composes this VM,
 * which is well before the library-backed playlist picker can be opened.
 *
 * `followArtist`/`savePlaylist` and the add-to-playlist picker stay on [SpotifyViewModel] — they emit
 * snackbars and are triggered from non-composable search builders, i.e. "add external content to the
 * library" rather than browsing it.
 */
class LibraryViewModel : ViewModel() {

    private val tag = "LibraryVM"

    private val _library = MutableStateFlow<List<LibraryItem>>(emptyList())
    val library: StateFlow<List<LibraryItem>> = _library
    private val _libraryTotal = MutableStateFlow(-1)
    val libraryTotal: StateFlow<Int> = _libraryTotal
    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore

    init { loadLibrary() }

    fun loadLibrary() {
        launchSession("loadLibrary") { sess ->
            val page = Playlist(sess).getLibrary(limit = 50, offset = 0)
            _library.value = page.toUiLibraryList()
            _libraryTotal.value = page.total
        }
    }

    fun loadMoreLibrary() {
        if (_isLoadingMore.value) return
        val loaded = _library.value.size
        val total = _libraryTotal.value
        if (total in 0..loaded) return
        _isLoadingMore.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val sess = SessionHolder.session ?: return@launch
                val page = Playlist(sess).getLibrary(limit = 50, offset = loaded)
                val more = page.toUiLibraryList()
                _library.value = _library.value + more
                _libraryTotal.value = page.total
            } catch (e: Exception) {
                LokiLogger.e(tag, "loadMoreLibrary", e)
            } finally {
                _isLoadingMore.value = false
            }
        }
    }

    fun removeFromLibrary(item: LibraryItem) {
        launchSession("removeFromLibrary") { sess ->
            val id = item.uri.substringAfterLast(":")
            when (item.type) {
                "album" -> Album(sess).removeFromLibrary(id)
                "artist" -> Artist(sess).unfollow(id)
                "playlist" -> Playlist(sess).deletePlaylist(id, SessionHolder.username)
            }
            loadLibrary()
        }
    }

    fun createPlaylist(name: String) {
        launchSession("createPlaylist") { sess ->
            Playlist(sess).createPlaylist(name, SessionHolder.username)
            delay(1000)
            loadLibrary()
        }
    }

    private fun launchSession(tag: String, block: suspend (Session) -> Unit): Job =
        viewModelScope.launch(Dispatchers.IO) {
            val sess = SessionHolder.session ?: return@launch
            try {
                block(sess)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LokiLogger.e(this@LibraryViewModel.tag, tag, e)
            }
        }
}
