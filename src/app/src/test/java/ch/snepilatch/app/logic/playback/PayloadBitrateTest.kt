package ch.snepilatch.app.logic.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

/** Only the audio is counted (#959): never the cover, the container's boxes or the header pages. */
class PayloadBitrateTest {

    private fun kbps(bytes: ByteArray, durationMs: Long): Int? {
        val f = File.createTempFile("payload", null)
        try {
            f.writeBytes(bytes)
            return RandomAccessFile(f, "r").channel.use { PayloadBitrate.kbps(it, durationMs) }
        } finally {
            f.delete()
        }
    }

    private fun box(type: String, payload: ByteArray): ByteArray {
        val size = 8 + payload.size
        return ByteArray(4) { ((size ushr (8 * (3 - it))) and 0xFF).toByte() } + type.toByteArray(Charsets.ISO_8859_1) + payload
    }

    @Test
    fun aFragmentedMp4CountsEveryMdatAndNothingElse() {
        // Two fragments of 8000 audio bytes, behind a moov holding a 50 kB cover: 16000 bytes over 1 s is 128 kbps.
        val file = box("ftyp", "M4A ".toByteArray()) + box("moov", ByteArray(50_000)) +
            box("moof", ByteArray(100)) + box("mdat", ByteArray(8_000)) +
            box("moof", ByteArray(100)) + box("mdat", ByteArray(8_000))

        assertEquals(128, kbps(file, 1_000))
    }

    private fun page(granule: Long, body: Int): ByteArray {
        val segments = (body + 254) / 255
        val lacing = ByteArray(segments) { i -> (if (i < segments - 1) 255 else body - 255 * (segments - 1)).toByte() }
        val header = ByteArray(27)
        "OggS".toByteArray().copyInto(header)
        for (i in 0 until 8) header[6 + i] = ((granule ushr (8 * i)) and 0xFF).toByte()
        header[26] = segments.toByte()
        return header + lacing + ByteArray(body)
    }

    @Test
    fun anOggCountsOnlyTheAudioPages() {
        // OpusHead and a tags page with a large cover carry no granule; 20000 audio bytes over 1 s is 160 kbps.
        val file = page(0, 19) + page(0, 60_000) + page(960, 10_000) + page(1920, 10_000)

        assertEquals(160, kbps(file, 1_000))
    }

    @Test
    fun anMp3LeavesOutBothTags() {
        val id3 = "ID3".toByteArray() + byteArrayOf(3, 0, 0, 0, 0, 0x7F, 0x76) // a 16374 byte tag
        val audio = byteArrayOf(0xFF.toByte(), 0xFB.toByte()) + ByteArray(39_998)
        val v1 = "TAG".toByteArray() + ByteArray(125)

        assertEquals(320, kbps(id3 + ByteArray(16_374) + audio + v1, 1_000))
    }

    @Test
    fun anUnknownFormatOrNoLengthIsNotMeasured() {
        assertNull(kbps("RIFF....WAVE".toByteArray() + ByteArray(100), 1_000))
        assertNull(kbps(box("ftyp", "M4A ".toByteArray()) + box("mdat", ByteArray(100)), 0))
    }
}
