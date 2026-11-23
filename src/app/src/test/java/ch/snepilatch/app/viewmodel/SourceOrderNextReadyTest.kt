package ch.snepilatch.app.viewmodel

import ch.snepilatch.app.logic.playback.AudioSourceResolver
import ch.snepilatch.app.logic.shared.AppSettings
import ch.snepilatch.app.logic.shared.SessionHolder
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotify.api.playerconnect.PlayerConnect
import kotify.api.playerstatus.TrackChangeEvent
import kotify.cdn.StreamInfo
import kotify.cdn.StreamResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test

/** The next button spun for as long as a lossless track played: nothing looked the next track up. */
class SourceOrderNextReadyTest {

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
        AppSettings.sourceOrder.value = listOf(AppSettings.SOURCE_SPFY)
        AppSettings.preferredAudioSource.value = null
        rig.uninstall()
    }

    @Test
    fun aFileSourceThatPlays_looksUpTheNextTrack() = runBlocking {
        val player = mockk<PlayerConnect>(relaxed = true)
        SessionHolder.player = player
        AppSettings.sourceOrder.value = listOf("lossless")
        AppSettings.preferredAudioSource.value = "lossless"
        coEvery { AudioSourceResolver.fromTrack(any(), any(), "lossless") } returns StreamResult.Success(
            StreamInfo(url = "https://cdn/track.flac", provider = "qobuz", mimeType = "audio/flac")
        )

        rig.vm.playInOrder(TrackChangeEvent(null, null, null), "spotify:track:t", "Title", "Artist", null, 0L)

        // preResolveNextTrack starts by asking for the queue.
        coVerify { player.getState() }
    }
}
