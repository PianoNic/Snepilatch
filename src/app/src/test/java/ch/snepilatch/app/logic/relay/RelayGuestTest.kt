package ch.snepilatch.app.logic.relay

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RelayGuestTest {

    private class FakePlayer : RelayHostPlayer {
        val flow = MutableStateFlow(
            RelayHostPlayer.State("track:mine", null, 180_000, paused = false, shuffle = false, repeat = "off", queue = emptyList()),
        )
        override val state = flow
        override val positionMs = MutableStateFlow(0L)
        val calls = mutableListOf<String>()
        var applyingDuringCalls = mutableListOf<Boolean>()
        var applying = false

        override fun current() = flow.value
        override suspend fun pause() = record("pause") { flow.value = flow.value.copy(paused = true) }
        override suspend fun resume() = record("resume") { flow.value = flow.value.copy(paused = false) }
        override suspend fun next() = record("next") {}
        override suspend fun previous() = record("previous") {}
        override suspend fun seek(positionMs: Long) = record("seek:$positionMs") { this.positionMs.value = positionMs }
        override suspend fun play(uri: String, contextUri: String?) = record("play:$uri:$contextUri") {
            flow.value = flow.value.copy(trackUri = uri, contextUri = contextUri)
            positionMs.value = 0
        }
        override suspend fun enqueue(uri: String) = record("enqueue:$uri") {}

        private fun record(call: String, effect: () -> Unit) {
            calls += call
            applyingDuringCalls += applying
            effect()
        }
    }

    private fun hostPlayback(track: String, position: Long, paused: Boolean, at: Long) =
        RelayPlayback(track, "playlist:host", position, 200_000, paused, shuffle = false, repeat = "off", queue = emptyList(), sampledAt = at)

    private fun TestScope.guest(player: FakePlayer) =
        RelayGuest(backgroundScope, player, { currentTime }) { player.applying = it }.also { it.start() }

    @Test
    fun aDifferentTrack_isPlayedInTheHostsContext_thenSoughtToWhereTheHostIs() = runTest {
        val player = FakePlayer()
        val guest = guest(player)
        guest.follow(hostPlayback("track:host", position = 30_000, paused = false, at = currentTime))
        runCurrent()
        assertEquals(listOf("play:track:host:playlist:host"), player.calls)

        advanceTimeBy(RelayGuest.LOAD_GRACE_MS + 100)
        guest.reconcile()
        val seek = player.calls.last()
        assertTrue(seek, seek.startsWith("seek:"))
        val target = seek.removePrefix("seek:").toLong()
        assertTrue("seek target $target", target in 31_500L..32_500L)
        assertTrue(player.applyingDuringCalls.all { it })
    }

    @Test
    fun aSmallDrift_isLeftAlone_andThePauseIsMatched() = runTest {
        val player = FakePlayer()
        player.flow.value = player.flow.value.copy(trackUri = "track:host")
        player.positionMs.value = 10_000
        val guest = guest(player)
        guest.follow(hostPlayback("track:host", position = 11_000, paused = true, at = currentTime))
        runCurrent()
        assertEquals(listOf("pause"), player.calls)

        advanceTimeBy(RelayGuest.PAUSE_COOLDOWN_MS + 100)
        guest.follow(hostPlayback("track:host", position = 11_000, paused = false, at = currentTime))
        runCurrent()
        assertEquals(listOf("pause", "resume"), player.calls)
    }

    @Test
    fun aTrackCommandStillLoading_isNotSentAgain() = runTest {
        val player = FakePlayer()
        val guest = guest(player)
        guest.follow(hostPlayback("track:host", position = 0, paused = false, at = currentTime))
        runCurrent()
        player.flow.value = player.flow.value.copy(trackUri = "track:mine")
        guest.reconcile()
        guest.reconcile()
        assertEquals(1, player.calls.count { it.startsWith("play:") })
    }

    @Test
    fun joinTokens_comeFromRelayLinksAndBareTokens_notFromOtherLinks() {
        assertEquals("8XnjKHFOglRc", RelayJamMapper.joinToken(" https://snepirelay.pianonic.ch/jam/8XnjKHFOglRc "))
        assertEquals("8XnjKHFOglRc", RelayJamMapper.joinToken("http://192.168.1.20:8080/jam/8XnjKHFOglRc/?x=1"))
        assertEquals("8XnjKHFOglRc", RelayJamMapper.joinToken("8XnjKHFOglRc"))
        assertNull(RelayJamMapper.joinToken("https://open.spotify.com/socialsession/abcdefghijkl1234"))
        assertNull(RelayJamMapper.joinToken("https://spotify.link/rGvHYkuNo6b"))
    }
}
