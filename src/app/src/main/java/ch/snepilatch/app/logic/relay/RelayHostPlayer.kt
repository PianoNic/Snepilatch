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

    // A guest's side: making Spfy's own advance land on the host's next track.

    /** This account's own next track, the one Spfy's natural advance will play. */
    val nextUri: String? get() = null

    /** Whether Kotify holds at a track end instead of advancing to [nextUri]. */
    var holdAtTrackEnd: Boolean
        get() = false
        set(_) = Unit

    /** Put [uri] at the front of this account's queue, taking out [replacing] put there before. */
    suspend fun playNext(uri: String, replacing: String?) = Unit
}
