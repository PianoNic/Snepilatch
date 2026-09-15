package ch.snepilatch.app.logic.relay

import ch.snepilatch.app.logic.shared.LokiLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * The host's side of a relay jam. Publishes the playback whenever something guests follow changes,
 * when the position jumps (a seek), and every [HEARTBEAT_MS] so a guest that drifted catches up.
 * [apply] carries out what guests ask for; the relay has already checked they may.
 */
class RelayHost(
    private val scope: CoroutineScope,
    private val player: RelayHostPlayer,
    private val publish: (RelayPlayback) -> Unit,
    private val serverNow: () -> Long,
) {
    private var jobs: List<Job> = emptyList()

    @Volatile private var last: RelayPlayback? = null

    fun start() {
        if (jobs.isNotEmpty()) return
        jobs = listOf(
            scope.launch { player.state.distinctUntilChanged().collect { publishNow() } },
            scope.launch { player.positionMs.collect { position -> if (jumped(position)) publishNow() } },
            scope.launch {
                while (isActive) {
                    delay(HEARTBEAT_MS)
                    publishNow()
                }
            },
        )
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        last = null
    }

    fun publishNow() {
        val state = player.current()
        val playback = RelayPlayback(
            trackUri = state.trackUri,
            contextUri = state.contextUri,
            positionMs = player.positionMs.value,
            durationMs = state.durationMs,
            paused = state.paused,
            shuffle = state.shuffle,
            repeat = state.repeat,
            queue = state.queue.take(MAX_QUEUE),
            sampledAt = serverNow(),
        )
        last = playback
        publish(playback)
    }

    suspend fun apply(command: RelayCommand) {
        val current = player.current()
        when (command.kind) {
            RelayCommand.PAUSE -> if (!current.paused) player.pause()
            RelayCommand.RESUME -> if (current.paused) player.resume()
            RelayCommand.NEXT -> player.next()
            RelayCommand.PREVIOUS -> player.previous()
            RelayCommand.SEEK -> command.positionMs?.let { player.seek(it) }
            RelayCommand.PLAY -> command.uri?.let { player.play(it, command.contextUri) }
            RelayCommand.ADD_TO_QUEUE -> command.uri?.let { player.enqueue(it) }
            else -> LokiLogger.i(TAG, "Guest command ${command.kind} is not handled yet")
        }
    }

    private fun jumped(position: Long): Boolean {
        val previous = last ?: return false
        return abs(position - previous.positionAt(serverNow())) > SEEK_JUMP_MS
    }

    companion object {
        private const val TAG = "RelayHost"
        const val HEARTBEAT_MS = 20_000L
        const val SEEK_JUMP_MS = 2_500L
        const val MAX_QUEUE = 100
    }
}
