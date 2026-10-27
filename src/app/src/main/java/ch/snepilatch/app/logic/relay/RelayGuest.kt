package ch.snepilatch.app.logic.relay

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ch.snepilatch.app.logic.shared.LokiLogger
import kotlin.math.abs

/**
 * A guest's side of a relay jam: keeps this device's own playback on the host's. Every host update,
 * every local change and a periodic check [reconcile] the two: a different track is played in the
 * host's context, a position more than [DRIFT_MS] off is sought to where the host is now, and the
 * pause state is matched. Each correction waits out a cooldown, so a command still landing is not
 * sent again.
 */
class RelayGuest(
    private val scope: CoroutineScope,
    private val player: RelayHostPlayer,
    private val serverNow: () -> Long,
    private val applying: (Boolean) -> Unit = {},
) {
    private val lock = Mutex()
    private var jobs: List<Job> = emptyList()

    @Volatile private var host: RelayPlayback? = null

    private var trackCommand: Pair<String, Long>? = null
    private var lastSeekAt = Long.MIN_VALUE / 2
    private var lastPauseAt = Long.MIN_VALUE / 2

    // The host's next track this guest put at the front of its own queue, so it is not put there twice.
    private var placedNext: String? = null

    fun start() {
        if (jobs.isNotEmpty()) return
        jobs = listOf(
            scope.launch { player.state.distinctUntilChanged().collect { reconcile() } },
            scope.launch {
                while (isActive) {
                    delay(CHECK_MS)
                    reconcile()
                }
            },
        )
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        host = null
    }

    fun follow(playback: RelayPlayback) {
        host = playback
        scope.launch { reconcile() }
    }

    /**
     * The guest's own seek or pause, applied here at once while the command travels to the host, so
     * the controls answer immediately and the check that follows does not undo it.
     */
    suspend fun applyLocally(command: RelayCommand) = lock.withLock {
        val now = serverNow()
        when (command.kind) {
            RelayCommand.SEEK -> command.positionMs?.let {
                lastSeekAt = now
                applied { player.seek(it) }
            }
            RelayCommand.PAUSE -> {
                lastPauseAt = now
                applied { player.pause() }
            }
            RelayCommand.RESUME -> {
                lastPauseAt = now
                applied { player.resume() }
            }
            else -> Unit
        }
    }

    /**
     * This device's track ran out and Kotify held instead of picking a next one: play the host's next
     * now. The host is either still on the track that ended here (its next is the head of its queue) or
     * has already moved on (its current one). Nothing when neither is known; the next update brings it.
     */
    suspend fun trackEnded() = lock.withLock {
        val target = host ?: return@withLock
        val ended = player.current().trackUri
        val next = if (target.trackUri == ended) target.queue.firstOrNull()?.uri else target.trackUri
        LokiLogger.i(TAG, "Track $ended ended here; host on ${target.trackUri}, queue ${target.queue.size}, playing $next")
        if (next != null) playTrack(target, next, serverNow())
    }

    suspend fun reconcile() = lock.withLock {
        val target = host ?: return@withLock
        val trackUri = target.trackUri ?: return@withLock
        val now = serverNow()
        val local = player.current()
        if (local.trackUri != trackUri) {
            // Spfy advanced this account onto the host's next a moment before the host's own update:
            // the host is at its track's end and about to say the same, so this is not a mismatch.
            val aheadByOne = local.trackUri == target.queue.firstOrNull()?.uri &&
                target.durationMs - target.positionAt(now) <= ADVANCE_GRACE_MS
            if (!aheadByOne) playTrack(target, trackUri, now)
            return@withLock
        }
        val sinceTrackCommand = trackCommand?.takeIf { it.first == trackUri }?.let { now - it.second } ?: Long.MAX_VALUE
        if (sinceTrackCommand < LOAD_GRACE_MS) return@withLock
        matchPosition(target, now)
        matchPause(target, local, now)
        prepareNext(target, now)
    }

    /**
     * What makes a track change seamless, as everyone in a Spfy jam advancing through one shared list:
     * near the end, the host's next is put at the front of this account's own queue, so Spfy's natural
     * advance plays it with its audio fetched ahead and no command at the boundary. Kotify holds at the
     * end only while this account's next is something else; [trackEnded] then plays the host's next.
     */
    private suspend fun prepareNext(target: RelayPlayback, now: Long) {
        val hostNext = target.queue.firstOrNull()?.uri
        val mine = player.nextUri
        player.holdAtTrackEnd = hostNext == null || mine != hostNext
        if (hostNext == null || mine == hostNext || hostNext == placedNext) return
        if (target.durationMs - target.positionAt(now) > PREPARE_MS) return
        val previous = placedNext
        placedNext = hostNext
        applied { player.playNext(hostNext, replacing = previous) }
    }

    private suspend fun playTrack(target: RelayPlayback, trackUri: String, now: Long) {
        val previous = trackCommand
        if (previous != null && previous.first == trackUri && now - previous.second < TRACK_RETRY_MS) return
        trackCommand = trackUri to now
        applied { player.play(trackUri, target.contextUri) }
    }

    private suspend fun matchPosition(target: RelayPlayback, now: Long) {
        if (now - lastSeekAt < SEEK_COOLDOWN_MS) return
        val expected = target.positionAt(now)
        if (abs(player.positionMs.value - expected) <= DRIFT_MS) return
        lastSeekAt = now
        applied { player.seek(if (target.paused) expected else expected + SEEK_LEAD_MS) }
    }

    private suspend fun matchPause(target: RelayPlayback, local: RelayHostPlayer.State, now: Long) {
        if (local.paused == target.paused || now - lastPauseAt < PAUSE_COOLDOWN_MS) return
        lastPauseAt = now
        applied { if (target.paused) player.pause() else player.resume() }
    }

    private suspend fun applied(block: suspend () -> Unit) {
        applying(true)
        try {
            block()
        } finally {
            applying(false)
        }
    }

    companion object {
        private const val TAG = "RelayGuest"
        const val DRIFT_MS = 2_000L
        const val CHECK_MS = 5_000L
        const val LOAD_GRACE_MS = 1_500L
        const val TRACK_RETRY_MS = 8_000L
        const val SEEK_COOLDOWN_MS = 3_000L
        const val PAUSE_COOLDOWN_MS = 2_000L
        const val SEEK_LEAD_MS = 300L
        const val PREPARE_MS = 20_000L
        const val ADVANCE_GRACE_MS = 3_000L
    }
}
