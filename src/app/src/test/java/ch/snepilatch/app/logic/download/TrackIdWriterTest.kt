package ch.snepilatch.app.logic.download

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Writing the id into a file already on the phone (#932). A mistake here damages a song the user
 * owns, so every test checks the audio came through byte for byte, not only that the id is there.
 */
class TrackIdWriterTest {

    private val id = "5u7QargOKXO82nXcAU9s8L"

    private fun latin1(b: ByteArray) = String(b, Charsets.ISO_8859_1)

    // --- Ogg Opus --------------------------------------------------------------------------------

    private val opusHead = "OpusHead".toByteArray() + byteArrayOf(1, 2, 0x38, 0x01, 0x80.toByte(), 0xBB.toByte(), 0, 0, 0, 0, 0)
    private val audio =
        List(400) { i -> ByteArray(300 + i % 50) { (i * 7 + it).toByte() }.also { it[0] = 0xF8.toByte() } }

    private fun ogg(cover: TrackTags.Cover? = null): ByteArray {
        val out = ByteArrayOutputStream()
        val writer = OggOpusWriter(out, serial = 1234, preSkip = 312)
        writer.writeHeaders(opusHead, VorbisComments.opusTags(TrackTags("Biertornado", "PA69", album = "Rec", cover = cover)))
        audio.forEach(writer::add)
        writer.finish()
        return out.toByteArray()
    }

    private class Page(val header: ByteArray, val body: ByteArray)

    private fun pages(b: ByteArray): List<Page> {
        val pages = ArrayList<Page>()
        var at = 0
        while (at < b.size) {
            val count = b[at + 26].toInt() and 0xFF
            val headerLength = 27 + count
            val bodyLength = (0 until count).sumOf { b[at + 27 + it].toInt() and 0xFF }
            pages += Page(b.copyOfRange(at, at + headerLength), b.copyOfRange(at + headerLength, at + headerLength + bodyLength))
            at += headerLength + bodyLength
        }
        return pages
    }

    private fun le32(b: ByteArray, at: Int) =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)

    /** Every page's checksum holds and the sequence counts up from zero without a gap. */
    private fun assertValidOgg(b: ByteArray) {
        pages(b).forEachIndexed { i, page ->
            assertEquals("sequence of page $i", i, le32(page.header, 18))
            val zeroed = page.header.copyOf().also { for (k in 22..25) it[k] = 0 }
            assertEquals("checksum of page $i", le32(page.header, 22), OggCrc.of(zeroed, page.body))
        }
    }

    /** The audio pages' bodies, which must not change by a single byte. */
    private fun audioBodies(b: ByteArray): List<ByteArray> =
        pages(b).dropWhile { !latin1(it.body).startsWith("OpusTags") }.drop(1)
            .dropWhile { it.header[5].toInt() and 1 == 1 }.map { it.body }

    @Test
    fun opusGetsTheIdAndKeepsTagsAndAudio() {
        val original = ogg()
        val tagged = TrackIdWriter.withTrackId(original, id)!!

        assertEquals(id, FolderScan.trackIdIn(latin1(tagged)))
        assertTrue(latin1(tagged).contains("TITLE=Biertornado"))
        assertTrue(latin1(tagged).contains("ALBUM=Rec"))
        assertValidOgg(tagged)
        val before = audioBodies(original)
        val after = audioBodies(tagged)
        assertTrue("the fixture must have audio pages", before.size > 1)
        assertEquals(before.size, after.size)
        before.indices.forEach { assertArrayEquals("audio page $it", before[it], after[it]) }
    }

    @Test
    fun opusWithACoverSpanningPagesStaysValid() {
        val cover = TrackTags.Cover(ByteArray(150_000) { (it % 251).toByte() }, "image/jpeg")
        val original = ogg(cover)
        val tagged = TrackIdWriter.withTrackId(original, id)!!

        assertEquals(id, FolderScan.trackIdIn(latin1(tagged)))
        assertValidOgg(tagged)
        assertEquals(audioBodies(original).map { it.toList() }, audioBodies(tagged).map { it.toList() })
    }

    @Test
    fun writingTwiceKeepsOneId() {
        val twice = TrackIdWriter.withTrackId(TrackIdWriter.withTrackId(ogg(), "0000000000000000000000")!!, id)!!

        assertEquals(1, Regex("SPOTIFY_TRACK_ID=").findAll(latin1(twice)).count())
        assertEquals(id, FolderScan.trackIdIn(latin1(twice)))
    }

    // --- FLAC ------------------------------------------------------------------------------------

    private val frames = ByteArray(4096) { (it * 31).toByte() }

    private fun flac(): ByteArray {
        val streamInfo = ByteArray(34) { it.toByte() }
        val plain = "fLaC".toByteArray() + byteArrayOf(0x80.toByte(), 0, 0, 34) + streamInfo + frames
        val out = ByteArrayOutputStream()
        FlacTagger.tag(ByteArrayInputStream(plain), out, TrackTags("Song", "Band"))
        return out.toByteArray()
    }

    @Test
    fun flacGetsTheIdAndKeepsTheFrames() {
        val original = flac()
        val tagged = TrackIdWriter.withTrackId(original, id)!!

        assertEquals(id, FolderScan.trackIdIn(latin1(tagged)))
        assertTrue(latin1(tagged).contains("TITLE=Song"))
        assertArrayEquals(frames, tagged.copyOfRange(tagged.size - frames.size, tagged.size))
    }

    // --- MP4 -------------------------------------------------------------------------------------

    private fun box(type: String, payload: ByteArray): ByteArray {
        val size = 8 + payload.size
        return ByteArray(4) { ((size ushr (8 * (3 - it))) and 0xFF).toByte() } + type.toByteArray(Charsets.ISO_8859_1) + payload
    }

    private fun be32(b: ByteArray, at: Int) =
        ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
            ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)

    private val mdatPayload = ByteArray(256) { (it * 13).toByte() }

    /** A chunk offset table pointing at the start of the mdat payload. */
    private fun moov(mdatPayloadAt: Int): ByteArray {
        val offset = ByteArray(4) { ((mdatPayloadAt ushr (8 * (3 - it))) and 0xFF).toByte() }
        val stco = box("stco", ByteArray(4) + byteArrayOf(0, 0, 0, 1) + offset)
        return box("moov", box("mvhd", ByteArray(8)) + box("trak", box("mdia", box("minf", box("stbl", stco)))))
    }

    @Test
    fun mp4WithMoovLastGetsTheIdInItsExistingTags() {
        val ftyp = box("ftyp", "M4A ".toByteArray())
        val mdat = box("mdat", mdatPayload)
        val plain = ftyp + mdat + moov(ftyp.size + 8)
        val out = ByteArrayOutputStream()
        Mp4Tagger.tag(ByteArrayInputStream(plain), out, TrackTags("Song", "Band"))
        val tagged = TrackIdWriter.withTrackId(out.toByteArray(), id)!!

        assertEquals(id, FolderScan.trackIdIn(latin1(tagged)))
        assertTrue(latin1(tagged).contains("Song"))
        assertArrayEquals(mdatPayload, tagged.copyOfRange(ftyp.size + 8, ftyp.size + 8 + mdatPayload.size))
        val moovAt = ftyp.size + mdat.size
        assertEquals("moov runs to the end of the file", tagged.size - moovAt, be32(tagged, moovAt))
    }

    @Test
    fun mp4WithMoovFirstMovesTheChunkOffsetsWithTheAudio() {
        val ftyp = box("ftyp", "M4A ".toByteArray())
        val moovSize = moov(0).size
        val payloadAt = ftyp.size + moovSize + 8
        val original = ftyp + moov(payloadAt) + box("mdat", mdatPayload)
        val tagged = TrackIdWriter.withTrackId(original, id)!!

        assertEquals(id, FolderScan.trackIdIn(latin1(tagged)))
        val stcoAt = latin1(tagged).indexOf("stco") - 4
        val newOffset = be32(tagged, stcoAt + 16)
        assertEquals("the offset still points at the audio", mdatPayload.toList(), tagged.copyOfRange(newOffset, newOffset + mdatPayload.size).toList())
    }

    /** A box with a 64-bit size field, as Samsung's recorder writes mdat. */
    private fun largeBox(type: String, payload: ByteArray): ByteArray {
        val size = 16L + payload.size
        return byteArrayOf(0, 0, 0, 1) + type.toByteArray(Charsets.ISO_8859_1) +
            ByteArray(8) { ((size ushr (8 * (7 - it))) and 0xFF).toByte() } + payload
    }

    /**
     * The layout of the files on the phone that were all left untagged (#944): a 64-bit mdat, and Samsung's
     * own udta before the one that holds the tags.
     */
    @Test
    fun mp4WithALargeMdatAndARecorderUdtaGetsTheIdInTheTags() {
        val ftyp = box("ftyp", "M4A ".toByteArray())
        val mdat = largeBox("mdat", mdatPayload)
        val samsung = box("udta", box("SDLN", ByteArray(8)) + box("smrd", ByteArray(8)))
        val ilst = box("ilst", box("©nam", box("data", ByteArray(8) + "Song".toByteArray())))
        val tagsUdta = box("udta", box("meta", ByteArray(4) + ilst))
        val moovPayload = box("mvhd", ByteArray(8)) + samsung + tagsUdta
        val original = ftyp + mdat + box("moov", moovPayload)
        val tagged = TrackIdWriter.withTrackId(original, id)!!

        assertEquals(id, FolderScan.trackIdIn(latin1(tagged)))
        val text = latin1(tagged)
        assertTrue("the id sits after the title, in the same ilst", text.indexOf("Song") < text.indexOf(TrackTags.TRACK_ID_KEY))
        assertArrayEquals("Samsung's udta is untouched", samsung, tagged.copyOfRange(text.indexOf("SDLN") - 12, text.indexOf("SDLN") - 12 + samsung.size))
        val moovAt = ftyp.size + mdat.size
        assertEquals("moov runs to the end of the file", tagged.size - moovAt, be32(tagged, moovAt))
        assertArrayEquals(mdatPayload, tagged.copyOfRange(ftyp.size + 16, ftyp.size + 16 + mdatPayload.size))
    }

    @Test
    fun anUnknownFormatIsLeftAlone() {
        assertNull(TrackIdWriter.withTrackId("ID3".toByteArray() + ByteArray(100), id))
        assertNotNull(TrackIdWriter.withTrackId(ogg(), id))
    }
}
