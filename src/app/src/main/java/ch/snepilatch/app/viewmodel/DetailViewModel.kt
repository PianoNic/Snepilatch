package ch.snepilatch.app.viewmodel

import androidx.lifecycle.viewModelScope
import ch.snepilatch.app.data.*
import ch.snepilatch.app.playback.SessionHolder
import ch.snepilatch.app.util.LokiLogger
import kotify.api.album.Album
import kotify.api.artist.Artist
import kotify.api.playlist.Playlist
import kotify.api.podcast.Podcast
import kotify.api.song.Song
import kotify.session.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * ViewModel for the shared detail screen (playlist / album / artist / show).
 *
 * Owns the detail data + its pagination + the follow/save toggle, and the
 * openers that navigate to a detail and load it. Navigation goes through the
 * process-scoped [Navigator]; the session comes from [SessionHolder]. Screens
 * obtain this via `viewModel()` alongside [PlaybackViewModel].
 *
 * [PlaybackViewModel]'s deep-link handler and playback-context bridges
 * (openAlbumFromCurrentTrack / openArtistFromCurrentTrack / navigateToContext)
 * need PlayerConnect / playingContext, so they stay there and reach the openers
 * through [DetailRoutes] rather than holding a reference to this ViewModel.
 */
class DetailViewModel : SessionViewModel("DetailVM") {

    private val _detail = MutableStateFlow(DetailData())
    val detail: StateFlow<DetailData> = _detail

    val isLoading = MutableStateFlow(false)

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore

    val detailSaved = MutableStateFlow(false)

    init { DetailRoutes.register(this) }

    override fun onCleared() {
        DetailRoutes.unregister(this)
        super.onCleared()
    }

    /** Navigate to a detail screen and load it under [isLoading]; a null result leaves the previous
     *  detail unchanged (used by openShow when the podcast has no info). */
    private fun openDetail(screen: Screen, op: String, load: suspend (Session) -> DetailData?) {
        Navigator.navigateTo(screen)
        launchWithSessionLoading(op, isLoading) { sess -> load(sess)?.let { _detail.value = it } }
    }

    fun openLikedSongs() = openDetail(Screen.PLAYLIST_DETAIL, "openLikedSongs") { sess ->
        Playlist(sess).getLikedSongs(limit = 50).toDetailData(offset = 0)
    }

    fun openPlaylist(playlistId: String) = openDetail(Screen.PLAYLIST_DETAIL, "openPlaylist") { sess ->
        Playlist(sess).getPlaylist(playlistId, limit = 50).toDetailData(playlistId)
    }

    fun openAlbum(albumId: String) = openDetail(Screen.ALBUM_DETAIL, "openAlbum") { sess ->
        Album(sess).getAlbum(albumId, limit = 50).toDetailData(albumId)
    }

    fun openArtist(artistId: String) = openDetail(Screen.ARTIST_DETAIL, "openArtist") { sess ->
        Artist(sess).getArtist(artistId).toDetailData(artistId)
    }

    /**
     * Open a podcast show. [publisher]/[imageUrl] come from the search/library item that was tapped
     * (the `queryPodcastEpisodes` payload doesn't carry them); they fall back to the first episode's
     * cover art. Episodes render as episode-URI [ch.snepilatch.app.data.TrackInfo]s.
     */
    fun openShow(showId: String, publisher: String? = null, imageUrl: String? = null) =
        openDetail(Screen.SHOW_DETAIL, "openShow") { sess ->
            Podcast(sess, showId).getPodcastInfo(limit = 50, offset = 0)
                ?.toDetailData(showId, publisher, imageUrl)
                .also { if (it == null) LokiLogger.e(logTag, "openShow: no podcast info for $showId") }
        }

    fun openAlbumForTrack(trackUri: String) {
        launchWithSession("openAlbumForTrack") { sess ->
            val trackId = trackUri.removePrefix("spotify:track:")
            val track = Song(sess).getSong(trackId) ?: return@launchWithSession
            val albumUri = track.album.uri.takeIf { it.isNotBlank() } ?: return@launchWithSession
            openAlbum(albumUri.substringAfterLast(":"))
        }
    }

    fun openArtistForTrack(trackUri: String) {
        launchWithSession("openArtistForTrack") { sess ->
            val trackId = trackUri.removePrefix("spotify:track:")
            val track = Song(sess).getSong(trackId) ?: return@launchWithSession
            val artistUri = track.artists.firstOrNull()?.uri?.takeIf { it.isNotBlank() } ?: return@launchWithSession
            openArtist(artistUri.substringAfterLast(":"))
        }
    }

    fun loadMoreDetail() {
        val current = _detail.value
        if (_isLoadingMore.value) return
        if (current.totalCount in 0..current.tracks.size) return
        val uri = current.uri
        _isLoadingMore.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val sess = SessionHolder.session ?: return@launch
                val offset = current.tracks.size
                if (uri == "spotify:collection:tracks") {
                    val data = Playlist(sess).getLikedSongs(limit = 50, offset = offset)
                    val more = data.toDetailData(offset)
                    _detail.value = current.copy(
                        tracks = current.tracks + more.tracks,
                        totalCount = more.totalCount,
                        loadedOffset = offset + more.tracks.size
                    )
                } else if (uri.startsWith("spotify:playlist:")) {
                    val id = uri.removePrefix("spotify:playlist:")
                    val info = Playlist(sess).getPlaylist(id, limit = DETAIL_PAGE_SIZE, offset = offset)
                    val more = info.tracks.map { it.toTrackInfo() }
                    val newSize = current.tracks.size + more.size
                    // Server-reported totalTracks is unreliable (PlaylistMapper
                    // returns 0 when content.totalCount is missing). Use the
                    // page-shorter-than-limit signal as the authoritative
                    // "we're at the end" indicator instead.
                    val newTotalCount = when {
                        more.size < DETAIL_PAGE_SIZE -> newSize
                        info.totalTracks > 0 -> info.totalTracks
                        else -> -1
                    }
                    _detail.value = current.copy(
                        tracks = current.tracks + more,
                        totalCount = newTotalCount,
                        loadedOffset = newSize
                    )
                } else if (uri.startsWith("spotify:album:")) {
                    val id = uri.removePrefix("spotify:album:")
                    val info = Album(sess).getAlbum(id, limit = 50, offset = offset)
                    val more = info.tracks.map { it.toTrackInfo(info.coverArtUrl) }
                    _detail.value = current.copy(
                        tracks = current.tracks + more,
                        totalCount = info.totalTracks,
                        loadedOffset = offset + more.size
                    )
                } else if (uri.startsWith("spotify:show:")) {
                    val id = uri.removePrefix("spotify:show:")
                    val info = Podcast(sess, id).getPodcastInfo(limit = DETAIL_PAGE_SIZE, offset = offset)
                    val more = info?.episodes?.map { it.toTrackInfo(current.name) } ?: emptyList()
                    val newSize = current.tracks.size + more.size
                    // A short page means we've hit the end; otherwise keep the server-reported total.
                    val newTotalCount = if (more.size < DETAIL_PAGE_SIZE) newSize else (info?.totalEpisodes ?: newSize)
                    _detail.value = current.copy(
                        tracks = current.tracks + more,
                        totalCount = newTotalCount,
                        loadedOffset = newSize
                    )
                }
            } catch (e: Exception) {
                LokiLogger.e(logTag, "loadMoreDetail", e)
            } finally {
                _isLoadingMore.value = false
            }
        }
    }

    private val _isLoadingAll = MutableStateFlow(false)

    /**
     * True while [loadAllTracks] is paging. Lives here rather than in the screen because the button is
     * inside a LazyColumn item, so `remember` state next to it is discarded the moment the header
     * scrolls out of view — which would drop the only guard against starting a second run.
     */
    val isLoadingAll: StateFlow<Boolean> = _isLoadingAll

    /**
     * Loads every remaining page of [forUri], then hands over its complete track list.
     *
     * For "download all", which otherwise covers whichever page happened to be scrolled into view.
     * Drives [loadMoreDetail] rather than repeating its per-type paging: it flips isLoadingMore
     * synchronously, so awaiting that going false is enough to sequence the pages.
     *
     * [forUri] is checked every round and before handing over. Paging a long playlist takes several
     * requests, and _detail is a single live slot — opening another album meanwhile would otherwise
     * page *that* one and hand its tracks to a caller that asked about the first.
     */
    fun loadAllTracks(forUri: String, onComplete: (List<TrackInfo>) -> Unit) {
        if (_isLoadingAll.value) return
        _isLoadingAll.value = true
        viewModelScope.launch {
            try {
                var more = true
                while (more) {
                    val current = _detail.value
                    val before = current.tracks.size
                    more = if (current.uri != forUri || current.totalCount in 0..before) {
                        false
                    } else {
                        loadMoreDetail()
                        _isLoadingMore.first { !it }
                        // A page that added nothing is the end, or a failed request. Either way stop,
                        // rather than re-asking for the same offset until the list happens to grow.
                        _detail.value.tracks.size > before
                    }
                }
                val loaded = _detail.value
                if (loaded.uri == forUri) onComplete(loaded.tracks)
            } finally {
                _isLoadingAll.value = false
            }
        }
    }

    fun checkDetailSaved(type: String, id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val sess = SessionHolder.session ?: return@launch
                detailSaved.value = when (type) {
                    "album" -> Album(sess).isSaved(id)
                    "artist" -> Artist(sess).isFollowing(id)
                    else -> false
                }
            } catch (_: Exception) { detailSaved.value = false }
        }
    }

    fun toggleDetailSaved(type: String, id: String) {
        launchWithSession("toggleDetailSaved") { sess ->
            val currentlySaved = detailSaved.value
            when (type) {
                "album" -> if (currentlySaved) Album(sess).removeFromLibrary(id) else Album(sess).saveToLibrary(id)
                "artist" -> if (currentlySaved) Artist(sess).unfollow(id) else Artist(sess).follow(id)
            }
            detailSaved.value = !currentlySaved
        }
    }

    companion object {
        private const val DETAIL_PAGE_SIZE = 50
    }
}

/**
 * Process-scoped hop so [PlaybackViewModel]'s deep-link + playback-context code can open a detail
 * without a reference to the (screen-scoped) [DetailViewModel]. The live ViewModel registers itself
 * on construction; calls before one exists are dropped (in practice a screen — normally Home — is
 * always composed before a deep link is processed, so one is registered).
 */
object DetailRoutes {
    @Volatile private var target: DetailViewModel? = null

    fun register(vm: DetailViewModel) { target = vm }
    fun unregister(vm: DetailViewModel) { if (target === vm) target = null }

    fun openAlbum(id: String) { target?.openAlbum(id) }
    fun openArtist(id: String) { target?.openArtist(id) }
    fun openPlaylist(id: String) { target?.openPlaylist(id) }
    fun openShow(id: String, publisher: String? = null, imageUrl: String? = null) {
        target?.openShow(id, publisher, imageUrl)
    }
    fun openLikedSongs() { target?.openLikedSongs() }
    fun openAlbumForTrack(trackUri: String) { target?.openAlbumForTrack(trackUri) }
    fun openArtistForTrack(trackUri: String) { target?.openArtistForTrack(trackUri) }
}
