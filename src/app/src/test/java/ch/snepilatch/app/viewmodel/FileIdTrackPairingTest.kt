package ch.snepilatch.app.viewmodel

import ch.snepilatch.app.playback.SessionHolder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A file id is only valid for the track it was issued for. When a local advance and the server name
 * different tracks after an ad, the app used to pair the last file id it saw with the incoming URI
 * and load the wrong song's audio — observed live as
 * "Resolving stream for spotify:track:7wJ5… / Using pre-resolved CDN URL (fileId=c67a16e0…) /
 * Loading DRM: WINDOWS 95", i.e. one track's URI played with another track's audio.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FileIdTrackPairingTest {

    private val rig = PlaybackTestRig()

    @Before
    fun setUp() {
        rig.install()
    }

    @After
    fun tearDown() {
        SessionHolder.player = null
        rig.uninstall()
    }

    @Test
    fun audioFromAnotherTrackIsNeverAccepted() {
        assertFalse(
            "a file id issued for a different track must not be used",
            rig.vm.belongsTo("spotify:track:OTHER", "spotify:track:WANTED")
        )
    }

    @Test
    fun audioForTheSameTrackIsAccepted() {
        assertTrue(rig.vm.belongsTo("spotify:track:WANTED", "spotify:track:WANTED"))
    }

    @Test
    fun unknownOwnerStaysUsable() {
        // Older pushes and the cold-start path surface a file id without a URI. Rejecting those would
        // strand playback, and they are no worse than the previous behaviour.
        assertTrue(
            "an unknown owner must stay usable so cold start still resolves",
            rig.vm.belongsTo(null, "spotify:track:WANTED")
        )
    }
}
