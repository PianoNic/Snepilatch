package ch.snepilatch.app.viewmodel

import ch.snepilatch.app.playback.SessionHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Tests for [HomeViewModel]. Like the other feature-VM tests this uses the no-session path
 * ([SessionHolder.session] == null): construction kicks off the init load, which short-circuits, so
 * the feed stays null and nothing crashes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var vm: HomeViewModel

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        SessionHolder.session = null
        vm = HomeViewModel()
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test fun startsWithNoFeed() {
        // init { loadHome() } ran, but with no session it's a no-op.
        assertNull(vm.homeData.value)
    }

    @Test fun loadHomeWithoutSessionKeepsFeedNull() {
        vm.loadHome()
        assertNull(vm.homeData.value)
    }
}
