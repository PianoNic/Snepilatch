package ch.snepilatch.app.logic.relay

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    suspend fun reconcile() = lock.withLock {
        val target = host ?: return@withLock
        val trackUri = target.trackUri ?: return@withLock
        val now = serverNow()
        val local = player.current()
        if (local.trackUri != trackUri) {
            playTrack(target, trackUri, now)
            return@withLock
        }
        val sinceTrackCommand = trackCommand?.takeIf { it.first == trackUri }?.let { now - it.second } ?: Long.MAX_VALUE
        if (sinceTrackCommand < LOAD_GRACE_MS) return@withLock
        matchPosition(target, now)
        matchPause(target, local, now)
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
        const val DRIFT_MS = 2_000L
        const val CHECK_MS = 5_000L
        const val LOAD_GRACE_MS = 1_500L
        const val TRACK_RETRY_MS = 8_000L
        const val SEEK_COOLDOWN_MS = 3_000L
        const val PAUSE_COOLDOWN_MS = 2_000L
        const val SEEK_LEAD_MS = 300L
    }
}
