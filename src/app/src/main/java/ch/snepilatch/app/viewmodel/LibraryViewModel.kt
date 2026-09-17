package ch.snepilatch.app.viewmodel

import androidx.lifecycle.viewModelScope
import ch.snepilatch.app.data.LibraryItem
import ch.snepilatch.app.data.toUiLibraryList
import ch.snepilatch.app.logic.shared.Debouncer
import ch.snepilatch.app.logic.shared.LokiLogger
import ch.snepilatch.app.logic.shared.SessionHolder
import kotify.api.album.Album
import kotify.api.artist.Artist
import kotify.api.playerconnect.PlayerConnect
import kotify.api.playlist.Playlist
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import ch.snepilatch.app.logic.shared.SessionViewModel
import ch.snepilatch.app.logic.shared.spfyId

/**
 * ViewModel for the "Your Library" screen: the saved list + pagination, plus create/remove.
 *
 * Reads the session (and, for mutations, the username) from [SessionHolder], and loads the first
 * page in [init] — the old eager load lived in `PlaybackViewModel.initialize`, which runs before any
 * composable exists; loading here instead fires as soon as the post-login shell composes this VM,
 * which is well before the library-backed playlist picker can be opened.
 *
 * `followArtist`/`savePlaylist` and the add-to-playlist picker stay on [PlaybackViewModel] — they emit
 * snackbars and are triggered from non-composable search builders, i.e. "add external content to the
 * library" rather than browsing it.
 *
 * The library is a tree, not a flat list: a playlist filed into a folder is absent from the root
 * listing and only reachable through that folder. [folderPath] is where in the tree we are, and
 * every load is scoped to its last entry.
 *
 * It is fetched once and then kept fresh by the dealer, the way the web client does it: a change to
 * the rootlist — a playlist created, deleted, renamed, reordered, or moved between folders, from
 * any device — arrives as a push and invalidates what we hold. Nothing refetches on navigation, so
 * opening the tab is free. A dealer reconnect refetches too, since pushes sent while the socket was
 * down are simply gone.
 */
class LibraryViewModel : SessionViewModel("LibraryVM") {

    private val _library = MutableStateFlow<List<LibraryItem>>(emptyList())
    val library: StateFlow<List<LibraryItem>> = _library
    private val _libraryTotal = MutableStateFlow(-1)
    val libraryTotal: StateFlow<Int> = _libraryTotal
    private val _isLoading = MutableStateFlow(false)

    /** A listing is being fetched — the first page of the library, or of a folder just opened. */
    val isLoading: StateFlow<Boolean> = _isLoading
    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore
    private val _folderPath = MutableStateFlow<List<LibraryFolder>>(emptyList())

    /** The folders opened to get here, outermost first. Empty at the library root. */
    val folderPath: StateFlow<List<LibraryFolder>> = _folderPath

    private val folderUri: String? get() = _folderPath.value.lastOrNull()?.uri

    /**
     * The push arrives on the dealer's thread and can arrive in bursts — renaming a playlist moves
     * the rootlist more than once — so it hops onto the ViewModel scope and is collapsed there.
     */
    private val refresh = Debouncer(
        scope = viewModelScope,
        waitMs = REFRESH_WAIT_MS,
        maxWaitMs = REFRESH_MAX_WAIT_MS,
    ) { loadLibrary() }

    private var subscribedTo: PlayerConnect? = null

    init {
        loadLibrary()
        subscribeToPushes()
        // A switched account gets its own library instead of the old account's (#847).
        viewModelScope.launch {
            SessionHolder.generation.drop(1).collect {
                _folderPath.value = emptyList()
                _library.value = emptyList()
                loadLibrary()
                // A new account means a new PlayerConnect, and the old subscription died with it.
                subscribeToPushes()
            }
        }
    }

    private fun subscribeToPushes() {
        viewModelScope.launch {
            val player = SessionHolder.awaitPlayer(PLAYER_WAIT_MS) ?: return@launch
            if (player === subscribedTo) return@launch
            subscribedTo = player
            player.onRootlistChange { viewModelScope.launch { refresh.request() } }
            // Whatever moved while the socket was down was never delivered, so treat coming back as
            // a change in itself.
            player.onReconnected { viewModelScope.launch { refresh.request() } }
        }
    }

    /** Descend into [item] (a `folder` library entry); anything else is ignored. */
    fun openFolder(item: LibraryItem) {
        if (item.type != FOLDER_TYPE) return
        moveTo(_folderPath.value + LibraryFolder(item.uri, item.name))
    }

    /** Back out one level. False when already at the root, so the caller can handle back itself. */
    fun closeFolder(): Boolean {
        val path = _folderPath.value
        if (path.isEmpty()) return false
        moveTo(path.dropLast(1))
        return true
    }

    private fun moveTo(path: List<LibraryFolder>) {
        _folderPath.value = path
        _library.value = emptyList()
        _libraryTotal.value = -1
        loadLibrary()
    }

    fun loadLibrary() {
        val requested = folderUri
        launchWithSessionLoading("loadLibrary", _isLoading) { sess ->
            val page = Playlist(sess).getLibrary(limit = PAGE_SIZE, offset = 0, folderUri = requested)
            // A folder opened or closed while this was in flight owns the list now.
            if (requested != folderUri) return@launchWithSessionLoading
            _library.value = page.toUiLibraryList()
            _libraryTotal.value = page.total
            // The listing is fetched once and then only on a push, so when it reloads is the first
            // thing worth knowing when someone reports a stale library.
            LokiLogger.i(logTag, "Library loaded: ${page.total} items, folder=${requested ?: "root"}")
        }
    }

    fun loadMoreLibrary() {
        if (_isLoadingMore.value) return
        val loaded = _library.value.size
        val total = _libraryTotal.value
        if (total in 0..loaded) return
        val requested = folderUri
        launchWithSessionLoading("loadMoreLibrary", _isLoadingMore) { sess ->
            val page = Playlist(sess).getLibrary(limit = PAGE_SIZE, offset = loaded, folderUri = requested)
            if (requested != folderUri) return@launchWithSessionLoading
            _library.value = _library.value + page.toUiLibraryList()
            _libraryTotal.value = page.total
        }
    }

    fun removeFromLibrary(item: LibraryItem) {
        launchWithSession("removeFromLibrary") { sess ->
            val id = spfyId(item.uri)
            when (item.type) {
                "album" -> Album(sess).removeFromLibrary(id)
                "artist" -> Artist(sess).unfollow(id)
                "playlist" -> Playlist(sess).deletePlaylist(id, SessionHolder.username)
            }
            loadLibrary()
        }
    }

    fun createPlaylist(name: String) {
        launchWithSession("createPlaylist") { sess ->
            Playlist(sess).createPlaylist(name, SessionHolder.username)
            delay(1000)
            loadLibrary()
        }
    }

    companion object {
        /** The `type` a [LibraryItem] carries when it is a folder rather than something playable. */
        const val FOLDER_TYPE = "folder"
        private const val PAGE_SIZE = 50

        // The web client's own debounce on this exact signal.
        private const val REFRESH_WAIT_MS = 200L
        private const val REFRESH_MAX_WAIT_MS = 1000L

        /** The player registers a few seconds after launch; the listing itself does not wait on it. */
        private const val PLAYER_WAIT_MS = 30_000L
    }
}

/** One level of [LibraryViewModel.folderPath]. */
data class LibraryFolder(val uri: String, val name: String)
