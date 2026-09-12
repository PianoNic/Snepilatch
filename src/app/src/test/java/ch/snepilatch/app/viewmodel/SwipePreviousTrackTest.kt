package ch.snepilatch.app.viewmodel

import ch.snepilatch.app.data.TrackInfo
import io.mockk.coVerify
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class SwipePreviousTrackTest {
    private val rig = PlaybackTestRig()

    @Before
    fun setUp() = rig.install()

    @After
    fun tearDown() = rig.uninstall()

    @Test
    fun forcedPreviousChangesTrackAfterRestartThreshold() {
        rig.seedStreaming(positionMs = 30_000L)
        rig.vm.prevTrackPreview.value = TrackInfo(
            uri = "spotify:track:previous",
            name = "Previous",
            artist = "Artist",
            albumArt = null,
        )

        rig.vm.skipPrevious(forceTrackChange = true)

        assertEquals("spotify:track:previous", rig.vm.playback.value.track?.uri)
        verify(exactly = 0) { rig.service.syncSeek(0L) }
        coVerify(timeout = 1_000, exactly = 1) { rig.player.localPreviousTrack() }
    }
}
