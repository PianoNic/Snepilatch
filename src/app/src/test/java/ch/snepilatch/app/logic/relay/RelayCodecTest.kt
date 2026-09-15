package ch.snepilatch.app.logic.relay

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Frames as Snepirelay v0.1.0 sent them, captured from the live relay on 2026-09-15. */
class RelayCodecTest {

    @Test
    fun aWelcome_withoutAJam() {
        val event = RelayCodec.parse(
            """{"type":"welcome","protocol":1,"memberId":"OarYmyoTA7wxOfxD","serverTime":1789504824619,"session":null,"playback":null,"rid":null}""",
        ) as RelayEvent.Welcome
        assertEquals("OarYmyoTA7wxOfxD", event.memberId)
        assertEquals(1789504824619, event.serverTime)
        assertNull(event.session)
        assertNull(event.playback)
    }

    @Test
    fun aSessionUpdate_carriesTheJamAndItsMembers() {
        val event = RelayCodec.parse(
            """{"type":"session_update","reason":"YOU_JOINED","session":{"sessionId":"kw7XOo4RGKnQOVzr","joinToken":"0urhfUvjCGir",
               "ownerId":"OarYmyoTA7wxOfxD","isOwner":true,"members":[{"id":"OarYmyoTA7wxOfxD","userId":null,"displayName":"smoke",
               "imageUrl":null,"device":{"deviceId":"d","name":"PC","type":"Computer"},"isHost":true,"isCurrentUser":true,"listening":true,
               "joinedAt":1789504824627}],"guestControl":"full","maxMemberCount":32,"timestamp":1789504824627},
               "memberIds":["OarYmyoTA7wxOfxD"],"rid":null}""",
        ) as RelayEvent.SessionUpdate
        assertEquals("YOU_JOINED", event.reason)
        val session = event.session!!
        assertEquals("0urhfUvjCGir", session.joinToken)
        assertTrue(session.isOwner)
        assertEquals(RelayCodec.GUEST_CONTROL_FULL, session.guestControl)
        val host = session.members.single()
        assertTrue(host.isHost && host.isCurrentUser && host.listening)
        assertNull(host.userId)
        assertEquals(listOf("OarYmyoTA7wxOfxD"), event.memberIds)
    }

    @Test
    fun aSessionUpdate_forADeviceNoLongerInTheJam() {
        val event = RelayCodec.parse("""{"type":"session_update","reason":"SESSION_DELETED","session":null,"memberIds":["h"],"rid":null}""")
        assertNull((event as RelayEvent.SessionUpdate).session)
    }

    @Test
    fun playback_roundTripsThroughTheWriterAndTheReader() {
        val state = RelayPlayback(
            trackUri = "track:one",
            contextUri = null,
            positionMs = 1000,
            durationMs = 200_000,
            paused = false,
            shuffle = true,
            repeat = "context",
            queue = listOf(RelayQueueEntry("track:two", "q1", null)),
            sampledAt = 5,
        )
        val written = RelayCodec.playback(state)
        val echoed = """{"type":"playback","state":${written["state"]},"serverTime":9,"rid":null}"""
        assertEquals(state, (RelayCodec.parse(echoed) as RelayEvent.Playback).state)
    }

    @Test
    fun aCommand_andAnError_andAnOk() {
        val command = RelayCodec.parse("""{"type":"command","from":"g","command":{"kind":"seek","positionMs":42000},"rid":null}""") as RelayEvent.Command
        assertEquals(RelayCommand(RelayCommand.SEEK, positionMs = 42_000), command.command)
        val error = RelayCodec.parse("""{"type":"error","error":"SESSION_FULL","values":{"maxMemberCount":"32"},"rid":"r3"}""") as RelayEvent.Error
        assertEquals("SESSION_FULL", error.code)
        assertEquals("32", error.values["maxMemberCount"])
        assertEquals("r3", error.rid)
        assertEquals(RelayEvent.Ok("r4"), RelayCodec.parse("""{"type":"ok","rid":"r4"}"""))
    }

    @Test
    fun unknownOrBrokenFrames_areIgnored() {
        assertNull(RelayCodec.parse("""{"type":"something_new","rid":null}"""))
        assertNull(RelayCodec.parse("not json"))
        assertNull(RelayCodec.parse("""{"type":"welcome"}"""))
    }

    @Test
    fun theHello_andACommand_areWhatTheRelayReads() {
        val hello = RelayCodec.hello(
            "install-1", "s".repeat(64),
            RelayCodec.Profile("user", "Nic", null),
            RelayCodec.Device("device-1", "S25 Ultra", "Smartphone"),
        )
        assertEquals("hello", hello["type"]!!.jsonPrimitive.content)
        assertEquals(1, hello["protocol"]!!.jsonPrimitive.content.toInt())
        assertEquals("Nic", hello["profile"]!!.jsonObject["displayName"]!!.jsonPrimitive.content)

        val written = RelayCodec.withRid(RelayCodec.command(RelayCommand(RelayCommand.ADD_TO_QUEUE, uri = "track:x")), "r1")
        val command = Json.parseToJsonElement(written.toString()).jsonObject
        assertEquals("r1", command["rid"]!!.jsonPrimitive.content)
        val body = command["command"]!!.jsonObject
        assertEquals("addToQueue", body["kind"]!!.jsonPrimitive.content)
        assertFalse(body.containsKey("positionMs"))
    }

    @Test
    fun thePositionMovesOnWhilePlayingAndStopsWhilePaused() {
        val playing = RelayPlayback(
            trackUri = "track:one",
            contextUri = null,
            positionMs = 10_000,
            durationMs = 60_000,
            paused = false,
            shuffle = false,
            repeat = "off",
            queue = emptyList(),
            sampledAt = 1_000,
        )
        assertEquals(12_500, playing.positionAt(serverNow = 3_500))
        assertEquals(60_000, playing.positionAt(serverNow = 999_999))
        assertEquals(10_000, playing.copy(paused = true).positionAt(serverNow = 3_500))
        assertEquals(10_000, playing.positionAt(serverNow = 500))
    }
}
