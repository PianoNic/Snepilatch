package ch.snepilatch.app.viewmodel

import ch.snepilatch.app.data.PlaybackUiState
import ch.snepilatch.app.data.TrackInfo
import ch.snepilatch.app.playback.MusicPlaybackService
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

/**
 * Test rig for [SpotifyViewModel] playback logic. Wires up:
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
    lateinit var vm: SpotifyViewModel
        private set

    fun install() {
        Dispatchers.setMain(testDispatcher)
        service = mockk(relaxed = true)
        mockkObject(MusicPlaybackService.Companion)
        every { MusicPlaybackService.instance } returns service
        vm = SpotifyViewModel()
    }

    fun uninstall() {
        unmockkObject(MusicPlaybackService.Companion)
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
