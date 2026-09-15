package ch.snepilatch.app.viewmodel

import ch.snepilatch.app.data.TrackInfo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The player only offers to take the track out of the playlist when that is a thing it can do
 * (#851): the playlist has to be the user's own, the context has to be a playlist at all, and the
 * row that is playing has to be known, since the removal is keyed on it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RemovePlayingFromPlaylistTest {

    private val rig = PlaybackTestRig()

    @Before fun setUp() = rig.install()

    @After fun tearDown() = rig.uninstall()

    private fun playing(uid: String?) {
        rig.seedStreaming()
        rig.vm._playback.value = rig.vm.playback.value.copy(
            track = TrackInfo(uri = "spotify:track:t", uid = uid, name = "T", artist = "A", albumArt = null),
        )
    }

    private fun context(uri: String?, owned: Boolean) {
        rig.vm.playingContext.value = uri?.let { PlaybackViewModel.PlayingContext("Playlist", "Mine", it, owned) }
    }

    @Test fun ownPlaylistWithAKnownRow_canBeRemovedFrom() {
        playing("row1")
        context("spotify:playlist:p1", owned = true)
        assertTrue(rig.vm.canRemovePlayingFromPlaylist())
    }

    @Test fun someoneElsesPlaylist_cannot() {
        playing("row1")
        context("spotify:playlist:p1", owned = false)
        assertFalse(rig.vm.canRemovePlayingFromPlaylist())
    }

    @Test fun aTrackWithoutItsRow_cannot() {
        playing(null)
        context("spotify:playlist:p1", owned = true)
        assertFalse(rig.vm.canRemovePlayingFromPlaylist())
    }

    @Test fun anAlbumOrNoContextAtAll_cannot() {
        playing("row1")
        context("spotify:album:a1", owned = true)
        assertFalse("an album is not a playlist", rig.vm.canRemovePlayingFromPlaylist())
        context(null, owned = true)
        assertFalse("nothing to remove from", rig.vm.canRemovePlayingFromPlaylist())
    }
}
