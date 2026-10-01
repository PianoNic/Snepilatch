package ch.snepilatch.app.logic.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioQualityTest {

    private fun format(mime: String, build: Format.Builder.() -> Unit = {}) =
        Format.Builder().setSampleMimeType(mime).apply(build).build()

    private fun flac(encoding: Int, sampleRate: Int) = format(MimeTypes.AUDIO_FLAC) {
        setPcmEncoding(encoding)
        setSampleRate(sampleRate)
    }

    @Test
    fun losslessShowsBitDepthAndSampleRate() {
        assertEquals("FLAC 16/44.1", AudioQuality.label(flac(C.ENCODING_PCM_16BIT, 44_100)))
        assertEquals("FLAC 24/96", AudioQuality.label(flac(C.ENCODING_PCM_24BIT, 96_000)))
        assertEquals("FLAC 48 kHz", AudioQuality.label(format(MimeTypes.AUDIO_FLAC) { setSampleRate(48_000) }))
    }

    @Test
    fun lossyShowsTheBitrate() {
        assertEquals("AAC 256 kbps", AudioQuality.label(format(MimeTypes.AUDIO_AAC) { setAverageBitrate(256_000) }))
        assertEquals("MP3 320 kbps", AudioQuality.label(format(MimeTypes.AUDIO_MPEG) { setPeakBitrate(320_000) }))
        assertEquals("Opus 160 kbps", AudioQuality.label(format(MimeTypes.AUDIO_OPUS) { setAverageBitrate(159_700) }))
    }

    @Test
    fun withoutABitrateOnlyTheCodec() {
        assertEquals("Opus", AudioQuality.label(format(MimeTypes.AUDIO_OPUS)))
    }

    @Test
    fun nothingPlayingOrUnknownShowsNoChip() {
        assertNull(AudioQuality.label(null))
        assertNull(AudioQuality.label(format(MimeTypes.VIDEO_H264)))
    }
}
