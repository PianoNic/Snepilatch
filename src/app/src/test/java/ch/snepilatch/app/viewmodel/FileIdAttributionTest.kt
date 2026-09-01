package ch.snepilatch.app.viewmodel

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * A file id belongs to one track. Reusing the last one seen made a track change that carried no file
 * id load the previous song's audio under the new song's name (Loki, 2026-09-01: a change to Sugar
 * resolved Heartwave's file id, because the pre-resolved-CDN check then compared that id to itself).
 */
class FileIdAttributionTest {

    private val rig = PlaybackTestRig()

    private val sugar = "spotify:track:0g79ji5a5HG1Ea69B8l98I"
    private val heartwave = "spotify:track:3eD0zzvjGQ11i65M891X4a"
    private val heartwaveFile = "186f35684c00ba5a518d36b9bf8129a53104169b"

    @Before fun setUp() = rig.install()

    @After fun tearDown() = rig.uninstall()

    @Test
    fun eventFileIdWins() {
        rig.vm.handlePlaybackId(heartwaveFile, heartwave)
        assertEquals("own", rig.vm.fileIdForTrack("own", sugar))
    }

    @Test
    fun anotherTracksFileIdIsNotReused() {
        rig.vm.handlePlaybackId(heartwaveFile, heartwave)
        assertNull(rig.vm.fileIdForTrack(null, sugar))
    }

    @Test
    fun thisTracksFileIdStandsInWhenTheChangeCarriesNone() {
        rig.vm.handlePlaybackId(heartwaveFile, heartwave)
        assertEquals(heartwaveFile, rig.vm.fileIdForTrack(null, heartwave))
    }

    @Test
    fun anUnattributedFileIdIsStillTrusted() {
        // onPlaybackId can land before the track it belongs to is known; that race is what the
        // pre-resolved cache relies on for an instant skip, so it must keep working.
        rig.vm.handlePlaybackId(heartwaveFile, null)
        assertEquals(heartwaveFile, rig.vm.fileIdForTrack(null, sugar))
    }
}
