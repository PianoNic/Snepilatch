package ch.snepilatch.app.logic.relay

/** A jam as the relay describes it to one member; [isOwner] and each member's `isCurrentUser` are from that member's view. */
data class RelaySession(
    val sessionId: String,
    val joinToken: String,
    val ownerId: String,
    val isOwner: Boolean,
    val members: List<RelayMember>,
    val guestControl: String,
    val maxMemberCount: Int,
    val timestamp: Long,
)
