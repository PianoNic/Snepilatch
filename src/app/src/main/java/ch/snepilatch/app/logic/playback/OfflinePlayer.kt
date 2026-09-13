package ch.snepilatch.app.logic.playback

import ch.snepilatch.app.data.TrackInfo
import ch.snepilatch.app.logic.download.Downloads
import ch.snepilatch.app.logic.download.toTrackInfo
import ch.snepilatch.app.logic.shared.LokiLogger
import kotify.cdn.StreamResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * What the offline player is on: the list in play order, where in it, and how it plays. [tracks]
 * is the order tracks play in, which is the tapped list until shuffle rearranges it.
 */
data class OfflinePlayback(
    val tracks: List<TrackInfo>,
    val index: Int,
    val isPlaying: Boolean,
    val durationMs: Long,
    val shuffle: Boolean = false,
    /** `off`, `context` (the list starts over) or `track`, the same words the cluster uses. */
    val repeat: String = "off",
    /** The playlist or album the list came from, null for the downloads list itself. */
    val contextUri: String? = null,
) {
    val current: TrackInfo? get() = tracks.getOrNull(index)

    /** What the queue sheet shows: everything after the current track, in play order. */
    val upcoming: List<TrackInfo> get() = tracks.drop(index + 1)
}

/**
 * The offline state machine. Without a session there is no Connect and no server-side state
 * machine, so this owns what the online one would: the list, the pointer into it, playing or
 * paused, the duration, shuffle and repeat. It drives [MusicPlaybackService] straight from the
 * downloaded files and publishes [state]; the playback view model mirrors that into the one
 * playback state every screen reads, so the player UI needs no offline branch of its own.
 * Process-scoped like [ch.snepilatch.app.logic.shared.SessionHolder], so an offline cold launch
 * reaches it too.
 *
 * The list the user tapped from is the queue. When it runs out playback stops on the last track,
 * paused; it does not wander into unrelated downloads (#791). Signal loss and the way back to
 * Connect follow (#792, #793).
 */
object OfflinePlayer {

    private val _state = MutableStateFlow<OfflinePlayback?>(null)
    val state: StateFlow<OfflinePlayback?> = _state.asStateFlow()

    /** The tapped order, kept so turning shuffle off restores it. */
    private var unshuffled: List<TrackInfo> = emptyList()

    /** Seams for tests: where a track's local file comes from and which service plays it. */
    internal var localFile: (uri: String, title: String, artist: String) -> String? = ::localCopyOf
    internal var service: () -> MusicPlaybackService? = { MusicPlaybackService.instance }

    /**
     * Start [tracks] at [index] from its downloaded copy: a new list, in the order given, with
     * shuffle off. False when that track has no local copy. Repeat carries over, like a setting.
     */
    suspend fun play(tracks: List<TrackInfo>, index: Int, contextUri: String? = null): Boolean {
        val repeat = _state.value?.repeat ?: "off"
        if (!load(tracks, index, shuffle = false, repeat = repeat, contextUri = contextUri)) return false
        unshuffled = tracks
        LokiLogger.i(TAG, "Playing the downloaded copy of ${tracks[index].uri} (${index + 1} of ${tracks.size})")
        return true
    }

    /**
     * Start from a tapped row. The list the row came from is the queue: the context's downloads
     * when the row carried a [contextUri], else every download, in the order the index lists them.
     * A track the index does not hold plays on its own.
     */
    suspend fun playFromDownloads(track: TrackInfo, contextUri: String?): Boolean {
        val list = Downloads.rows.value
            .filter { contextUri == null || it.contextUri == contextUri }
            .map { it.toTrackInfo() }
            .takeIf { rows -> rows.any { it.uri == track.uri } }
            ?: listOf(track)
        return play(list, list.indexOfFirst { it.uri == track.uri }.coerceAtLeast(0), contextUri)
    }

    /**
     * Take over what is already playing, without touching the audio: the signal went while
     * online, so [tracks] is the playing track followed by what of the queue and its context is on
     * the phone, and ExoPlayer keeps running whatever it has. Repeat carries over (#792).
     */
    fun adopt(tracks: List<TrackInfo>, index: Int, isPlaying: Boolean, durationMs: Long, contextUri: String? = null) {
        val current = tracks.getOrNull(index) ?: return
        val repeat = _state.value?.repeat ?: "off"
        _state.value = OfflinePlayback(tracks, index, isPlaying, durationMs, shuffle = false, repeat = repeat, contextUri = contextUri)
        unshuffled = tracks
        LokiLogger.i(TAG, "Took over ${current.uri} with ${tracks.size - index - 1} downloaded tracks to come")
    }

    /** Move within the current list: same list, same shuffle and repeat, new pointer. */
    private suspend fun playAt(index: Int): Boolean {
        val s = _state.value ?: return false
        if (!load(s.tracks, index, s.shuffle, s.repeat, s.contextUri)) return false
        LokiLogger.i(TAG, "Now on ${s.tracks[index].uri} (${index + 1} of ${s.tracks.size})")
        return true
    }

    private suspend fun load(tracks: List<TrackInfo>, index: Int, shuffle: Boolean, repeat: String, contextUri: String?): Boolean {
        val track = tracks.getOrNull(index) ?: return false
        val title = track.name.ifBlank { "Unknown" }
        val artist = track.artist.ifBlank { "Unknown" }
        val url = localFile(track.uri, title, artist) ?: return false
        withContext(Dispatchers.Main) {
            val svc = service() ?: return@withContext
            svc.stop()
            // Nothing to record: the file is already on disk.
            svc.stopCapture()
            svc.playUrl(url, title, artist, track.albumArt, startPlaying = true)
        }
        _state.value = OfflinePlayback(
            tracks, index, isPlaying = true, durationMs = track.durationMs, shuffle = shuffle, repeat = repeat, contextUri = contextUri,
        )
        return true
    }

    /** ExoPlayer knows the real length once the file is open; the index may not have had it. */
    fun durationKnown(durationMs: Long) {
        if (durationMs <= 0) return
        _state.update { it?.copy(durationMs = durationMs) }
    }

    /**
     * The file ran out. Repeat track plays it again, otherwise the next track of the list plays;
     * at the end the list starts over under repeat context and stops, paused on the last track,
     * without it. False when it stopped, so the caller can say so.
     */
    suspend fun ended(): Boolean {
        val s = _state.value ?: return false
        val advanced = when {
            s.repeat == "track" -> playAt(s.index)
            s.index + 1 < s.tracks.size -> playAt(s.index + 1)
            s.repeat == "context" && s.tracks.isNotEmpty() -> playAt(0)
            else -> false
        }
        if (!advanced) _state.update { it?.copy(isPlaying = false) }
        return advanced
    }

    /** Pause or resume what is loaded; ExoPlayer keeps the position. */
    suspend fun togglePlayPause() {
        val s = _state.value ?: return
        withContext(Dispatchers.Main) {
            val svc = service() ?: return@withContext
            if (s.isPlaying) svc.syncPause() else svc.syncPlay(svc.getCurrentPosition())
        }
        _state.update { it?.copy(isPlaying = !s.isPlaying) }
    }

    suspend fun seekTo(positionMs: Long) {
        if (_state.value == null) return
        withContext(Dispatchers.Main) { service()?.syncSeek(positionMs) }
    }

    /** The next track of the list; at the end it starts over under repeat context, else nothing changes. */
    suspend fun next(): Boolean {
        val s = _state.value ?: return false
        return when {
            s.index + 1 < s.tracks.size -> playAt(s.index + 1)
            s.repeat == "context" && s.tracks.isNotEmpty() -> playAt(0)
            else -> false
        }
    }

    /**
     * Back to the start of the current track, or to the previous one when the track has only just
     * begun, the same threshold the online player uses.
     */
    suspend fun previous(): Boolean {
        val s = _state.value ?: return false
        val position = withContext(Dispatchers.Main) { service()?.getCurrentPosition() ?: 0L }
        if (position > PREV_RESTART_THRESHOLD_MS || s.index == 0) {
            seekTo(0L)
            return false
        }
        return playAt(s.index - 1)
    }

    /** Play the [upcomingIndex]th entry of [OfflinePlayback.upcoming]. */
    suspend fun jumpTo(upcomingIndex: Int): Boolean {
        val s = _state.value ?: return false
        return playAt(s.index + 1 + upcomingIndex)
    }

    /** Drop [track] from what is still to come; the current track and what played stay. */
    fun remove(track: TrackInfo) {
        _state.update { s ->
            s ?: return@update null
            val at = s.tracks.withIndex().firstOrNull { it.index > s.index && it.value.uri == track.uri }?.index
                ?: return@update s
            s.copy(tracks = s.tracks.toMutableList().apply { removeAt(at) })
        }
        unshuffled = unshuffled.filterNot { it.uri == track.uri }
    }

    /** Move [track] to [toUpcomingIndex] among what is still to come. */
    fun move(track: TrackInfo, toUpcomingIndex: Int) {
        _state.update { s ->
            s ?: return@update null
            val upcoming = s.upcoming.toMutableList()
            val from = upcoming.indexOfFirst { it.uri == track.uri }
            if (from < 0) return@update s
            val to = toUpcomingIndex.coerceIn(0, upcoming.lastIndex)
            upcoming.add(to, upcoming.removeAt(from))
            s.copy(tracks = s.tracks.take(s.index + 1) + upcoming)
        }
    }

    /** Shuffle keeps the current track where it is and mixes the rest; off restores the tapped order. */
    fun toggleShuffle() {
        _state.update { s ->
            s ?: return@update null
            val current = s.current ?: return@update s
            if (!s.shuffle) {
                val rest = s.tracks.filterIndexed { i, _ -> i != s.index }.shuffled()
                s.copy(tracks = listOf(current) + rest, index = 0, shuffle = true)
            } else {
                val index = unshuffled.indexOfFirst { it.uri == current.uri }.coerceAtLeast(0)
                s.copy(tracks = unshuffled, index = index, shuffle = false)
            }
        }
    }

    /** The web player's cycle: off, context, track, off. */
    fun cycleRepeat() {
        _state.update { s ->
            s ?: return@update null
            s.copy(
                repeat = when (s.repeat) {
                    "off" -> "context"
                    "context" -> "track"
                    else -> "off"
                }
            )
        }
    }

    fun clear() {
        _state.value = null
        unshuffled = emptyList()
    }

    private fun localCopyOf(uri: String, title: String, artist: String): String? =
        (AudioSourceResolver.localOrNull(uri, title, artist) as? StreamResult.Success)?.info?.url

    private const val TAG = "OfflinePlayer"
    private const val PREV_RESTART_THRESHOLD_MS = 3000L
}
