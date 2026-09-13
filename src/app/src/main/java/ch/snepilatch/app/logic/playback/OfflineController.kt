package ch.snepilatch.app.logic.playback

import ch.snepilatch.app.data.PlaybackUiState
import ch.snepilatch.app.data.TrackInfo
import ch.snepilatch.app.logic.download.Downloads
import ch.snepilatch.app.logic.download.toTrackInfo
import ch.snepilatch.app.logic.shared.LokiLogger
import ch.snepilatch.app.logic.shared.NetworkState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * The offline engine's side of the playback view model: mirrors [OfflinePlayer]'s state into the
 * flows every screen reads, watches the network, and takes over in place when the signal goes
 * mid-listening (#789, #792). What only the view model can do, the stream commit and the position
 * ticker, stays behind [Hooks]. The way back to Connect is #793.
 */
class OfflineController(private val scope: CoroutineScope, private val hooks: Hooks) {

    interface Hooks {
        val playback: MutableStateFlow<PlaybackUiState>
        val queue: MutableStateFlow<List<TrackInfo>>
        val queuedCount: MutableStateFlow<Int>
        val nextPreview: MutableStateFlow<TrackInfo?>
        val prevPreview: MutableStateFlow<TrackInfo?>
        val isOffline: MutableStateFlow<Boolean>

        /** Whether a session is up; the network going before one exists is the launch path's business. */
        fun hasSession(): Boolean
        fun playingContextUri(): String?

        /** A different track is current now: commit the stream, follow the art. */
        fun trackChanged(track: TrackInfo)
        fun setTickerRunning(running: Boolean)
    }

    private var offlineWatch: Job? = null

    fun start() {
        scope.launch { OfflinePlayer.state.collect { s -> if (s != null && hooks.isOffline.value) mirror(s) } }
        // Short blips are the dealer's reconnect business: the engine takes over only once the
        // network has stayed gone for a moment.
        scope.launch {
            NetworkState.online.collect { online ->
                offlineWatch?.cancel()
                if (online || hooks.isOffline.value || !hooks.hasSession()) return@collect
                offlineWatch = launch(Dispatchers.IO) {
                    delay(GRACE_MS)
                    if (!NetworkState.online.value) takeOver()
                }
            }
        }
    }

    /** The engine's state, written into the one playback state and the queue the screens read. */
    private fun mirror(s: OfflinePlayback) {
        val cur = hooks.playback.value
        val sameTrack = cur.track?.uri == s.current?.uri
        hooks.playback.value = cur.copy(
            track = s.current,
            isPlaying = s.isPlaying,
            isPaused = !s.isPlaying,
            durationMs = s.durationMs,
            positionMs = if (sameTrack) cur.positionMs else 0L,
            shuffleMode = if (s.shuffle) "on" else "off",
            isShuffling = s.shuffle,
            repeatMode = s.repeat,
        )
        hooks.queue.value = s.upcoming
        hooks.queuedCount.value = 0
        hooks.nextPreview.value = s.upcoming.firstOrNull()
        hooks.prevPreview.value = s.tracks.getOrNull(s.index - 1)
        if (!sameTrack) s.current?.let(hooks::trackChanged)
        hooks.setTickerRunning(s.isPlaying)
    }

    /**
     * The network is gone while a session is up: hand what plays to the engine in place, without
     * touching the audio. Its list is the playing track, then what of the queue is on the phone,
     * then the rest of the playing context from the downloads index. Tracks that are not
     * downloaded are dropped; when nothing of the context is downloaded the engine stops after
     * the current track.
     */
    fun takeOver() {
        if (hooks.isOffline.value) return
        hooks.isOffline.value = true
        val p = hooks.playback.value
        val current = p.track ?: run {
            LokiLogger.w(TAG, "Network gone with nothing playing, offline with downloads only")
            return
        }
        val list = listOf(current) + downloadedToCome(current, hooks.queue.value, hooks.playingContextUri())
        LokiLogger.w(TAG, "Network gone while on ${current.uri}: the offline engine takes over, ${list.size - 1} downloaded tracks to come")
        OfflinePlayer.adopt(list, 0, isPlaying = p.isPlaying && !p.isPaused, durationMs = p.durationMs)
    }

    /** The queue's downloaded entries in queue order, then the context's other downloads. */
    private fun downloadedToCome(current: TrackInfo, queue: List<TrackInfo>, contextUri: String?): List<TrackInfo> {
        val index = Downloads.index.value
        val fromQueue = queue.filter { Downloads.isDownloaded(index, it.uri, it.name, it.artist) }
        val seen = (fromQueue.map { it.uri } + current.uri).toMutableSet()
        val fromContext = contextUri?.let { ctx ->
            Downloads.rows.value.filter { it.contextUri == ctx && seen.add(it.trackUri) }.map { it.toTrackInfo() }
        }.orEmpty()
        return fromQueue + fromContext
    }

    private companion object {
        const val TAG = "OfflineController"

        /** How long the network has to stay gone before the engine takes over. */
        const val GRACE_MS = 5_000L
    }
}
