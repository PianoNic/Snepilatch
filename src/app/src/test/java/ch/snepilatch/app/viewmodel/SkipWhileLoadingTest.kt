package ch.snepilatch.app.viewmodel

import ch.snepilatch.app.playback.AudioSourceResolver
import ch.snepilatch.app.playback.SessionHolder
import ch.snepilatch.app.playback.engine.SpfyCdnResolver
import ch.snepilatch.app.playback.engine.SpfyStream
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.slot
import io.mockk.unmockkObject
import io.mockk.verify
import kotify.api.playerstatus.PlayerTrack
import kotify.api.playerstatus.TrackChangeEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Issue #703: a skip while a track was still loading played the audio of that track. Its file id was
 * the latest one seen, and the wait for the new track's id was satisfied by any id at all.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SkipWhileLoadingTest {

    private val rig = PlaybackTestRig()
    private val resolver = mockk<SpfyCdnResolver>(relaxed = true)
    private val onTrackChange = slot<(TrackChangeEvent) -> Unit>()

    private val loading = "spotify:track:loading"
    private val loadingFile = "file-loading"
    private val skippedTo = "spotify:track:skippedTo"
    private val skippedToFile = "file-skippedTo"

    @Before
    fun setUp() {
        rig.install()
        mockkObject(AudioSourceResolver)
        every { AudioSourceResolver.localOrNull(any(), any(), any()) } returns null
        AppSettings.preferredAudioSource.value = null
        coEvery { resolver.resolveForFileId(any(), any()) } answers {
            SpfyStream(cdnUrl = "cdn/${firstArg<String>()}", licenseUrl = "lic", licenseHeaders = emptyMap(), mirrorCount = 1, pssh = null)
        }
        SessionHolder.cdnResolver = resolver
        coEvery { rig.player.getState() } returns null
        every { rig.player.onTrackChange(capture(onTrackChange)) } just runs
        rig.vm.adoptRunningSession()
        rig.seedStreaming()
        // The track that is still loading has announced its file id.
        rig.vm.handlePlaybackId(loadingFile, loading)
    }

    @After
    fun tearDown() {
        unmockkObject(AudioSourceResolver)
        SessionHolder.cdnResolver = null
        rig.uninstall()
    }

    @Test
    fun skipWhileLoading_waitsForTheNewTracksFileId() {
        onTrackChange.captured(change(skippedTo))
        Thread.sleep(300) // the new track's id lands after the change, as the state machine announces it
        rig.vm.handlePlaybackId(skippedToFile, skippedTo)

        coVerify(timeout = 3_000, exactly = 1) { resolver.resolveForFileId(skippedToFile, any()) }
        coVerify(exactly = 0) { resolver.resolveForFileId(loadingFile, any()) }
        verify(timeout = 1_000) {
            rig.service.playDrmUrl("cdn/$skippedToFile", any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    private fun change(uri: String) = TrackChangeEvent(
        previous = loading,
        current = PlayerTrack(
            uri = uri, uid = null, provider = null, name = "Song", artistName = "Artist", artistUri = null,
            albumName = null, albumUri = null, durationMs = 200_000L, isExplicit = false,
            imageUrl = null, imageSmallUrl = null, imageLargeUrl = null, contextUri = null,
        ),
        currentFileId = null,
    )
}
