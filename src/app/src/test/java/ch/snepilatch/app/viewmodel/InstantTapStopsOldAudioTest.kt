package ch.snepilatch.app.viewmodel

import ch.snepilatch.app.data.TrackInfo
import ch.snepilatch.app.logic.playback.AudioSourceResolver
import ch.snepilatch.app.logic.shared.AppSettings
import ch.snepilatch.app.logic.shared.SessionHolder
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A tap on a streamed track goes quiet at once (#845): the outgoing audio stops while the echo,
 * resolve and licence run, instead of playing on under the new title. A re-tap of the track that is
 * already streaming must not stop it, because its echo short-circuits and would never reload.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InstantTapStopsOldAudioTest {

    private val rig = PlaybackTestRig()

    @Before
    fun setUp() {
        rig.install()
        mockkObject(AudioSourceResolver)
        every { AudioSourceResolver.localOrNull(any(), any(), any()) } returns null
        AppSettings.preferredAudioSource.value = null
        rig.seedStreaming()
        rig.vm.currentStreamUri = "spotify:track:test"
    }

    @After
    fun tearDown() {
        unmockkObject(AudioSourceResolver)
        SessionHolder.cdnResolver = null
        rig.uninstall()
    }

    private fun tap(uri: String) = runBlocking {
        rig.vm.startUserPlayback(TrackInfo(uri = uri, name = "Next", artist = "Someone", albumArt = null), contextUri = null)
    }

    @Test
    fun tappingAnotherTrackStopsTheOutgoingAudioAndShowsLoading() {
        tap("spotify:track:other")
        verify { rig.service.stop() }
        assertTrue(rig.vm.isStreamLoading.value)
    }

    @Test
    fun reTappingTheStreamingTrackLeavesItPlaying() {
        tap("spotify:track:test")
        verify(exactly = 0) { rig.service.stop() }
        assertFalse(rig.vm.isStreamLoading.value)
    }
}
