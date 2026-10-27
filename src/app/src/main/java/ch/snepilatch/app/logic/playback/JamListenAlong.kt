package ch.snepilatch.app.logic.playback

import ch.snepilatch.app.logic.download.ArgonFetchSource
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
    /** True while a track is being looked up and loaded, for the play button's spinner. */
    private val loading: (Boolean) -> Unit = {},
) {
    /** The jam's next track, looked up ahead so a change plays at once; one entry, replaced as the queue moves. */
    private var prefetched: Pair<String, YouTubeMusicSource.Stream>? = null

    private val lock = Mutex()
    private var loadedUri: String? = null
    private var loadedAt = Long.MIN_VALUE / 2
    private var missedUri: String? = null
    private var failures = 0
    private var loadedUriBeforeFailure: String? = null

    /** [target] is asked again after a lookup, since the jam may have moved on while it ran. */
    suspend fun reconcile(target: () -> JamPosition) = lock.withLock {
        val jam = target()
        val uri = jam.trackUri
        if (uri == null) {
            if (player.isPlaying()) player.pause()
            return@withLock
        }
        if (uri != loadedUri) {
            // A skip's first state can carry the uri before the name; the lookup and the notification need it.
            if (uri == missedUri || jam.title.isBlank()) return@withLock
            val ahead = prefetched?.takeIf { it.first == uri }?.second
            loading(true)
            val stream = ahead ?: resolve(jam)
            val latest = target()
            if (latest.trackUri != uri) return@withLock
            if (stream == null) {
                missedUri = uri
                loading(false)
                LokiLogger.w(TAG, "No YouTube Music match for ${jam.artist} - ${jam.title}, silent until the next track")
                if (player.isPlaying()) player.pause()
                return@withLock
            }
            if (ahead != null) LokiLogger.i(TAG, "Playing the prefetched ${jam.title}")
            player.load(stream, latest, latest.positionMs + LOAD_LEAD_MS, play = !latest.paused)
            if (uri != loadedUriBeforeFailure) failures = 0
            loadedUri = uri
            loadedUriBeforeFailure = uri
            loadedAt = now()
            return@withLock
        }
        if (now() - loadedAt < LOAD_GRACE_MS) return@withLock
        if (jam.paused) {
            if (player.isPlaying()) player.pause()
            return@withLock
        }
        if (!player.isPlaying() || abs(player.positionMs() - jam.positionMs) > DRIFT_MS) {
            LokiLogger.i(TAG, "Resync: here ${player.positionMs()} ms (playing=${player.isPlaying()}), jam ${jam.positionMs} ms")
            player.play(jam.positionMs + SEEK_LEAD_MS)
            // A seek rebuffers, which stalls the position; without a fresh grace the next check sees that
            // as drift and seeks again, and the audio stops and starts every check.
            loadedAt = now()
        }
    }

    /** The track whose stream is loaded on this phone, or null. */
    val loadedTrack: String? get() = loadedUri

    /** Looks the jam's [next] track up ahead, once, so the change to it needs no lookup. */
    suspend fun prefetch(next: JamPosition?) {
        val uri = next?.trackUri ?: return
        if (uri == loadedUri || uri == missedUri || prefetched?.first == uri) return
        val stream = resolve(next) ?: return
        prefetched = uri to stream
    }

    /** The loaded stream failed: load the track again at the next check, and give up on it after [MAX_FAILURES]. */
    fun failed() {
        val uri = loadedUri ?: return
        failures++
        loadedUri = null
        if (failures >= MAX_FAILURES) {
            LokiLogger.w(TAG, "Stream for $uri failed $failures times, silent until the next track")
            missedUri = uri
        }
    }

    fun reset() {
        loadedUri = null
        missedUri = null
        failures = 0
        prefetched = null
    }

    companion object {
        private const val TAG = "JamListenAlong"
        const val DRIFT_MS = 1_500L
        const val LOAD_GRACE_MS = 3_000L

        // What an ArgonFetch stream takes from load to ready (1.2 to 1.8 s measured), so it starts in step.
        const val LOAD_LEAD_MS = 1_500L
        const val SEEK_LEAD_MS = 150L
        const val MAX_FAILURES = 2
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
        service?.knownKbps = stream.kbps
    }

    override fun play(positionMs: Long) { service?.syncPlay(positionMs) }

    override fun pause() { service?.syncPause() }

    override fun positionMs(): Long = service?.getCurrentPosition() ?: 0L

    // Told to play, buffering included: a rebuffer is not a pause to correct.
    override fun isPlaying(): Boolean = service?.playWhenReady() == true

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

    /**
     * Still in the jam listened along to before a cold boot. Nothing plays by itself, as the web player
     * does nothing on finding a session of its own; the jam banner offers to resume instead.
     */
    val resumable = MutableStateFlow(false)
    private var watch: Job? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var jobs: List<Job> = emptyList()
    private var vm: PlaybackViewModel? = null

    // ArgonFetch first, as downloads do: googlevideo refuses the urls the InnerTube player hands out, so
    // the direct route mostly ends in a 403. It stays as the fallback for when ArgonFetch has no answer.
    private val follower = ListenAlongFollower(
        ServiceListenAlongPlayer,
        resolve = { jam ->
            jam.trackUri?.let { ArgonFetchSource.audio(it) }
                ?.let { YouTubeMusicSource.Stream(it.url, it.mimeType, emptyMap(), it.kbps) }
                ?: YouTubeMusicSource.resolve(jam.title, jam.artist, AppSettings.effectiveRegion(), jam.durationMs)
        },
        loading = { vm?.isStreamLoading?.value = it },
    )

    /** The service's player failed on the stream; called from the playback view model's error hook. */
    fun playbackFailed() = follower.failed()

    fun bind(viewModel: PlaybackViewModel) {
        vm = viewModel
        if (watch != null) return
        watch = scope.launch {
            var seen = false
            JamHolder.session.collect { jam ->
                val saved = AppSettings.listenAlongSession
                // Only the Spfy jam listened along to; a relay jam plays through its own guest.
                resumable.value = jam != null && !active.value && saved != null && jam.sessionId == saved &&
                    !ch.snepilatch.app.logic.relay.RelayJamMapper.isRelay(jam)
                // Left, ended, or another jam: nothing to come back to.
                if (jam?.sessionId != saved && (jam != null || seen)) AppSettings.listenAlongSession = null
                seen = seen || jam != null
            }
        }
    }

    fun start() {
        val model = vm ?: return
        if (active.value) return
        active.value = true
        resumable.value = false
        AppSettings.listenAlongSession = JamHolder.session.value?.sessionId
        follower.reset()
        LokiLogger.i(TAG, "Listening along to the jam via YouTube Music")
        jobs = listOf(
            scope.launch {
                combine(model.currentTrack, model.isPausedFlow) { track, paused -> Triple(track?.uri, track?.name, paused) }
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
                // A left or ended jam clears the holder; the join response's session carries no `active`.
                JamHolder.session.collect { if (it == null) stop() }
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
        vm?.streamProvider?.let { if (it.value == PROVIDER) it.value = null }
        LokiLogger.i(TAG, "Stopped listening along")
    }

    /** On the main thread: the player is ExoPlayer, which only answers there. The lookup moves to IO itself. */
    private suspend fun follow(model: PlaybackViewModel) {
        model.ensurePlaybackService()
        withContext(Dispatchers.Main) { follower.reconcile { snapshot(model) } }
        // What the source and quality pills show. Set on every check: the view model clears it whenever
        // the jam's track changes, as it would for a Connect stream of its own.
        if (follower.loadedTrack != null) model.streamProvider.value = PROVIDER
        val next = model.queue.value.firstOrNull()
        follower.prefetch(next?.let { JamPosition(it.uri, it.name, it.artist, it.albumArt, it.durationMs, 0, paused = false) })
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
    private const val PROVIDER = "YouTube Music"
}
