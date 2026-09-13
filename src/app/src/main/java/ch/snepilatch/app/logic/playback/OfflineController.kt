package ch.snepilatch.app.logic.playback

import ch.snepilatch.app.data.PlaybackUiState
import ch.snepilatch.app.data.TrackInfo
import ch.snepilatch.app.logic.download.Downloads
import ch.snepilatch.app.logic.download.toTrackInfo
import ch.snepilatch.app.logic.shared.LokiLogger
import ch.snepilatch.app.logic.shared.NetworkState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The offline engine's side of the playback view model: mirrors [OfflinePlayer]'s state into the
 * flows every screen reads, watches the network, takes over in place when the signal goes
 * mid-listening, and hands playback back to Connect when the network returns (#789, #792, #793).
 * What only the view model can do, the stream commit, the ticker, the session and the Connect
 * commands, stays behind [Hooks].
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

        /** Bring the session up from the saved cookies, the way launch does. */
        fun startSession()

        /** The Connect player once the device is registered, or null after [timeoutMs]. */
        suspend fun awaitPlayer(timeoutMs: Long): Boolean

        /** ExoPlayer's position; it is confined to the main thread, so this hops there. */
        suspend fun currentPositionMs(): Long

        /**
         * Make Connect play [track] in [contextUri] from [positionMs], paused or not, without
         * touching the audio that is already running.
         */
        suspend fun handBackToConnect(track: TrackInfo, contextUri: String?, positionMs: Long, paused: Boolean)
    }

    private var offlineWatch: Job? = null
    private var handBack: Job? = null

    /** Completed when the dealer socket is back after a network return; a new wait replaces it. */
    private var dealerBack = CompletableDeferred<Unit>()

    /** The context playback came from when the engine took over, for the way back. */
    private var takeoverContextUri: String? = null

    fun start() {
        scope.launch { OfflinePlayer.state.collect { s -> if (s != null && hooks.isOffline.value) mirror(s) } }
        scope.launch {
            NetworkState.online.collect { online ->
                offlineWatch?.cancel()
                when {
                    online && hooks.isOffline.value -> handBackWhenReady()
                    // Short blips are the dealer's reconnect business: the engine takes over only
                    // once the network has stayed gone for a moment.
                    !online && !hooks.isOffline.value && hooks.hasSession() -> {
                        offlineWatch = launch(Dispatchers.IO) {
                            delay(GRACE_MS)
                            if (!NetworkState.online.value) takeOver()
                        }
                    }
                }
            }
        }
    }

    fun onDealerReconnected() {
        dealerBack.complete(Unit)
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
        takeoverContextUri = hooks.playingContextUri()
        val p = hooks.playback.value
        val current = p.track ?: run {
            LokiLogger.w(TAG, "Network gone with nothing playing, offline with downloads only")
            return
        }
        val list = listOf(current) + downloadedToCome(current, hooks.queue.value, takeoverContextUri)
        LokiLogger.w(TAG, "Network gone while on ${current.uri}: the offline engine takes over, ${list.size - 1} downloaded tracks to come")
        OfflinePlayer.adopt(list, 0, isPlaying = p.isPlaying && !p.isPaused, durationMs = p.durationMs)
    }

    /**
     * The network is back while the engine owns playback. Bring the session up if the app started
     * offline, wait for the device to be registered (and the dealer to be back when the session
     * survived), then let Connect adopt the playing track in its context at its position. The
     * audio never stops; the engine steps aside once Connect has it. If nothing comes up the
     * engine keeps playing and the next network change tries again.
     */
    private fun handBackWhenReady() {
        if (handBack?.isActive == true) return
        handBack = scope.launch(Dispatchers.IO) {
            // A session that does not come up (the network still settling, a request that times
            // out) leaves the engine playing; while the network stays up this tries again.
            while (hooks.isOffline.value && NetworkState.online.value) {
                if (tryHandBack()) return@launch
                LokiLogger.w(TAG, "Staying offline, trying the way back again in ${RETRY_MS}ms")
                delay(RETRY_MS)
            }
        }
    }

    private suspend fun tryHandBack(): Boolean {
        val hadSession = hooks.hasSession()
        if (hadSession) dealerBack = CompletableDeferred() else hooks.startSession()
        if (!hooks.awaitPlayer(PLAYER_WAIT_MS)) {
            LokiLogger.w(TAG, "Network back but no player within ${PLAYER_WAIT_MS}ms")
            return false
        }
        if (hadSession && withTimeoutOrNull(DEALER_WAIT_MS) { dealerBack.await() } == null) {
            LokiLogger.w(TAG, "Dealer not back within ${DEALER_WAIT_MS}ms, handing back anyway")
        }
        val s = OfflinePlayer.state.value
        val current = s?.current
        val position = hooks.currentPositionMs()
        hooks.isOffline.value = false
        if (current != null) {
            val context = Downloads.find(current.uri)?.contextUri ?: takeoverContextUri
            LokiLogger.i(TAG, "Network back: Connect adopts ${current.uri} in ${context ?: "no context"} at ${position}ms")
            hooks.handBackToConnect(current, context, position, paused = !s.isPlaying)
        } else {
            LokiLogger.i(TAG, "Network back with nothing playing offline, back online")
        }
        OfflinePlayer.clear()
        takeoverContextUri = null
        return true
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
        const val PLAYER_WAIT_MS = 60_000L
        const val DEALER_WAIT_MS = 15_000L
        const val RETRY_MS = 60_000L
    }
}
