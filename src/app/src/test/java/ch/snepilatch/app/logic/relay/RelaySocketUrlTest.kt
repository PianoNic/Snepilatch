package ch.snepilatch.app.logic.relay

import org.junit.Assert.assertEquals
import org.junit.Test

class RelaySocketUrlTest {

    @Test
    fun aServerAddress_becomesItsWebSocket() {
        assertEquals("wss://snepirelay.pianonic.ch/api/relay", RelayClient.socketUrl("https://snepirelay.pianonic.ch"))
        assertEquals("wss://snepirelay.pianonic.ch/api/relay", RelayClient.socketUrl(" https://snepirelay.pianonic.ch/ "))
        assertEquals("ws://192.168.1.20:8080/api/relay", RelayClient.socketUrl("http://192.168.1.20:8080"))
        assertEquals("wss://relay.example.com/api/relay", RelayClient.socketUrl("relay.example.com"))
        assertEquals("ws://localhost:5180/api/relay", RelayClient.socketUrl("ws://localhost:5180"))
    }
}
