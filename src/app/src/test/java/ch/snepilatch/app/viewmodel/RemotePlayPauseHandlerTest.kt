package ch.snepilatch.app.viewmodel

import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Regression tests for the remote play/pause mirroring logic. The
 * "browser pause doesn't pause phone" bug we hit twice would have been caught
 * by [remotePauseWhileStreaming_pausesExoPlayer].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RemotePlayPauseHandlerTest {

    private val rig = PlaybackTestRig()

    @Before
    fun setUp() {
        rig.install()
    }

    @After
    fun tearDown() {
        rig.uninstall()
    }

    @Test
    fun remotePlayWhenNotStreaming_forwardsSyncPlay() {
        rig.vm.handleRemotePlay(positionMs = 12_345L)
        verify { rig.service.syncPlay(12_345L) }
    }

    @Test
    fun remotePauseWhenNotStreaming_forwardsSyncPause() {
        rig.vm.handleRemotePause(positionMs = 0L)
        verify { rig.service.syncPause() }
    }

    /**
     * The "browser pause" regression: when streaming locally, a remote pause
     * MUST pause the local ExoPlayer too. Previously this was silently
     * dropped and the music kept playing on the phone.
     */
    @Test
    fun remotePauseWhileStreaming_pausesExoPlayer() {
        rig.seedStreaming(positionMs = 50_000L, isPaused = false)

        rig.vm.handleRemotePause(positionMs = 50_000L)

        verify { rig.service.syncPause() }
        assertTrue("playback should be marked paused", rig.vm.playback.value.isPaused)
        assertFalse("playback should NOT be marked playing", rig.vm.playback.value.isPlaying)
    }

    /**
     * Remote play while streaming + currently paused → resume locally.
     */
    @Test
    fun remotePlayWhileStreamingPaused_resumesExoPlayer() {
        rig.seedStreaming(positionMs = 30_000L, isPaused = true)

        rig.vm.handleRemotePlay(positionMs = 30_000L)

        verify { rig.service.syncPlay(30_000L) }
        assertTrue("playback should be marked playing", rig.vm.playback.value.isPlaying)
        assertFalse("playback should NOT be marked paused", rig.vm.playback.value.isPaused)
    }
}
