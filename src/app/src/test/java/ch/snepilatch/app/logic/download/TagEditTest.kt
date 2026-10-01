package ch.snepilatch.app.logic.download

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Tagging from the start of a file only (#953): a large FLAC or MP3 must come out exactly as if the whole
 * file had been rewritten in memory, while only its head is ever read.
 */
class TagEditTest {

    private val id = "5u7QargOKXO82nXcAU9s8L"
    private val cover = TrackTags.Cover(ByteArray(500) { 7 }, "image/jpeg")
    private val tags = TrackTags("Song", "Band", album = "Album", cover = cover, trackId = id)

    /** The file the edit describes: its prefix, then everything of [file] after the bytes it replaces. */
    private fun applied(edit: TrackIdWriter.Edit, file: ByteArray) = edit.prefix + file.copyOfRange(edit.replaced, file.size)

    private fun flac(frames: ByteArray): ByteArray {
        val plain = "fLaC".toByteArray() + byteArrayOf(0x80.toByte(), 0, 0, 34) + ByteArray(34) { it.toByte() } + frames
        val out = ByteArrayOutputStream()
        FlacTagger.tag(ByteArrayInputStream(plain), out, TrackTags("Song", "Band"))
        return out.toByteArray()
    }

    @Test
    fun aFlacTaggedFromItsHeadMatchesOneRewrittenWhole() {
        val file = flac(ByteArray(200_000) { (it * 31).toByte() })
        val head = file.copyOf(4_096)

        val edit = TrackIdWriter.edit(head, whole = false, tags)!!

        assertArrayEquals(TrackIdWriter.withTags(file, tags), applied(edit, file))
        assertTrue(edit.changes(head))
    }

    @Test
    fun anMp3TaggedFromItsHeadMatchesOneRewrittenWhole() {
        val file = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x64) + ByteArray(200_000) { (it * 7).toByte() }
        val head = file.copyOf(4_096)

        val edit = TrackIdWriter.edit(head, whole = false, tags)!!

        assertEquals(0, edit.replaced)
        assertArrayEquals(TrackIdWriter.withTags(file, tags), applied(edit, file))
    }

    @Test
    fun aHeadThatEndsInsideTheTagsIsNotGuessedAt() {
        val file = flac(ByteArray(1_000))

        assertNull(TrackIdWriter.edit(file.copyOf(20), whole = false, tags))
    }

    @Test
    fun formatsWithTagsAtTheEndNeedTheWholeFile() {
        val ftyp = byteArrayOf(0, 0, 0, 12) + "ftypM4A ".toByteArray()
        val file = ftyp + byteArrayOf(0, 0, 0, 8) + "moov".toByteArray()

        assertFalse(TrackIdWriter.tagsInFront(file))
        assertNull(TrackIdWriter.edit(file, whole = false, tags))
        assertEquals(file.size, TrackIdWriter.edit(file, whole = true, tags)!!.replaced)
    }

    @Test
    fun aFileThatAlreadyHasEverythingIsNoChange() {
        val file = flac(ByteArray(1_000))
        val full = TrackIdWriter.withTags(file, tags)!!

        assertFalse(TrackIdWriter.edit(full, whole = true, TrackTags("", ""))!!.changes(full))
    }
}
