package ch.snepilatch.app.logic.playback

import androidx.annotation.StringRes
import ch.snepilatch.app.R
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

        /** Report where the phone is and whether it is paused into the state machine Connect already has. */
        suspend fun reportToConnect(positionMs: Long, paused: Boolean)

        /** The app's snackbar. */
        fun showMessage(@StringRes id: Int)
    }

    private var offlineWatch: Job? = null
    private var handBack: Job? = null

    /** Completed when the dealer socket is back after a network return; a new wait replaces it. */
    private var dealerBack = CompletableDeferred<Unit>()

    /** The context playback came from when the engine took over, for the way back. */
    private var takeoverContextUri: String? = null

    /** The track Connect had this phone on when the engine took over; while it is still the one playing, Connect needs no play command. */
    private var takeoverTrackUri: String? = null

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

    /**
     * The dealer is back. When the engine is still on the very track Connect had this phone on,
     * the server's state machine is as valid as before the outage: drop offline mode here, before
     * the reconnect resync runs, and let it pick up as it always did. Only the position and the
     * pause state are reported, in case they moved meanwhile. No play command, nothing restarted,
     * the queue kept (#801). A takeover that moved on, or an offline start, still needs the play
     * command in [tryHandBack].
     */
    fun onDealerReconnected() {
        val s = OfflinePlayer.state.value
        val sameTrack = takeoverTrackUri != null && s?.current?.uri == takeoverTrackUri
        if (hooks.isOffline.value && sameTrack) {
            LokiLogger.i(TAG, "Dealer back on ${s?.current?.uri}, the track Connect already has, no hand-back needed")
            handBack?.cancel()
            hooks.isOffline.value = false
            val paused = s?.isPlaying == false
            OfflinePlayer.clear()
            takeoverTrackUri = null
            takeoverContextUri = null
            scope.launch(Dispatchers.IO) { hooks.reportToConnect(hooks.currentPositionMs(), paused) }
        }
        dealerBack.complete(Unit)
    }

    /**
     * A downloaded track ran out. The engine plays on when it can; when the list is used up it
     * stops, and the stop is announced so the silence is not a mystery: nothing more of the
     * playlist is on the phone, or no more downloads at all (#800).
     */
    suspend fun trackEnded() {
        if (OfflinePlayer.ended()) return
        val fromContext = OfflinePlayer.state.value?.contextUri != null
        LokiLogger.i(TAG, "The offline list ran out (${if (fromContext) "a playlist" else "the downloads"}), saying so")
        hooks.showMessage(if (fromContext) R.string.offline_playlist_ended else R.string.offline_downloads_ended)
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
        takeoverTrackUri = current.uri
        val list = listOf(current) + downloadedToCome(current, hooks.queue.value, takeoverContextUri)
        LokiLogger.w(TAG, "Network gone while on ${current.uri}: the offline engine takes over, ${list.size - 1} downloaded tracks to come")
        OfflinePlayer.adopt(list, 0, isPlaying = p.isPlaying && !p.isPaused, durationMs = p.durationMs, contextUri = takeoverContextUri)
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
        // The dealer's return may have settled it already, see onDealerReconnected.
        if (!hooks.isOffline.value) return true
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
        takeoverTrackUri = null
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
