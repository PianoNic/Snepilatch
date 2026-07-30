package ch.snepilatch.app.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * The muxer is hand-rolled, so these pin the parts a bad page would break silently: packet durations
 * (wrong granule positions make players report the wrong length and seek badly) and the page
 * framing itself.
 */
class OggOpusWriterTest {

    // "OpusHead", version 1, 2 channels, pre-skip 312, 48kHz, gain 0, mapping 0.
    private val opusHead = byteArrayOf(
        0x4F, 0x70, 0x75, 0x73, 0x48, 0x65, 0x61, 0x64,
        1, 2,
        0x38, 0x01,
        0x80.toByte(), 0xBB.toByte(), 0, 0,
        0, 0,
        0,
    )

    @Test
    fun packetDurationsFollowTheTocConfiguration() {
        // config 0 is SILK narrowband at 10ms, so 480 samples at 48kHz.
        assertEquals(480L, OpusPacket.samples(byteArrayOf(0x00), 48_000))
        // config 1, 20ms.
        assertEquals(960L, OpusPacket.samples(byteArrayOf(0x08), 48_000))
        // config 3, 60ms.
        assertEquals(2880L, OpusPacket.samples(byteArrayOf(0x18), 48_000))
        // config 31 is CELT fullband at 20ms, which is what YouTube ships.
        assertEquals(960L, OpusPacket.samples(byteArrayOf(0xF8.toByte()), 48_000))
        // config 16, CELT narrowband at 2.5ms.
        assertEquals(120L, OpusPacket.samples(byteArrayOf(0x80.toByte()), 48_000))
    }

    @Test
    fun theFrameCountCodeMultipliesTheDuration() {
        // Code 1 and 2 both mean two frames in the packet.
        assertEquals(1920L, OpusPacket.samples(byteArrayOf(0x09), 48_000))
        assertEquals(1920L, OpusPacket.samples(byteArrayOf(0x0A), 48_000))
        // Code 3 carries the count in the following byte.
        assertEquals(2880L, OpusPacket.samples(byteArrayOf(0x0B, 0x03), 48_000))
        assertEquals(0L, OpusPacket.samples(byteArrayOf(), 48_000))
    }

    @Test
    fun headerPagesAreFramedAndFlagged() {
        val out = ByteArrayOutputStream()
        OggOpusWriter(out, serial = 42, preSkip = 312)
            .writeHeaders(opusHead, VorbisComments.opusTags(TrackTags("T", "A")))
        val bytes = out.toByteArray()

        assertEquals("OggS", String(bytes.copyOfRange(0, 4)))
        assertEquals("first page must carry the beginning-of-stream flag", 0x02, bytes[5].toInt())
        assertEquals("header pages sit at granule 0", 0L, readLe64(bytes, 6))
        assertEquals(42, readLe32(bytes, 14))
        assertEquals("first page is sequence 0", 0, readLe32(bytes, 18))
        assertTrue("the comment page must follow", String(bytes).indexOf("OggS", 4) > 0)
        assertTrue("OpusTags must be present", String(bytes).contains("OpusTags"))
    }

    @Test
    fun packetsLongerThan255BytesAreSplitAcrossLacingValues() {
        val out = ByteArrayOutputStream()
        val writer = OggOpusWriter(out, serial = 1, preSkip = 0)
        writer.writeHeaders(opusHead, VorbisComments.opusTags(TrackTags("T", "A")))
        val header = out.size()

        // 600 bytes needs lacing 255, 255, 90 — the trailing value under 255 ends the packet.
        writer.add(ByteArray(600) { 0xF8.toByte() })
        writer.finish()

        val page = out.toByteArray().copyOfRange(header, out.size())
        val segmentCount = page[26].toInt() and 0xFF
        assertEquals(3, segmentCount)
        assertEquals(255, page[27].toInt() and 0xFF)
        assertEquals(255, page[28].toInt() and 0xFF)
        assertEquals(90, page[29].toInt() and 0xFF)
        assertEquals("last page must carry the end-of-stream flag", 0x04, page[5].toInt())
    }

    @Test
    fun granuleAdvancesByDecodedSamplesPlusPreSkip() {
        val out = ByteArrayOutputStream()
        val writer = OggOpusWriter(out, serial = 7, preSkip = 312)
        writer.writeHeaders(opusHead, VorbisComments.opusTags(TrackTags("T", "A")))
        val header = out.size()

        // Three fullband 20ms packets: 2880 samples, plus the pre-skip the decoder discards.
        repeat(3) { writer.add(byteArrayOf(0xF8.toByte(), 0, 0)) }
        writer.finish()

        val page = out.toByteArray().copyOfRange(header, out.size())
        assertEquals(3 * 960L + 312L, readLe64(page, 6))
    }

    @Test
    fun theChecksumIsNotLeftZero() {
        val out = ByteArrayOutputStream()
        OggOpusWriter(out, serial = 3, preSkip = 0)
            .writeHeaders(opusHead, VorbisComments.opusTags(TrackTags("T", "A")))
        assertTrue("a zero checksum means the page was never summed", readLe32(out.toByteArray(), 22) != 0)
    }

    private fun readLe32(b: ByteArray, at: Int): Int =
        (0 until 4).sumOf { (b[at + it].toInt() and 0xFF) shl (8 * it) }

    private fun readLe64(b: ByteArray, at: Int): Long =
        (0 until 8).sumOf { ((b[at + it].toLong() and 0xFF) shl (8 * it)) }
}
