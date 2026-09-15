package ch.snepilatch.app.logic.relay

/**
 * The host's playback. [positionMs] is where playback was at [sampledAt], a time on the relay's
 * clock, so the position now is [positionAt] of the relay's current time.
 */
data class RelayPlayback(
    val trackUri: String?,
    val contextUri: String?,
    val positionMs: Long,
    val durationMs: Long,
    val paused: Boolean,
    val shuffle: Boolean,
    val repeat: String,
    val queue: List<RelayQueueEntry>,
    val sampledAt: Long? = null,
) {
    fun positionAt(serverNow: Long): Long {
        val sampled = sampledAt ?: return positionMs
        if (paused) return positionMs
        val position = positionMs + (serverNow - sampled).coerceAtLeast(0)
        return if (durationMs > 0) position.coerceAtMost(durationMs) else position
    }
}
