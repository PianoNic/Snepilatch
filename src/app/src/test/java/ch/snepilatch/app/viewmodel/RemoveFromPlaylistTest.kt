package ch.snepilatch.app.viewmodel

import ch.snepilatch.app.data.DetailData
import ch.snepilatch.app.data.TrackInfo
import ch.snepilatch.app.data.canEditPlaylistItems
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
import ch.snepilatch.app.logic.shared.Navigator

/**
 * Removing is destructive and the app can't undo it, so what gates the action matters more than what
 * it does.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RemoveFromPlaylistTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var vm: DetailViewModel

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        SessionHolder.session = null
        SessionHolder.username = "pianonic"
        Navigator.reset()
        vm = DetailViewModel()
    }

    @After fun tearDown() {
        SessionHolder.username = ""
        Dispatchers.resetMain()
    }

    private fun playlist(owner: String = "spotify:user:pianonic", canEdit: Boolean = true) = DetailData(
        name = "Mix",
        uri = "spotify:playlist:p1",
        type = "playlist",
        ownerUri = owner,
        canEditItems = canEdit,
        tracks = listOf(
            TrackInfo("spotify:track:a", "A", "x", null, uid = "u1"),
            // The same song twice — this is what makes uid rather than uri the key.
            TrackInfo("spotify:track:a", "A", "x", null, uid = "u2"),
        ),
        totalCount = 2,
        loadedOffset = 2
    )

    @Test fun ownPlaylistIsEditable() {
        assertTrue(playlist().canEditPlaylistItems())
    }

    @Test fun someoneElsesPlaylistIsEditableWhenTheyLetYou() {
        // A collaborative playlist: not ours, still ours to change. Comparing the owner used to hide
        // the remove action here (#853).
        assertTrue(playlist(owner = "spotify:user:stranger").canEditPlaylistItems())
    }

    @Test fun aPlaylistWeOnlyFollowIsNot() {
        assertFalse(playlist(owner = "spotify:user:stranger", canEdit = false).canEditPlaylistItems())
    }

    @Test fun likedSongsIsNotAPlaylist() {
        val liked = DetailData(uri = "spotify:collection:tracks", canEditItems = true)
        assertFalse(liked.canEditPlaylistItems())
    }

    @Test fun anAlbumIsNotAPlaylist() {
        assertFalse(playlist().copy(type = "album").canEditPlaylistItems())
    }

    @Test fun removingWithoutASessionLeavesTheListAlone() {
        // The row drops optimistically, so bailing out early is what stops a signed-out tap from
        // blanking a row that is still in the playlist.
        vm.setDetailForTest(playlist())
        vm.removeFromPlaylist(playlist().tracks[0])
        assertEquals(2, vm.detail.value.tracks.size)
    }

    @Test fun removingATrackWithNoUidIsANoOp() {
        vm.setDetailForTest(playlist())
        vm.removeFromPlaylist(TrackInfo("spotify:track:a", "A", "x", null, uid = null))
        assertEquals(2, vm.detail.value.tracks.size)
    }
}
