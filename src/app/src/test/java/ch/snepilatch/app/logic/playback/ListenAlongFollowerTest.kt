package ch.snepilatch.app.logic.playback

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** Issue #1026: how the listen-along engine keeps this phone's YouTube Music audio on a Spfy jam. */
class ListenAlongFollowerTest {

    private class FakePlayer : ListenAlongPlayer {
        val calls = mutableListOf<String>()
        var playing = false
        var position = 0L
        override fun load(stream: YouTubeMusicSource.Stream, at: JamPosition, positionMs: Long, play: Boolean) {
            calls += "load ${stream.url} @$positionMs play=$play"
            playing = play
            position = positionMs
        }
        override fun play(positionMs: Long) {
            calls += "play @$positionMs"
            playing = true
            position = positionMs
        }
        override fun pause() {
            calls += "pause"
            playing = false
        }
        override fun positionMs() = position
        override fun isPlaying() = playing
        override fun stop() { calls += "stop" }
    }

    private var clock = 1_000_000L
    private var lookups = 0
    private val player = FakePlayer()

    private fun follower(match: Boolean = true) = ListenAlongFollower(
        player,
        resolve = { jam ->
            lookups++
            if (match) YouTubeMusicSource.Stream("yt:${jam.trackUri}", null, emptyMap()) else null
        },
        now = { clock },
    )

    private fun jam(uri: String? = "spotify:track:a", position: Long = 10_000, paused: Boolean = false) =
        JamPosition(uri, "Title", "Artist", null, 200_000, position, paused)

    @Test
    fun newTrack_loadsWhereTheJamIs() = runBlocking {
        follower().reconcile { jam() }
        assertEquals(listOf("load yt:spotify:track:a @${10_000 + ListenAlongFollower.LOAD_LEAD_MS} play=true"), player.calls)
    }

    @Test
    fun drift_isSoughtAwayAfterTheLoadGrace_butNotWithinIt() = runBlocking {
        val f = follower()
        f.reconcile { jam(position = 10_000) }
        player.calls.clear()
        f.reconcile { jam(position = 20_000) }
        assertEquals("nothing within the load grace", emptyList<String>(), player.calls)
        clock += ListenAlongFollower.LOAD_GRACE_MS
        f.reconcile { jam(position = 20_000) }
        assertEquals(listOf("play @${20_000 + ListenAlongFollower.SEEK_LEAD_MS}"), player.calls)
    }

    @Test
    fun resync_isNotRepeatedWhileItRebuffers() = runBlocking {
        val f = follower()
        f.reconcile { jam(position = 10_000) }
        clock += ListenAlongFollower.LOAD_GRACE_MS
        player.calls.clear()
        f.reconcile { jam(position = 20_000) }
        player.position = 20_000 + ListenAlongFollower.SEEK_LEAD_MS - 2_000 // stalled while it buffers
        clock += 2_000
        f.reconcile { jam(position = 22_000) }
        assertEquals(listOf("play @${20_000 + ListenAlongFollower.SEEK_LEAD_MS}"), player.calls)
    }

    @Test
    fun trackWithoutItsNameYet_waitsForIt() = runBlocking {
        val f = follower()
        f.reconcile { jam().copy(title = "") }
        assertEquals(emptyList<String>(), player.calls)
        f.reconcile { jam() }
        assertEquals(1, player.calls.size)
    }

    @Test
    fun pausedJam_pausesThisPhone() = runBlocking {
        val f = follower()
        f.reconcile { jam() }
        clock += ListenAlongFollower.LOAD_GRACE_MS
        player.calls.clear()
        f.reconcile { jam(paused = true) }
        assertEquals(listOf("pause"), player.calls)
    }

    @Test
    fun noMatch_staysSilent_andIsNotLookedUpAgain() = runBlocking {
        val f = follower(match = false)
        f.reconcile { jam() }
        f.reconcile { jam() }
        assertEquals(1, lookups)
        assertEquals(emptyList<String>(), player.calls)
    }

    @Test
    fun jamMovedOnDuringTheLookup_loadsNothing() = runBlocking {
        var asked = 0
        follower().reconcile { if (asked++ == 0) jam(uri = "spotify:track:a") else jam(uri = "spotify:track:b") }
        assertEquals(emptyList<String>(), player.calls)
    }

    @Test
    fun failedStream_isLoadedAgain_thenGivenUpOn() = runBlocking {
        val f = follower()
        f.reconcile { jam() }
        f.failed()
        f.reconcile { jam() }
        assertEquals("loaded again after the first failure", 2, lookups)
        f.failed()
        f.reconcile { jam() }
        assertEquals("given up after the second", 2, lookups)
    }

    @Test
    fun prefetchedNextTrack_playsWithoutAnotherLookup() = runBlocking {
        val f = follower()
        f.reconcile { jam(uri = "spotify:track:a") }
        f.prefetch(jam(uri = "spotify:track:b"))
        assertEquals(2, lookups)
        f.reconcile { jam(uri = "spotify:track:b", position = 0) }
        assertEquals("the change used the prefetched stream", 2, lookups)
        assertEquals("load yt:spotify:track:b @${ListenAlongFollower.LOAD_LEAD_MS} play=true", player.calls.last())
    }
}
