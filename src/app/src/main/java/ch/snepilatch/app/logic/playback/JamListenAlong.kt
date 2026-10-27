package ch.snepilatch.app.logic.playback

import ch.snepilatch.app.logic.shared.AppSettings
import ch.snepilatch.app.logic.shared.JamHolder
import ch.snepilatch.app.logic.shared.LokiLogger
import ch.snepilatch.app.viewmodel.PlaybackViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** Where the jam is, as this account sees it while it only controls the host's playback. */
data class JamPosition(
    val trackUri: String?,
    val title: String,
    val artist: String,
    val imageUrl: String?,
    val durationMs: Long,
    val positionMs: Long,
    val paused: Boolean,
)

/** The local audio the follower drives; [ServiceListenAlongPlayer] is the real one. */
interface ListenAlongPlayer {
    fun load(stream: YouTubeMusicSource.Stream, at: JamPosition, positionMs: Long, play: Boolean)
    fun play(positionMs: Long)
    fun pause()
    fun positionMs(): Long
    fun isPlaying(): Boolean
    fun stop()
}

/**
 * Keeps this phone's audio on the jam: a new track is looked up on YouTube Music and loaded where the
 * jam is, pause and play are matched, and a drift over [DRIFT_MS] is sought away. Nothing here talks to
 * Spfy. A track with no match stays silent until the jam moves on, rather than being retried each check.
 */
internal class ListenAlongFollower(
    private val player: ListenAlongPlayer,
    private val resolve: suspend (JamPosition) -> YouTubeMusicSource.Stream?,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()
    private var loadedUri: String? = null
    private var loadedAt = Long.MIN_VALUE / 2
    private var missedUri: String? = null

    /** [target] is asked again after a lookup, since the jam may have moved on while it ran. */
    suspend fun reconcile(target: () -> JamPosition) = lock.withLock {
        val jam = target()
        val uri = jam.trackUri
        if (uri == null) {
            if (player.isPlaying()) player.pause()
            return@withLock
        }
        if (uri != loadedUri) {
            if (uri == missedUri) return@withLock
            val stream = resolve(jam)
            val latest = target()
            if (latest.trackUri != uri) return@withLock
            if (stream == null) {
                missedUri = uri
                LokiLogger.w(TAG, "No YouTube Music match for ${jam.artist} - ${jam.title}, silent until the next track")
                if (player.isPlaying()) player.pause()
                return@withLock
            }
            player.load(stream, latest, latest.positionMs + LOAD_LEAD_MS, play = !latest.paused)
            loadedUri = uri
            loadedAt = now()
            return@withLock
        }
        if (now() - loadedAt < LOAD_GRACE_MS) return@withLock
        if (jam.paused) {
            if (player.isPlaying()) player.pause()
            return@withLock
        }
        if (!player.isPlaying() || abs(player.positionMs() - jam.positionMs) > DRIFT_MS) {
            player.play(jam.positionMs + SEEK_LEAD_MS)
        }
    }

    fun reset() {
        loadedUri = null
        missedUri = null
    }

    companion object {
        private const val TAG = "JamListenAlong"
        const val DRIFT_MS = 1_500L
        const val LOAD_GRACE_MS = 3_000L
        const val LOAD_LEAD_MS = 400L
        const val SEEK_LEAD_MS = 150L
    }
}

/** [ListenAlongPlayer] over the app's playback service. */
private object ServiceListenAlongPlayer : ListenAlongPlayer {
    private val service get() = MusicPlaybackService.instance

    override fun load(stream: YouTubeMusicSource.Stream, at: JamPosition, positionMs: Long, play: Boolean) {
        service?.playUrl(
            stream.url, at.title, at.artist, at.imageUrl,
            startPlaying = play, headers = stream.headers, startPositionMs = positionMs,
        )
    }

    override fun play(positionMs: Long) { service?.syncPlay(positionMs) }

    override fun pause() { service?.syncPause() }

    override fun positionMs(): Long = service?.getCurrentPosition() ?: 0L

    override fun isPlaying(): Boolean = service?.isPlaying() == true

    override fun stop() { service?.stop() }
}

/**
 * Listening along to a Spfy jam on this phone for an account the server only lets control it (a Free
 * account joins as CONTROL; listening is refused to it). The jam's track plays from YouTube Music and
 * this device never plays anything on Spfy, since doing so drops the account from the jam (verified
 * live). While [active], the playback view model leaves the service's end, error, transition and remote
 * play events to this engine, so none of them reaches Spfy. Ends with the jam.
 */
object JamListenAlong {
    val active = MutableStateFlow(false)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var jobs: List<Job> = emptyList()
    private var vm: PlaybackViewModel? = null
    private val follower = ListenAlongFollower(ServiceListenAlongPlayer, resolve = { jam ->
        YouTubeMusicSource.resolve(jam.title, jam.artist, AppSettings.effectiveRegion(), jam.durationMs)
    })

    fun bind(viewModel: PlaybackViewModel) {
        vm = viewModel
    }

    fun start() {
        val model = vm ?: return
        if (active.value) return
        active.value = true
        follower.reset()
        LokiLogger.i(TAG, "Listening along to the jam via YouTube Music")
        jobs = listOf(
            scope.launch {
                combine(model.currentTrack, model.isPausedFlow) { track, paused -> track?.uri to paused }
                    .distinctUntilChanged()
                    .collect { follow(model) }
            },
            scope.launch {
                while (isActive) {
                    delay(CHECK_MS)
                    follow(model)
                }
            },
            scope.launch {
                JamHolder.session.collect { if (it == null || !it.active) stop() }
            },
        )
    }

    fun stop() {
        if (!active.value) return
        active.value = false
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        follower.reset()
        scope.launch(Dispatchers.Main) { ServiceListenAlongPlayer.stop() }
        LokiLogger.i(TAG, "Stopped listening along")
    }

    /** On the main thread: the player is ExoPlayer, which only answers there. The lookup moves to IO itself. */
    private suspend fun follow(model: PlaybackViewModel) {
        model.ensurePlaybackService()
        withContext(Dispatchers.Main) { follower.reconcile { snapshot(model) } }
    }

    private fun snapshot(model: PlaybackViewModel): JamPosition {
        val track = model.currentTrack.value
        return JamPosition(
            trackUri = track?.uri,
            title = track?.name.orEmpty(),
            artist = track?.artist.orEmpty(),
            imageUrl = track?.albumArt,
            durationMs = model.durationFlow.value,
            positionMs = model.positionFlow.value,
            paused = model.isPausedFlow.value,
        )
    }

    private const val TAG = "JamListenAlong"
    private const val CHECK_MS = 2_000L
}
