package ch.snepilatch.app.viewmodel

import io.mockk.coEvery
import io.mockk.verify
import kotify.api.playerstatus.DevicesInfo
import kotify.api.playerstatus.PlayerStateData
import kotify.api.playerstatus.PlayerTrack
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Issue #701: after the app was killed mid-track the restored snapshot position reached the now
 * playing screen but never the notification, which sat at 0 until playback resumed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IdleNotificationPositionTest {

    private val rig = PlaybackTestRig()

    @Before
    fun setUp() {
        rig.install()
        rig.vm.isInitialized.value = true
        coEvery { rig.player.getDevices() } returns DevicesInfo(emptyMap(), null)
    }

    @After
    fun tearDown() = rig.uninstall()

    @Test
    fun restoredPausedSnapshot_pushesItsPositionToTheIdleNotification() {
        coEvery { rig.player.getState() } returns PlayerStateData(
            is_playing = false, is_paused = true,
            track = PlayerTrack(
                uri = "spotify:track:test", uid = "uid", provider = "context", name = "Test", artistName = "Tester",
                artistUri = null, albumName = null, albumUri = null, durationMs = 200_000L, isExplicit = false,
                imageUrl = null, imageSmallUrl = null, imageLargeUrl = null, contextUri = null,
            ),
            position_as_of_timestamp = 42_000L, timestamp = 1_700_000_000_000L, duration = 200_000L,
            play_origin = null, is_active_device = false, has_active_device = false,
        )

        rig.vm.resyncOnForeground()

        verify(timeout = 2_000) {
            rig.service.setIdleMetadata(title = "Test", artist = any(), albumArtUrl = any(), durationMs = 200_000L, positionMs = 42_000L)
        }
    }
}
