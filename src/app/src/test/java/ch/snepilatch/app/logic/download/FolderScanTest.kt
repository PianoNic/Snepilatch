package ch.snepilatch.app.logic.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** The scan finds the id in the bytes the taggers really write, in both layouts (#567). */
class FolderScanTest {

    private val id = "5u7QargOKXO82nXcAU9s8L"
    private val tags = TrackTags("Biertornado", "PA69", trackId = id)

    private fun latin1(bytes: ByteArray) = String(bytes, Charsets.ISO_8859_1)

    @Test
    fun readsTheIdFromAVorbisComment() {
        assertEquals(id, FolderScan.trackIdIn(latin1(VorbisComments.commentBlock(tags))))
    }

    @Test
    fun readsTheIdFromAnMp4FreeformAtom() {
        fun box(type: String, payload: ByteArray): ByteArray {
            val size = 8 + payload.size
            return ByteArray(4) { ((size ushr (8 * (3 - it))) and 0xFF).toByte() } + type.toByteArray(Charsets.ISO_8859_1) + payload
        }
        val mp4 = box("ftyp", "isom".toByteArray()) + box("mdat", ByteArray(64)) + box("moov", box("mvhd", ByteArray(8)))
        val out = ByteArrayOutputStream()
        Mp4Tagger.tag(ByteArrayInputStream(mp4), out, tags)

        assertEquals(id, FolderScan.trackIdIn(latin1(out.toByteArray())))
    }

    @Test
    fun aFileWithoutTheTagHasNoId() {
        assertNull(FolderScan.trackIdIn(latin1(VorbisComments.commentBlock(TrackTags("Song", "Band")))))
    }
}
