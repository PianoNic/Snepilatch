package ch.snepilatch.app.logic.relay

/** [listening] is false while the member's connection is down and it may still come back. */
data class RelayMember(
    val id: String,
    val userId: String?,
    val displayName: String,
    val imageUrl: String?,
    val isHost: Boolean,
    val isCurrentUser: Boolean,
    val listening: Boolean,
    val joinedAt: Long,
)
