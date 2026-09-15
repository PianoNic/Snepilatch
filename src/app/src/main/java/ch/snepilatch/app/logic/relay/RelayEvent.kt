package ch.snepilatch.app.logic.relay

/** What the relay sends. [rid] is set on the answer to a request that carried one. */
sealed interface RelayEvent {
    val rid: String?

    data class Welcome(
        val memberId: String,
        val serverTime: Long,
        val session: RelaySession?,
        val playback: RelayPlayback?,
        override val rid: String?,
    ) : RelayEvent

    data class Pong(val clientTime: Long, val serverTime: Long, override val rid: String?) : RelayEvent

    /** [session] is null once this device is no longer in the jam. */
    data class SessionUpdate(val reason: String, val session: RelaySession?, val memberIds: List<String>, override val rid: String?) : RelayEvent

    data class Playback(val state: RelayPlayback, val serverTime: Long, override val rid: String?) : RelayEvent

    data class Command(val from: String, val command: RelayCommand, override val rid: String?) : RelayEvent

    data class Ok(override val rid: String?) : RelayEvent

    data class Error(val code: String, val values: Map<String, String>, override val rid: String?) : RelayEvent
}
