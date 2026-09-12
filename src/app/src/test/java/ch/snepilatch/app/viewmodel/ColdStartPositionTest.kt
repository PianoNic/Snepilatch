package ch.snepilatch.app.viewmodel

import ch.snepilatch.app.data.PlaybackUiState
import ch.snepilatch.app.data.TrackInfo
import io.mockk.coEvery
import kotify.api.playerstatus.DevicesInfo
import kotify.api.playerstatus.PlayerStateData
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Issue #722: a cluster frame that lands while a stream is loading must not paint position 0 over
 * the position the UI shows. A track change sets 0 itself; a cold start loads at the saved position.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ColdStartPositionTest {

    private val rig = PlaybackTestRig()

    @Before
    fun setUp() {
        rig.install()
        rig.vm.isInitialized.value = true
        coEvery { rig.player.getDevices() } returns DevicesInfo(emptyMap(), null)
    }

    @After
    fun tearDown() = rig.uninstall()

    private fun <T> await(block: suspend () -> T): T = runBlocking { withTimeout(2_000) { block() } }

    private val track =
        TrackInfo(uri = "spotify:track:test", name = "Test", artist = "Tester", albumArt = null, durationMs = 249_000)

    private fun clusterAt(positionMs: Long) = PlayerStateData(
        is_playing = true, is_paused = true, track = null, position_as_of_timestamp = positionMs, play_origin = null,
        is_active_device = true, has_active_device = true, active_device_id = "us", duration = 250_000,
    )

    @Test
    fun clusterFrame_whileTheStreamLoads_keepsTheShownPosition() {
        rig.vm._playback.value = PlaybackUiState(track = track, isPaused = true, positionMs = 58_000, durationMs = 249_000)
        rig.vm.isStreamLoading.value = true
        coEvery { rig.player.getState() } returns clusterAt(0)

        rig.vm.resyncOnForeground()

        // The frame is applied (the new duration proves it) but the position stays where the load started.
        assertEquals(58_000L, await { rig.vm.playback.first { it.durationMs == 250_000L }.positionMs })
    }

    @Test
    fun clusterFrame_afterTheLoad_paintsTheClusterPosition() {
        rig.vm._playback.value = PlaybackUiState(track = track, isPaused = true, positionMs = 58_000, durationMs = 249_000)
        rig.vm.isStreamLoading.value = false
        coEvery { rig.player.getState() } returns clusterAt(61_000)

        rig.vm.resyncOnForeground()

        assertEquals(61_000L, await { rig.vm.playback.first { it.positionMs == 61_000L }.positionMs })
    }
}
