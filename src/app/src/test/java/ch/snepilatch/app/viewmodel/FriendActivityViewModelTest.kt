package ch.snepilatch.app.viewmodel

import ch.snepilatch.app.logic.shared.SessionHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Like [ProfileViewModelTest], no network: without a session a load short-circuits and the loading flag never sticks. */
@OptIn(ExperimentalCoroutinesApi::class)
class FriendActivityViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        SessionHolder.session = null
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test fun loadWithoutSession_leavesTheFeedEmptyAndLoadingOff() {
        val vm = FriendActivityViewModel()
        vm.load()
        assertTrue(vm.feed.value.isEmpty())
        assertFalse(vm.loading.value)
    }
}
