package ch.snepilatch.app.logic.relay

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * What hosting a jam needs from the player: the state to publish and the controls guests may use.
 * [state] leaves the position out so it only moves on a real change; [positionMs] is the position.
 */
interface RelayHostPlayer {

    data class State(
        val trackUri: String?,
        val contextUri: String?,
        val durationMs: Long,
        val paused: Boolean,
        val shuffle: Boolean,
        val repeat: String,
        val queue: List<RelayQueueEntry>,
    )

    val state: Flow<State>
    val positionMs: StateFlow<Long>

    fun current(): State

    suspend fun pause()
    suspend fun resume()
    suspend fun next()
    suspend fun previous()
    suspend fun seek(positionMs: Long)
    suspend fun play(uri: String, contextUri: String?)
    suspend fun enqueue(uri: String)
}
