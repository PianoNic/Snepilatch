package ch.snepilatch.app.viewmodel

import ch.snepilatch.app.data.TrackInfo
import ch.snepilatch.app.playback.AudioSourceResolver
import ch.snepilatch.app.playback.SessionHolder
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotify.api.playerconnect.PlayerConnect
import kotify.cdn.StreamInfo
import kotify.cdn.StreamResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Tapping a track starts an optimistic play a full second before the Connect echo reaches
 * resolveAndPlay. That path used to go straight to the Spfy CDN, so a downloaded track streamed
 * anyway and the echo's download check arrived too late to matter.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InstantTapDownloadedTest {

    private val rig = PlaybackTestRig()

    @Before
    fun setUp() {
        rig.install()
        mockkObject(AudioSourceResolver)
    }

    @After
    fun tearDown() {
        unmockkObject(AudioSourceResolver)
        SessionHolder.player = null
        SessionHolder.cdnResolver = null
        AppSettings.preferredAudioSource.value = null
        rig.uninstall()
    }

    @Test
    fun tappingADownloadedTrackPlaysTheLocalFile() {
        SessionHolder.player = mockk<PlayerConnect>(relaxed = true)
        AppSettings.preferredAudioSource.value = null // Spfy CDN, the source that instant-taps.
        every { AudioSourceResolver.localOrNull(any(), any(), any()) } returns StreamResult.Success(
            StreamInfo(url = "content://tree/Music/fatal.opus", provider = "Local", mimeType = "audio/ogg")
        )

        runBlocking {
            rig.vm.startUserPlayback(
                TrackInfo(uri = "spotify:track:dl", name = "Fatal", artist = "GEMN", albumArt = null),
                contextUri = null,
            )
        }

        assertEquals("Local", rig.vm.streamProvider.value)
    }

    @Test
    fun tappingAnUndownloadedTrackDoesNotClaimLocalPlayback() {
        SessionHolder.player = mockk<PlayerConnect>(relaxed = true)
        AppSettings.preferredAudioSource.value = null
        every { AudioSourceResolver.localOrNull(any(), any(), any()) } returns null

        runBlocking {
            rig.vm.startUserPlayback(
                TrackInfo(uri = "spotify:track:none", name = "Featherfall", artist = "Hyper Potions", albumArt = null),
                contextUri = null,
            )
        }

        assertEquals(null, rig.vm.streamProvider.value)
    }
}
