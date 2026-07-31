package ch.snepilatch.app.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * A track the playback cache holds all but the tail of used to fail the whole remux: the reader threw
 * on the half-written last element, [OpusRemuxer] reported failure, and the download fell back to
 * keeping the untagged WebM. Every packet the reader did hand over was whole, so the right answer is
 * to close a valid Ogg around them and accept a file that is a moment short.
 */
class TruncatedRemuxTest {

    private val tags = TrackTags(title = "Song", artist = "Band")

    /** CodecPrivate for a stereo 48kHz Opus track with a 312-sample pre-skip. */
    private val opusHead = byteArrayOf(
        0x4F, 0x70, 0x75, 0x73, 0x48, 0x65, 0x61, 0x64,
        1, 2,
        0x38, 0x01,
        0x80.toByte(), 0xBB.toByte(), 0, 0,
        0, 0,
        0,
    )

    /** One fullband 20ms Opus packet. */
    private fun packet(marker: Byte) = byteArrayOf(0xF8.toByte(), marker, marker)

    private fun ebml(id: ByteArray, payload: ByteArray) = id + vint(payload.size.toLong()) + payload

    /** Minimal EBML length: one byte while it fits, else two. */
    private fun vint(value: Long): ByteArray = if (value < 0x7F) {
        byteArrayOf((0x80 or value.toInt()).toByte())
    } else {
        byteArrayOf((0x40 or (value shr 8).toInt()).toByte(), (value and 0xFF).toInt().toByte())
    }

    private fun simpleBlock(payload: ByteArray): ByteArray =
        ebml(byteArrayOf(0xA3.toByte()), byteArrayOf(0x81.toByte(), 0, 0, 0x80.toByte()) + payload)

    /**
     * Segment -> Tracks -> TrackEntry(TrackNumber, CodecID, CodecPrivate), then loose SimpleBlocks.
     * The reader descends into master elements, so nesting only needs to be well formed.
     */
    private fun webm(blocks: Int): ByteArray {
        val entryChildren = ebml(byteArrayOf(0xD7.toByte()), byteArrayOf(1)) +
            ebml(byteArrayOf(0x86.toByte()), "A_OPUS".toByteArray(Charsets.US_ASCII)) +
            ebml(byteArrayOf(0x63, 0xA2.toByte()), opusHead)
        val trackEntry = ebml(byteArrayOf(0xAE.toByte()), entryChildren)
        val tracks = ebml(byteArrayOf(0x16, 0x54, 0xAE.toByte(), 0x6B), trackEntry)
        var body = tracks
        repeat(blocks) { body += simpleBlock(packet(it.toByte())) }
        return ebml(byteArrayOf(0x18, 0x53, 0x80.toByte(), 0x67), body)
    }

    private fun remux(input: ByteArray): Pair<Boolean, ByteArray> {
        val out = ByteArrayOutputStream()
        val ok = OpusRemuxer.remux(ByteArrayInputStream(input), out, tags, serial = 7)
        return ok to out.toByteArray()
    }

    /** Offsets of every "OggS" capture pattern. */
    private fun pageOffsets(bytes: ByteArray): List<Int> =
        (0..bytes.size - 4).filter { isCapture(bytes, it) }

    private fun isCapture(b: ByteArray, at: Int): Boolean =
        b[at] == 0x4F.toByte() && b[at + 1] == 0x67.toByte() && b[at + 2] == 0x67.toByte() &&
            b[at + 3] == 0x53.toByte()

    private fun pageCount(bytes: ByteArray): Int = pageOffsets(bytes).size

    @Test
    fun `a whole webm remuxes`() {
        val (ok, ogg) = remux(webm(blocks = 6))
        assertTrue(ok)
        assertTrue(pageCount(ogg) >= 2)
        assertTrue(String(ogg, Charsets.ISO_8859_1).contains("OpusHead"))
        assertTrue(String(ogg, Charsets.ISO_8859_1).contains("Song"))
    }

    @Test
    fun `a webm cut off mid-block still yields a playable ogg of what arrived`() {
        val whole = webm(blocks = 6)
        // Lop off the tail so the final SimpleBlock is half written.
        val truncated = whole.copyOfRange(0, whole.size - 4)

        val (ok, ogg) = remux(truncated)

        assertTrue("a truncated tail must not fail the whole remux", ok)
        assertTrue(String(ogg, Charsets.ISO_8859_1).contains("OpusHead"))
        assertTrue(String(ogg, Charsets.ISO_8859_1).contains("Song"))
        // Shorter than the whole file, but a real stream rather than nothing.
        assertTrue(ogg.isNotEmpty())
        assertTrue(pageCount(ogg) >= 2)
    }

    @Test
    fun `truncation before the first block is still a failure`() {
        // Cut inside the header, so no packet was ever handed over.
        val whole = webm(blocks = 6)
        val headerOnly = whole.copyOfRange(0, 12)
        assertFalse(remux(headerOnly).first)
    }

    @Test
    fun `the last page is flagged end-of-stream so the length is not left open`() {
        val whole = webm(blocks = 6)
        val (_, ogg) = remux(whole.copyOfRange(0, whole.size - 4))
        val last = pageOffsets(ogg).last()
        assertEquals(0x04, ogg[last + 5].toInt() and 0x04)
    }
}
