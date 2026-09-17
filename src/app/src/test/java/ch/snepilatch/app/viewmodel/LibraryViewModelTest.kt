package ch.snepilatch.app.viewmodel

import ch.snepilatch.app.data.LibraryItem
import ch.snepilatch.app.logic.shared.SessionHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for [LibraryViewModel]. Like the other feature-VM tests this uses the no-session path
 * ([SessionHolder.session] == null), where every load short-circuits — so we pin the local-state
 * contract: construction (which kicks off the init load) and the loaders never populate the list or
 * crash without a session.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var vm: LibraryViewModel

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        SessionHolder.session = null
        vm = LibraryViewModel()
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test fun startsClean() {
        // init { loadLibrary() } ran, but with no session it's a no-op.
        assertTrue(vm.library.value.isEmpty())
        assertEquals(-1, vm.libraryTotal.value)
        assertFalse(vm.isLoadingMore.value)
    }

    @Test fun loadLibraryWithoutSessionKeepsListEmpty() {
        vm.loadLibrary()
        assertTrue(vm.library.value.isEmpty())
        assertEquals(-1, vm.libraryTotal.value)
    }

    @Test fun loadMoreWithoutSessionKeepsListEmpty() {
        vm.loadMoreLibrary()
        assertTrue(vm.library.value.isEmpty())
    }

    @Test fun createPlaylistWithoutSessionIsSafeNoOp() {
        vm.createPlaylist("My Playlist")
        assertTrue(vm.library.value.isEmpty())
    }

    // --- folders (#573): the library is a tree, and a playlist filed into a folder is only
    // reachable by descending into it.

    @Test fun startsAtTheLibraryRoot() {
        assertTrue(vm.folderPath.value.isEmpty())
    }

    @Test fun openFolderDescendsAndCloseFolderComesBackUp() {
        vm.openFolder(folder("spotify:user:u:folder:a", "Road trips"))
        assertEquals(listOf("Road trips"), vm.folderPath.value.map { it.name })

        vm.openFolder(folder("spotify:user:u:folder:b", "Long drives"))
        assertEquals(listOf("Road trips", "Long drives"), vm.folderPath.value.map { it.name })

        assertTrue(vm.closeFolder())
        assertEquals(listOf("Road trips"), vm.folderPath.value.map { it.name })
        assertTrue(vm.closeFolder())
        assertTrue(vm.folderPath.value.isEmpty())
    }

    @Test fun closeFolderAtRootReportsNothingToClose() {
        // The screen's back handler relies on this to fall through to normal back navigation.
        assertFalse(vm.closeFolder())
    }

    @Test fun openFolderIgnoresItemsThatAreNotFolders() {
        vm.openFolder(LibraryItem("spotify:playlist:x", "Some playlist", null, "playlist"))
        assertTrue(vm.folderPath.value.isEmpty())
    }

    @Test fun descendingDropsTheParentListingSoItCannotBleedThrough() {
        vm.openFolder(folder("spotify:user:u:folder:a", "Road trips"))
        assertTrue(vm.library.value.isEmpty())
        assertEquals(-1, vm.libraryTotal.value)
    }

    private fun folder(uri: String, name: String) = LibraryItem(uri, name, null, "folder")
}
