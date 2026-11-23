package ch.snepilatch.app.viewmodel

import ch.snepilatch.app.data.TrackInfo
import ch.snepilatch.app.logic.playback.AudioSourceResolver
import ch.snepilatch.app.logic.playback.engine.SpfyCdnResolver
import ch.snepilatch.app.logic.shared.AppSettings
import ch.snepilatch.app.logic.shared.SessionHolder
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotify.api.playerstatus.PlayerStateData
import kotify.api.playerstatus.QueueTrack
import kotify.cdn.StreamInfo
import kotify.cdn.StreamResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #1142: a next track no source has left the next button spinning for the rest of the track, though
 * nothing was loading any more.
 */
class NextMissNotLoadingTest {

    private val rig = PlaybackTestRig()

    @Before
    fun setUp() {
        rig.install()
        mockkObject(AudioSourceResolver)
    }

    @After
    fun tearDown() {
        unmockkObject(AudioSourceResolver)
        SessionHolder.cdnResolver = null
        AppSettings.preferredAudioSource.value = null
        rig.uninstall()
    }

    @Test
    fun aNextTrackNoSourceHas_stillEndsTheLoading() = runBlocking {
        AppSettings.preferredAudioSource.value = "lossless"
        SessionHolder.cdnResolver = mockk<SpfyCdnResolver>(relaxed = true)
        val next = QueueTrack(
            uri = "spotify:track:next", uid = null, name = "Next", artistName = "Artist", artistUri = null,
            albumName = null, albumUri = null, durationMs = 200_000, imageUrl = null,
        )
        coEvery { rig.player.getState() } returns PlayerStateData(
            is_playing = true, is_paused = false, track = null, position_as_of_timestamp = 0, play_origin = null,
            is_active_device = true, has_active_device = true, active_device_id = "us", duration = 250_000,
            next_tracks = listOf(next),
        )
        every { AudioSourceResolver.localOrNull(any(), any(), any()) } returns null
        coEvery { AudioSourceResolver.byQuery(any(), any(), any(), any(), any(), any()) } returns
            StreamResult.Success(StreamInfo(url = "https://cdn/track.flac", provider = "qobuz", mimeType = "audio/flac"))
        coEvery { AudioSourceResolver.byQuery("spotify:track:next", any(), any(), any(), any(), any()) } returns
            StreamResult.Failure("No playable stream found (Qobuz, Tidal, Deezer)")
        rig.vm.isNextReady.value = false

        rig.vm.resumeAt("file", TrackInfo(uri = "spotify:track:now", name = "Now", artist = "Artist", albumArt = null), 0L, "HandBack")

        assertTrue(rig.vm.isNextReady.value)
    }
}
