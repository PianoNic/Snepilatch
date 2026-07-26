package ch.snepilatch.app.viewmodel

import ch.snepilatch.app.data.PlaybackUiState
import ch.snepilatch.app.data.TrackInfo
import ch.snepilatch.app.playback.MusicPlaybackService
import ch.snepilatch.app.playback.SessionHolder
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotify.api.playerconnect.PlayerConnect

/**
 * Test rig for [PlaybackViewModel] playback logic. Wires up:
 *  - a mocked [MusicPlaybackService.instance] so handler calls can be observed
 *  - the Main dispatcher swapped for an unconfined test dispatcher so the
 *    ViewModel's coroutines run synchronously
 *
 * Tests grab the rig in `@Before` (call [install]) and tear it down in
 * `@After` (call [uninstall]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackTestRig {
    val testDispatcher = UnconfinedTestDispatcher()
    val testScope = TestScope(testDispatcher)
    lateinit var service: MusicPlaybackService
        private set
    lateinit var vm: PlaybackViewModel
        private set

    /** Stand-in for the Connect player, so transport commands can be observed with `coVerify`. */
    lateinit var player: PlayerConnect
        private set

    fun install() {
        Dispatchers.setMain(testDispatcher)
        service = mockk(relaxed = true)
        mockkObject(MusicPlaybackService.Companion)
        every { MusicPlaybackService.instance } returns service
        player = mockk(relaxed = true)
        SessionHolder.player = player
        vm = PlaybackViewModel()
    }

    fun uninstall() {
        unmockkObject(MusicPlaybackService.Companion)
        SessionHolder.player = null
        Dispatchers.resetMain()
    }

    /**
     * Put the VM into "streaming locally" state so handleRemote* exercise the
     * streaming branches. The default state is "not streaming".
     */
    fun seedStreaming(positionMs: Long = 0L, isPaused: Boolean = false) {
        vm.isStreaming.value = true
        vm._playback.value = PlaybackUiState(
            track = TrackInfo(uri = "spotify:track:test", name = "Test", artist = "Tester", albumArt = null, durationMs = 200_000),
            isPlaying = !isPaused,
            isPaused = isPaused,
            positionMs = positionMs,
            durationMs = 200_000
        )
    }
}
