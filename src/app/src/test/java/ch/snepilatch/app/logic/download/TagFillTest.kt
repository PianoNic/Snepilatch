package ch.snepilatch.app.logic.download

import ch.snepilatch.app.logic.download.TrackIdWriter.Field
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Filling in the tags a file lacks while migrating (#946). What the file already says is never replaced,
 * and the audio comes through byte for byte.
 */
class TagFillTest {

    private val id = "5u7QargOKXO82nXcAU9s8L"
    private val cover = TrackTags.Cover(ByteArray(600) { (it * 3).toByte() }, "image/jpeg")
    private val spfy = TrackTags("Spfy Title", "Spfy Artist", album = "Spfy Album", cover = cover, trackId = id)

    private fun latin1(b: ByteArray) = String(b, Charsets.ISO_8859_1)

    private fun occurrences(b: ByteArray, text: String) = latin1(b).windowed(text.length).count { it == text }

    @Test
    fun opusGetsTheAlbumAndCoverButKeepsItsOwnTitle() {
        val out = ByteArrayOutputStream()
        val writer = OggOpusWriter(out, serial = 7, preSkip = 312)
        val head = "OpusHead".toByteArray() + byteArrayOf(1, 2, 0x38, 0x01, 0x80.toByte(), 0xBB.toByte(), 0, 0, 0, 0, 0)
        writer.writeHeaders(head, VorbisComments.opusTags(TrackTags("Biertornado", "PA69")))
        repeat(50) { i -> writer.add(ByteArray(200) { (i + it).toByte() }.also { it[0] = 0xF8.toByte() }) }
        writer.finish()
        val original = out.toByteArray()

        assertEquals(setOf(Field.ALBUM, Field.COVER), TrackIdWriter.missing(original))
        val filled = TrackIdWriter.withTags(original, spfy)!!

        assertTrue(latin1(filled).contains("TITLE=Biertornado"))
        assertFalse(latin1(filled).contains("Spfy Title"))
        assertTrue(latin1(filled).contains("ALBUM=Spfy Album"))
        assertEquals(id, FolderScan.trackIdIn(latin1(filled)))
        assertEquals(emptySet<Field>(), TrackIdWriter.missing(filled))
    }

    @Test
    fun flacGetsAPictureBlockAndTheAlbum() {
        val frames = ByteArray(4096) { (it * 31).toByte() }
        val plain = "fLaC".toByteArray() + byteArrayOf(0x80.toByte(), 0, 0, 34) + ByteArray(34) { it.toByte() } + frames
        val out = ByteArrayOutputStream()
        FlacTagger.tag(ByteArrayInputStream(plain), out, TrackTags("Song", "Band"))
        val original = out.toByteArray()

        assertEquals(setOf(Field.ALBUM, Field.COVER), TrackIdWriter.missing(original))
        val filled = TrackIdWriter.withTags(original, spfy)!!

        assertTrue(latin1(filled).contains("TITLE=Song"))
        assertTrue(latin1(filled).contains("ALBUM=Spfy Album"))
        assertEquals(emptySet<Field>(), TrackIdWriter.missing(filled))
        assertArrayEquals(frames, filled.copyOfRange(filled.size - frames.size, filled.size))
    }

    private fun box(type: String, payload: ByteArray): ByteArray {
        val size = 8 + payload.size
        return ByteArray(4) { ((size ushr (8 * (3 - it))) and 0xFF).toByte() } + type.toByteArray(Charsets.ISO_8859_1) + payload
    }

    @Test
    fun mp4GetsTheAtomsItLacksAndNoSecondTitle() {
        val payload = ByteArray(256) { (it * 13).toByte() }
        val ftyp = box("ftyp", "M4A ".toByteArray())
        val plain = ftyp + box("mdat", payload) + box("moov", box("mvhd", ByteArray(8)))
        val out = ByteArrayOutputStream()
        Mp4Tagger.tag(ByteArrayInputStream(plain), out, TrackTags("Song", "Band"))
        val original = out.toByteArray()

        assertEquals(setOf(Field.ALBUM, Field.COVER), TrackIdWriter.missing(original))
        val filled = TrackIdWriter.withTags(original, spfy)!!

        assertEquals(1, occurrences(filled, "©nam"))
        assertTrue(latin1(filled).contains("Spfy Album"))
        assertEquals(id, FolderScan.trackIdIn(latin1(filled)))
        assertEquals(emptySet<Field>(), TrackIdWriter.missing(filled))
        assertArrayEquals(payload, filled.copyOfRange(ftyp.size + 8, ftyp.size + 8 + payload.size))
    }

    @Test
    fun anMp4WithEverythingIsLeftAsItIs() {
        val ftyp = box("ftyp", "M4A ".toByteArray())
        val plain = ftyp + box("mdat", ByteArray(64)) + box("moov", box("mvhd", ByteArray(8)))
        val out = ByteArrayOutputStream()
        Mp4Tagger.tag(ByteArrayInputStream(plain), out, spfy)
        val full = out.toByteArray()

        assertArrayEquals(full, TrackIdWriter.withTags(full, TrackTags("Other", "Other", album = "Other", cover = cover)))
    }

    // --- MP3 -------------------------------------------------------------------------------------

    /** MPEG frames: the sync word, then bytes that must come through untouched. */
    private val mpeg = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x64) +
        ByteArray(2000) { (it * 7).toByte() }

    @Test
    fun anUntaggedMp3GetsAnId3TagInFrontOfItsAudio() {
        assertEquals(Field.entries.toSet(), TrackIdWriter.missing(mpeg))
        val filled = TrackIdWriter.withTags(mpeg, spfy)!!

        assertEquals("ID3", latin1(filled).take(3))
        assertEquals(id, FolderScan.trackIdIn(latin1(filled)))
        assertTrue("the id comes before the cover", latin1(filled).indexOf("TXXX") < latin1(filled).indexOf("APIC"))
        assertArrayEquals(mpeg, filled.copyOfRange(filled.size - mpeg.size, filled.size))
        assertEquals(emptySet<Field>(), TrackIdWriter.missing(filled))
    }

    /** An ID3v2.3 tag with one text frame, as other taggers write it: UTF-16 with a byte order mark. */
    private fun id3v23(title: String, flags: Int = 0): ByteArray {
        val text = byteArrayOf(1, 0xFF.toByte(), 0xFE.toByte()) + title.toByteArray(Charsets.UTF_16LE)
        val frame = "TIT2".toByteArray() + ByteArray(4) { ((text.size ushr (8 * (3 - it))) and 0xFF).toByte() } +
            ByteArray(2) + text
        val padding = ByteArray(32)
        val size = frame.size + padding.size
        val header = "ID3".toByteArray() + byteArrayOf(3, 0, flags.toByte()) +
            ByteArray(4) { ((size ushr (7 * (3 - it))) and 0x7F).toByte() }
        return header + frame + padding
    }

    @Test
    fun anMp3KeepsItsTitleAndGetsTheRest() {
        val original = id3v23("Eigener Titel") + mpeg

        assertEquals(setOf(Field.ARTIST, Field.ALBUM, Field.COVER), TrackIdWriter.missing(original))
        val filled = TrackIdWriter.withTags(original, spfy)!!

        assertEquals(1, occurrences(filled, "TIT2"))
        val utf16 = { text: String -> latin1(text.toByteArray(Charsets.UTF_16LE)) }
        assertTrue(latin1(filled).contains(utf16("Eigener Titel")))
        assertFalse(latin1(filled).contains(utf16("Spfy Title")))
        assertEquals(id, FolderScan.trackIdIn(latin1(filled)))
        assertEquals(emptySet<Field>(), TrackIdWriter.missing(filled))
        assertArrayEquals(mpeg, filled.copyOfRange(filled.size - mpeg.size, filled.size))
    }

    @Test
    fun anUnsynchronisedId3TagIsLeftAlone() {
        val original = id3v23("Titel", flags = 0x80) + mpeg

        assertNull(TrackIdWriter.missing(original))
        assertNull(TrackIdWriter.withTags(original, spfy))
    }
}
