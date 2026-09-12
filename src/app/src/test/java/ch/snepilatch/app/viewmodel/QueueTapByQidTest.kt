package ch.snepilatch.app.viewmodel

import ch.snepilatch.app.data.TrackInfo
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.After
import org.junit.Before
import org.junit.Test

/** Issue #681: a tapped queue row is addressed by its qid, which is what tells a hand-queued entry apart. */
@OptIn(ExperimentalCoroutinesApi::class)
class QueueTapByQidTest {

    private val rig = PlaybackTestRig()

    @Before
    fun setUp() {
        rig.install()
        coEvery { rig.player.skipToTrack(any()) } returns true
    }

    @After
    fun tearDown() = rig.uninstall()

    @Test
    fun tappedRow_isSkippedToByQid() {
        rig.vm._queue.value = listOf(
            TrackInfo(uri = "spotify:track:a", name = "A", artist = "X", albumArt = null, uid = "ua", qid = "ua:::0", queueIndex = 0),
            TrackInfo(uri = "spotify:track:b", name = "B", artist = "X", albumArt = null, uid = "ub", qid = "ub:::2", queueIndex = 1),
        )

        rig.vm.skipToQueueIndex(1)

        coVerify(timeout = 1_000, exactly = 1) { rig.player.skipToTrack("ub:::2") }
    }

    @Test
    fun rowWithoutQid_fallsBackToItsUid() {
        rig.vm._queue.value = listOf(
            TrackInfo(uri = "spotify:track:a", name = "A", artist = "X", albumArt = null, uid = "ua", queueIndex = 0),
        )

        rig.vm.skipToQueueIndex(0)

        coVerify(timeout = 1_000, exactly = 1) { rig.player.skipToTrack("ua") }
    }
}
