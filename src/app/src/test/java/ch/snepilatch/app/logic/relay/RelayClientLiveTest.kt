package ch.snepilatch.app.logic.relay

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID

/**
 * A host and a guest through the real relay. Needs the network, so it only runs with RELAY_LIVE=1;
 * RELAY_URL points it at another relay.
 */
class RelayClientLiveTest {

    private fun client(scope: CoroutineScope, name: String): RelayClient {
        val identity = RelayIdentity(UUID.randomUUID().toString(), UUID.randomUUID().toString() + UUID.randomUUID())
        val url = RelayClient.socketUrl(System.getenv("RELAY_URL") ?: RelaySettings.DEFAULT_URL)
        val profile = RelayCodec.Profile(null, "live-$name", null)
        val device = RelayCodec.Device("d-$name", "test", "Computer")
        return RelayClient(url, { RelayCodec.hello(identity.installId, identity.secret, profile, device) }, scope)
    }

    private inline fun <reified T : RelayEvent> RelayClient.next(scope: CoroutineScope) =
        scope.async(start = CoroutineStart.UNDISPATCHED) { events.filterIsInstance<T>().first() }

    @Test
    fun aHostAndAGuest_shareAJamThroughTheRelay() = runBlocking {
        assumeTrue(System.getenv("RELAY_LIVE") == "1")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val host = client(scope, "host")
        val guest = client(scope, "guest")
        try {
            withTimeout(20_000) {
                host.start()
                guest.start()
                host.state.first { it is RelayClient.State.Connected }
                guest.state.first { it is RelayClient.State.Connected }

                val created = host.next<RelayEvent.SessionUpdate>(this)
                assertTrue(host.request(RelayCodec.create()) is RelayEvent.Ok)
                val token = created.await().session!!.joinToken

                val guestJoined = guest.next<RelayEvent.SessionUpdate>(this)
                assertTrue(guest.request(RelayCodec.join(token)) is RelayEvent.Ok)
                assertEquals(2, guestJoined.await().session!!.members.size)

                val playback = guest.next<RelayEvent.Playback>(this)
                host.send(RelayCodec.playback(RelayPlayback("track:one", null, 1_000, 200_000, false, false, "off", emptyList())))
                assertEquals("track:one", playback.await().state.trackUri)

                val forwarded = host.next<RelayEvent.Command>(this)
                guest.send(RelayCodec.command(RelayCommand(RelayCommand.PAUSE)))
                assertEquals(RelayCommand.PAUSE, forwarded.await().command.kind)

                assertTrue(host.request(RelayCodec.end()) is RelayEvent.Ok)
            }
        } finally {
            host.stop()
            guest.stop()
            scope.cancel()
        }
    }
}
