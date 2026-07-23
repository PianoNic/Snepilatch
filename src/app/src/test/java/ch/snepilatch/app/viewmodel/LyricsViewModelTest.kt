package ch.snepilatch.app.viewmodel

import ch.snepilatch.app.playback.SessionHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Tests for [LyricsViewModel]. Like [SearchViewModelTest], this doesn't mock
 * the network: with no [SessionHolder.session] the fetch short-circuits, which
 * is exactly the local-state contract we want to pin — a fetch never leaves
 * the loading flag stuck on and never populates lyrics without a session.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LyricsViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var vm: LyricsViewModel

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        SessionHolder.session = null
        vm = LyricsViewModel()
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test fun startsClean() {
        assertNull(vm.lyrics.value)
        assertFalse(vm.isLoading.value)
    }

    @Test fun fetchWithoutSessionIsSafeNoOp() {
        vm.fetch("spotify:track:abc123")
        // No session → the launch returns before touching the loading flag or lyrics.
        assertNull(vm.lyrics.value)
        assertFalse(vm.isLoading.value)
    }

    @Test fun repeatedFetchWithoutSessionStaysClean() {
        vm.fetch("spotify:track:abc123")
        vm.fetch("spotify:track:abc123")
        vm.fetch("spotify:track:def456")
        assertNull(vm.lyrics.value)
        assertFalse(vm.isLoading.value)
    }
}
