package ch.snepilatch.app.logic.relay

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RelayHostTest {

    private class FakePlayer : RelayHostPlayer {
        val flow = MutableStateFlow(
            RelayHostPlayer.State("track:one", "playlist:a", 200_000, paused = false, shuffle = false, repeat = "off", queue = emptyList()),
        )
        override val state = flow
        override val positionMs = MutableStateFlow(10_000L)
        val calls = mutableListOf<String>()

        override fun current() = flow.value
        override suspend fun pause() { calls += "pause" }
        override suspend fun resume() { calls += "resume" }
        override suspend fun next() { calls += "next" }
        override suspend fun previous() { calls += "previous" }
        override suspend fun seek(positionMs: Long) { calls += "seek:$positionMs" }
        override suspend fun play(uri: String, contextUri: String?) { calls += "play:$uri:$contextUri" }
        override suspend fun enqueue(uri: String) { calls += "enqueue:$uri" }
    }

    private fun TestScope.host(player: FakePlayer, published: MutableList<RelayPlayback>) =
        RelayHost(backgroundScope, player, { published += it }, { currentTime })

    @Test
    fun aChangeGuestsFollow_isPublishedOnceWithTheServerTime() = runTest {
        val player = FakePlayer()
        val published = mutableListOf<RelayPlayback>()
        host(player, published).start()
        runCurrent()
        assertEquals(1, published.size)
        assertEquals("track:one", published.last().trackUri)
        assertEquals("playlist:a", published.last().contextUri)

        player.flow.value = player.flow.value.copy(trackUri = "track:two")
        runCurrent()
        assertEquals(2, published.size)
        assertEquals("track:two", published.last().trackUri)
        assertEquals(currentTime, published.last().sampledAt)
    }

    @Test
    fun theNormalTick_isNotPublished_butASeekIs() = runTest {
        val player = FakePlayer()
        val published = mutableListOf<RelayPlayback>()
        host(player, published).start()
        runCurrent()
        advanceTimeBy(1_000)
        player.positionMs.value = 11_000
        runCurrent()
        assertEquals(1, published.size)

        player.positionMs.value = 90_000
        runCurrent()
        assertEquals(2, published.size)
        assertEquals(90_000, published.last().positionMs)
    }

    @Test
    fun aHeartbeat_goesOutEvenWhenNothingChanged() = runTest {
        val player = FakePlayer()
        val published = mutableListOf<RelayPlayback>()
        host(player, published).start()
        runCurrent()
        advanceTimeBy(RelayHost.HEARTBEAT_MS + 1)
        assertTrue(published.size >= 2)
    }

    @Test
    fun guestCommands_driveThePlayerOnlyWhenTheyChangeSomething() = runTest {
        val player = FakePlayer()
        val host = host(player, mutableListOf())
        host.apply(RelayCommand(RelayCommand.RESUME))
        host.apply(RelayCommand(RelayCommand.PAUSE))
        player.flow.value = player.flow.value.copy(paused = true)
        host.apply(RelayCommand(RelayCommand.PAUSE))
        host.apply(RelayCommand(RelayCommand.RESUME))
        host.apply(RelayCommand(RelayCommand.SEEK, positionMs = 42_000))
        host.apply(RelayCommand(RelayCommand.ADD_TO_QUEUE, uri = "track:wish"))
        host.apply(RelayCommand(RelayCommand.PLAY, uri = "track:x", contextUri = "album:y"))
        host.apply(RelayCommand(RelayCommand.NEXT))
        assertEquals(listOf("pause", "resume", "seek:42000", "enqueue:track:wish", "play:track:x:album:y", "next"), player.calls)
    }
}
