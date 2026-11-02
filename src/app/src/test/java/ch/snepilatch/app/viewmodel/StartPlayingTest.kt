package ch.snepilatch.app.viewmodel

import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test

/** A start finding this phone listed as playing (#1032). */
@OptIn(ExperimentalCoroutinesApi::class)
class StartPlayingTest {

    private val rig = PlaybackTestRig()

    @Before
    fun setUp() = rig.install()

    @After
    fun tearDown() = rig.uninstall()

    @Test
    fun nothingAudibleHereComesUpPaused() = runBlocking {
        rig.seedStreaming(positionMs = 1_000)
        every { rig.service.isPlaying() } returns false

        rig.vm.settleStartPlaying(mockk(relaxed = true))

        coVerify(timeout = 1_000, exactly = 1) { rig.player.localPause(1_000) }
    }

    @Test
    fun audioStillRunningIsLeftAlone() = runBlocking {
        rig.seedStreaming(positionMs = 1_000)
        every { rig.service.isPlaying() } returns true

        rig.vm.settleStartPlaying(mockk(relaxed = true))

        coVerify(exactly = 0) { rig.player.localPause(any()) }
    }
}
