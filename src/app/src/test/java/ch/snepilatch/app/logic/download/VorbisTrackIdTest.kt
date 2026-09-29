package ch.snepilatch.app.logic.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Opus and FLAC share the comment block, so one check covers both (#566). */
class VorbisTrackIdTest {

    @Test
    fun theCommentBlockCarriesTheTrackId() {
        val tags = TrackTags("Song", "Band", trackId = "4uLU6hMCjMI75M1A2tKUQC")
        val block = String(VorbisComments.commentBlock(tags), Charsets.UTF_8)

        assertTrue(block.contains("SPOTIFY_TRACK_ID=4uLU6hMCjMI75M1A2tKUQC"))
    }

    @Test
    fun noIdMeansNoComment() {
        val block = String(VorbisComments.commentBlock(TrackTags("Song", "Band")), Charsets.UTF_8)

        assertFalse(block.contains("SPOTIFY_TRACK_ID"))
    }
}
