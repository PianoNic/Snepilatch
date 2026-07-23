package ch.snepilatch.app.viewmodel

import ch.snepilatch.app.data.Screen
import ch.snepilatch.app.playback.SessionHolder
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

/**
 * Tests for [DetailViewModel]. Like [SearchViewModelTest]/[LyricsViewModelTest] this doesn't mock the
 * network: with no [SessionHolder.session] a load short-circuits, which lets us pin two contracts —
 * (1) opening a detail navigates via [Navigator] *before* the load runs, so navigation is unaffected
 * by session state, and (2) [DetailRoutes] routes to the constructed ViewModel.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DetailViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var vm: DetailViewModel

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        SessionHolder.session = null
        Navigator.reset()
        vm = DetailViewModel()
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test fun startsClean() {
        assertEquals("", vm.detail.value.uri)
        assertFalse(vm.isLoading.value)
        assertFalse(vm.isLoadingMore.value)
        assertFalse(vm.detailSaved.value)
    }

    @Test fun openAlbumNavigatesEvenWithoutSession() {
        vm.openAlbum("abc")
        assertEquals(Screen.ALBUM_DETAIL, Navigator.currentScreen.value)
        // Load short-circuits (no session): loading flag settled, detail untouched.
        assertFalse(vm.isLoading.value)
        assertEquals("", vm.detail.value.uri)
    }

    @Test fun openPlaylistAndArtistNavigateToTheirScreens() {
        vm.openPlaylist("p1")
        assertEquals(Screen.PLAYLIST_DETAIL, Navigator.currentScreen.value)
        vm.openArtist("a1")
        assertEquals(Screen.ARTIST_DETAIL, Navigator.currentScreen.value)
    }

    @Test fun detailRoutesForwardsToTheConstructedViewModel() {
        // SpotifyViewModel's deep-link/playback bridges reach the detail openers through this hop.
        DetailRoutes.openArtist("a2")
        assertEquals(Screen.ARTIST_DETAIL, Navigator.currentScreen.value)
    }

    @Test fun checkDetailSavedDefaultsFalseWithoutSession() {
        vm.checkDetailSaved("album", "abc")
        assertFalse(vm.detailSaved.value)
    }
}
