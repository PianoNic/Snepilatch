package ch.snepilatch.app.logic.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Issue #1038: the file fixer goes by a file's first bytes, not its name. */
class FileFixerTest {

    private fun bytes(text: String, size: Int = 64) = text.toByteArray(Charsets.ISO_8859_1).copyOf(size)

    @Test
    fun eachFormatIsToldByItsFirstBytes() {
        assertEquals("flac", FolderScan.formatOf(bytes("fLaC")))
        assertEquals("m4a", FolderScan.formatOf(bytes("\u0000\u0000\u0000\u001Cftypiso8")))
        assertEquals("opus", FolderScan.formatOf(bytes("OggS" + "\u0000".repeat(24) + "OpusHead")))
        assertEquals("ogg", FolderScan.formatOf(bytes("OggS" + "\u0000".repeat(24) + "\u0001vorbis")))
        assertEquals("mp3", FolderScan.formatOf(bytes("ID3")))
        assertEquals("mp3", FolderScan.formatOf(byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x64)))
    }

    @Test
    fun anEmptyOrUnknownFileIsNoFormat() {
        assertNull(FolderScan.formatOf(ByteArray(0)))
        assertNull(FolderScan.formatOf(bytes("<html><body>error")))
    }
}
