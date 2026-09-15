package ch.snepilatch.app.logic.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayJamMapperTest {

    private fun session(isOwner: Boolean, guestControl: String) = RelaySession(
        sessionId = "s1",
        joinToken = "0urhfUvjCGir",
        ownerId = "host",
        isOwner = isOwner,
        members = listOf(
            RelayMember("host", "user-host", "Host", "https://img/host", isHost = true, isCurrentUser = isOwner, listening = true, joinedAt = 1),
            RelayMember("guest", null, "Guest", null, isHost = false, isCurrentUser = !isOwner, listening = false, joinedAt = 2),
        ),
        guestControl = guestControl,
        maxMemberCount = 32,
        timestamp = 9,
    )

    @Test
    fun aHostedJam_isTheOwnersAndMarkedAsRelay() {
        val jam = RelayJamMapper.toJamSession(session(isOwner = true, guestControl = RelayCodec.GUEST_CONTROL_NONE))
        assertTrue(RelayJamMapper.isRelay(jam))
        assertTrue(jam.isSessionOwner)
        assertTrue(jam.isControlling)
        assertEquals("0urhfUvjCGir", jam.joinSessionToken)
        assertEquals("host", jam.sessionOwnerId)
        assertTrue(jam.capabilities.removeParticipants)
        assertEquals("Host", jam.host?.displayName)
    }

    @Test
    fun aGuest_controlsOnlyWhenTheHostAllowsIt() {
        assertTrue(RelayJamMapper.toJamSession(session(isOwner = false, guestControl = RelayCodec.GUEST_CONTROL_FULL)).isControlling)
        val queueOnly = RelayJamMapper.toJamSession(session(isOwner = false, guestControl = RelayCodec.GUEST_CONTROL_QUEUE_ONLY))
        assertFalse(queueOnly.isControlling)
        assertTrue(queueOnly.queueOnlyMode)
        assertFalse(queueOnly.capabilities.removeParticipants)
    }

    @Test
    fun members_keepWhoIsListeningAndFallBackToTheirIdAsUsername() {
        val guest = RelayJamMapper.toJamSession(session(isOwner = false, guestControl = RelayCodec.GUEST_CONTROL_FULL)).currentUser!!
        assertEquals("guest", guest.username)
        assertFalse(guest.isListening)
    }

    @Test
    fun theInviteLink_isTheRelayAddressWithTheToken() {
        assertEquals("https://snepirelay.pianonic.ch/jam/abc", RelayJamMapper.inviteLink("https://snepirelay.pianonic.ch/", "abc"))
    }
}
