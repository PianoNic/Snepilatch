package ch.snepilatch.app.viewmodel

import ch.snepilatch.app.logic.shared.SessionHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/**
 * Like [LyricsViewModelTest], no network: with no [SessionHolder.session] a call short-circuits,
 * which pins the local contract, the busy flag never sticks and the done callback never fires
 * for a call that did nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var vm: ProfileViewModel

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        SessionHolder.session = null
        vm = ProfileViewModel()
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test fun removeWithoutSession_isANoOpThatLeavesBusyOff() {
        var done = false
        vm.removePicture { done = true }
        assertFalse(vm.busy.value)
        assertFalse(done)
    }
}
