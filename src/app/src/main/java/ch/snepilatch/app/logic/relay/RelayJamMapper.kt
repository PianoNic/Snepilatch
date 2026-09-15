package ch.snepilatch.app.logic.relay

import kotify.api.jam.JamCapabilities
import kotify.api.jam.JamMember
import kotify.api.jam.JamSession

/**
 * A relay jam in the shape the jam screens already draw. The relay has no separate share token, so
 * the join token stands in for both; guests control playback unless the host limited them.
 */
object RelayJamMapper {

    const val SESSION_TYPE = "relay"

    fun toJamSession(session: RelaySession): JamSession {
        val guestsControl = session.guestControl == RelayCodec.GUEST_CONTROL_FULL
        return JamSession(
            sessionId = session.sessionId,
            joinSessionToken = session.joinToken,
            joinSessionUri = "",
            sessionOwnerId = session.ownerId,
            members = session.members.map { member(it, guestsControl) },
            isSessionOwner = session.isOwner,
            isListening = true,
            isControlling = session.isOwner || guestsControl,
            active = true,
            queueOnlyMode = session.guestControl == RelayCodec.GUEST_CONTROL_QUEUE_ONLY,
            isPaused = false,
            hostActiveDeviceId = null,
            initialSessionType = SESSION_TYPE,
            hostDevice = null,
            timestamp = session.timestamp,
            redirectCommands = false,
            maxMemberCount = session.maxMemberCount,
            capabilities = JamCapabilities(
                invite = true,
                removeParticipants = session.isOwner,
                manageParticipantSettings = session.isOwner,
            ),
        )
    }

    /** The link an invite shares: the relay's own address with the join token. */
    fun inviteLink(serverUrl: String, joinToken: String): String = "${serverUrl.trimEnd('/')}/jam/$joinToken"

    fun isRelay(session: JamSession?): Boolean = session?.initialSessionType == SESSION_TYPE

    private fun member(member: RelayMember, guestsControl: Boolean) = JamMember(
        id = member.id,
        username = member.userId ?: member.id,
        displayName = member.displayName,
        imageUrl = member.imageUrl,
        isListening = member.listening,
        isControlling = member.isHost || guestsControl,
        isCurrentUser = member.isCurrentUser,
        joinedTimestamp = member.joinedAt,
        largeImageUrl = member.imageUrl,
    )
}
