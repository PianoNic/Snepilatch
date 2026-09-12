package ch.snepilatch.app.viewmodel

import ch.snepilatch.app.data.TrackInfo
import io.mockk.coVerify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotify.api.playerstatus.AdvanceReason
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Regression tests for issue #684: next, previous and seek must reach the device that holds
 * playback. With another Connect device active the local state reports describe this idle phone and
 * the remote never moves; with this phone active the local reports stay the (uncapped) path.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RemoteDeviceSkipSeekTest {

    private val rig = PlaybackTestRig()

    @Before
    fun setUp() {
        rig.install()
        rig.seedStreaming(positionMs = 0L)
    }

    @After
    fun tearDown() {
        rig.uninstall()
    }

    @Test
    fun nextWithForeignDeviceActive_sendsSkipNextCommand() {
        rig.vm.setForeignDeviceActiveForTest(true)
        rig.vm.nextTrackPreview.value = TrackInfo(uri = "spotify:track:next", name = "Next", artist = "Artist", albumArt = null)

        rig.vm.skipNext()
        runBlocking { rig.vm.awaitCommandForTest() }

        coVerify(exactly = 1) { rig.player.skipNext() }
        coVerify(exactly = 0) { rig.player.localNext(any()) }
        assertEquals("optimistic skip still applies", "spotify:track:next", rig.vm.playback.value.track?.uri)
    }

    @Test
    fun nextWhenThisDeviceIsActive_usesLocalStateReport() {
        rig.vm.setForeignDeviceActiveForTest(false)

        rig.vm.skipNext()
        runBlocking { rig.vm.awaitCommandForTest() }

        coVerify(exactly = 1) { rig.player.localNext(AdvanceReason.USER_SKIP) }
        coVerify(exactly = 0) { rig.player.skipNext() }
    }

    @Test
    fun previousWithForeignDeviceActive_sendsSkipPreviousCommand() {
        rig.vm.setForeignDeviceActiveForTest(true)

        rig.vm.skipPrevious()
        runBlocking { rig.vm.awaitCommandForTest() }

        coVerify(exactly = 1) { rig.player.skipPrevious() }
        coVerify(exactly = 0) { rig.player.localPrevious(any()) }
    }

    @Test
    fun previousWhenThisDeviceIsActive_usesLocalStateReport() {
        rig.vm.setForeignDeviceActiveForTest(false)

        rig.vm.skipPrevious()
        runBlocking { rig.vm.awaitCommandForTest() }

        coVerify(exactly = 1) { rig.player.localPrevious(0L) }
        coVerify(exactly = 0) { rig.player.skipPrevious() }
    }

    @Test
    fun seekWithForeignDeviceActive_sendsSeekCommand() {
        rig.vm.setForeignDeviceActiveForTest(true)

        rig.vm.seekTo(42_000L)

        coVerify(timeout = 1_000, exactly = 1) { rig.player.seek(42_000) }
        coVerify(exactly = 0) { rig.player.localSeek(any()) }
    }

    @Test
    fun seekWhenThisDeviceIsActive_usesLocalStateReport() {
        rig.vm.setForeignDeviceActiveForTest(false)

        rig.vm.seekTo(42_000L)

        coVerify(timeout = 1_000, exactly = 1) { rig.player.localSeek(42_000L) }
        coVerify(exactly = 0) { rig.player.seek(any()) }
    }
}
