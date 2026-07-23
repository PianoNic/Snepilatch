package ch.snepilatch.app.viewmodel

import ch.snepilatch.app.data.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/** Regression cover for "back opens the player": overlays must not sit on the back stack. */
@OptIn(ExperimentalCoroutinesApi::class)
class NavigationStackTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var vm: PlaybackViewModel

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        vm = PlaybackViewModel()
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test fun backFromPageOpenedFromPlayerReturnsToContentNotPlayer() {
        vm.navigateTo(Screen.PLAYLIST_DETAIL) // stack=[HOME]
        vm.navigateTo(Screen.NOW_PLAYING) // stack=[HOME, PLAYLIST_DETAIL]
        vm.navigateTo(Screen.QUEUE) // overlay→content: stack unchanged
        assertEquals(Screen.QUEUE, vm.currentScreen.value)

        vm.goBack()
        // The bug was this returning NOW_PLAYING; it must be the page beneath the player.
        assertEquals(Screen.PLAYLIST_DETAIL, vm.currentScreen.value)
    }

    @Test fun backFromLyricsReturnsToPlayer() {
        vm.navigateTo(Screen.NOW_PLAYING)
        vm.navigateTo(Screen.LYRICS)
        vm.goBack()
        assertEquals(Screen.NOW_PLAYING, vm.currentScreen.value)
    }

    @Test fun backFromPlayerCollapsesToContentBeneath() {
        vm.navigateTo(Screen.NOW_PLAYING)
        vm.goBack()
        assertEquals(Screen.HOME, vm.currentScreen.value)
    }

    @Test fun tabSwitchResetsStackAndBackReturnsToHome() {
        vm.navigateTo(Screen.PLAYLIST_DETAIL)
        vm.navigateTo(Screen.NOW_PLAYING)
        vm.navigateToTab(Screen.SEARCH)
        assertEquals(Screen.SEARCH, vm.currentScreen.value)

        vm.goBack()
        assertEquals(Screen.HOME, vm.currentScreen.value)
    }

    @Test fun homeTabLeavesEmptyStack() {
        vm.navigateTo(Screen.PLAYLIST_DETAIL)
        vm.navigateToTab(Screen.HOME)
        assertEquals(Screen.HOME, vm.currentScreen.value)
        assertFalse(vm.goBack())
    }

    @Test fun duplicateNavigateDoesNotStackOntoItself() {
        vm.navigateTo(Screen.QUEUE)
        vm.navigateTo(Screen.QUEUE)
        vm.goBack()
        assertEquals(Screen.HOME, vm.currentScreen.value)
    }
}
