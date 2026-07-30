package ch.snepilatch.app.download

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * The tagger is pure byte surgery, so it is testable without a device: what it must never do is
 * disturb the bytes before `moov`, because the chunk offsets inside point at them.
 */
class Mp4TaggerTest {

    private val tags = TrackTags(title = "Song", artist = "Band", album = "Record")

    /** A minimal file in the layout MediaMuxer produces: ftyp, then mdat, with moov last. */
    private fun mp4(moovLast: Boolean = true): ByteArray {
        val ftyp = box("ftyp", "isom".toByteArray())
        val mdat = box("mdat", ByteArray(64) { it.toByte() })
        val moov = box("moov", box("mvhd", ByteArray(8)))
        return if (moovLast) ftyp + mdat + moov else ftyp + moov + mdat
    }

    private fun box(type: String, payload: ByteArray): ByteArray {
        val size = 8 + payload.size
        return ByteArray(4) { ((size ushr (8 * (3 - it))) and 0xFF).toByte() } +
            type.toByteArray(Charsets.ISO_8859_1) + payload
    }

    private fun tag(input: ByteArray): Pair<Boolean, ByteArray> {
        val out = ByteArrayOutputStream()
        val ok = Mp4Tagger.tag(ByteArrayInputStream(input), out, tags)
        return ok to out.toByteArray()
    }

    @Test
    fun `writes tags and leaves everything before moov byte-identical`() {
        val original = mp4()
        val moovStart = original.size - 24 // the moov box built above
        val (ok, tagged) = tag(original)

        assertTrue(ok)
        assertArrayEquals(
            original.copyOfRange(0, moovStart),
            tagged.copyOfRange(0, moovStart),
        )
        assertTrue(tagged.size > original.size)
        assertTrue(String(tagged, Charsets.ISO_8859_1).contains("ilst"))
        assertTrue(String(tagged, Charsets.ISO_8859_1).contains("Song"))
        assertTrue(String(tagged, Charsets.ISO_8859_1).contains("Band"))
    }

    @Test
    fun `moov size header grows by exactly the udta that was appended`() {
        val original = mp4()
        val moovStart = original.size - 24
        val (_, tagged) = tag(original)

        val newMoovSize = readBeInt(tagged, moovStart)
        val oldMoovSize = readBeInt(original, moovStart)
        // Everything after the moov header is the original children plus the new udta.
        assertTrue(newMoovSize > oldMoovSize)
        assertTrue(newMoovSize == tagged.size - moovStart)
    }

    /** moov before mdat means appending would shift the media and break every chunk offset. */
    @Test
    fun `declines a file whose moov is not last`() {
        assertFalse(tag(mp4(moovLast = false)).first)
    }

    @Test
    fun `declines input that is not an mp4`() {
        assertFalse(tag("not an mp4 at all".toByteArray()).first)
    }

    private fun readBeInt(bytes: ByteArray, at: Int): Int {
        var value = 0
        for (i in 0 until 4) value = (value shl 8) or (bytes[at + i].toInt() and 0xFF)
        return value
    }
}
