package ch.snepilatch.app.playback.engine

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotify.cdn.SpotifyPlayback
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `metadata/4/track` is track-only. Podcast episodes must NOT be run through it — an episode uri would
 * build a malformed gid lookup — they resolve their audio from the connect-state/track-playback state
 * machine instead. This pins that guard so the episode path never reaches the track metadata endpoint.
 */
class SpotifyCdnResolverEpisodeTest {

    @Test
    fun `episode uri returns null without a track metadata lookup`() = runBlocking {
        val playback = mockk<SpotifyPlayback>(relaxed = true)
        val resolver = SpotifyCdnResolver(mockk(relaxed = true), playback)

        val result = resolver.fetchFileIdFromMetadata("spotify:episode:1f6tXaeR1XNYwSF0tqpEDT")

        assertNull("episodes have no track metadata file id", result)
        verify(exactly = 0) { playback.trackIdToGid(any()) }
    }

    @Test
    fun `track uri still performs the metadata lookup`() = runBlocking {
        val playback = mockk<SpotifyPlayback>(relaxed = true)
        every { playback.trackIdToGid(any()) } returns "gid123"
        coEvery { playback.getTrackMetadata(any()) } returns mockk(relaxed = true)
        every { playback.findFile(any(), any()) } returns null
        val resolver = SpotifyCdnResolver(mockk(relaxed = true), playback)

        resolver.fetchFileIdFromMetadata("spotify:track:4uLU6hMCjMI75M1A2tKUQC")

        verify(exactly = 1) { playback.trackIdToGid("4uLU6hMCjMI75M1A2tKUQC") }
    }
}
