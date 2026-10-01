package ch.snepilatch.app.logic.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes

/**
 * The quality chip's text (#959), from the format ExoPlayer actually plays, so it is the same for a
 * stream and a download: bit depth and sample rate for lossless audio ("FLAC 16/44.1"), the bitrate for
 * lossy ("AAC 256 kbps"), and the codec alone when the file does not say.
 */
object AudioQuality {

    /** [measuredKbps] stands in when the header has no bitrate: a download's measured one ([PayloadBitrate]). */
    fun label(format: Format?, measuredKbps: Int? = null): String? {
        val mime = format?.sampleMimeType ?: return null
        val codec = CODECS[mime] ?: return null
        if (mime in LOSSLESS) {
            val bits = bitsOf(format.pcmEncoding)
            val khz = format.sampleRate.takeIf { it != Format.NO_VALUE }?.let { kiloHertz(it) }
            return when {
                bits != null && khz != null -> "$codec $bits/$khz"
                khz != null -> "$codec $khz kHz"
                else -> codec
            }
        }
        val kbps = listOf(format.averageBitrate, format.bitrate, format.peakBitrate).firstOrNull { it > 0 }
            ?.let { (it + 500) / 1000 } ?: measuredKbps
        return if (kbps != null) "$codec $kbps kbps" else codec
    }

    private val CODECS = mapOf(
        MimeTypes.AUDIO_FLAC to "FLAC",
        MimeTypes.AUDIO_ALAC to "ALAC",
        MimeTypes.AUDIO_RAW to "PCM",
        MimeTypes.AUDIO_WAV to "WAV",
        MimeTypes.AUDIO_AAC to "AAC",
        MimeTypes.AUDIO_OPUS to "Opus",
        MimeTypes.AUDIO_VORBIS to "Vorbis",
        MimeTypes.AUDIO_MPEG to "MP3",
        MimeTypes.AUDIO_E_AC3 to "E-AC-3",
        MimeTypes.AUDIO_AC3 to "AC-3",
    )

    private val LOSSLESS = setOf(MimeTypes.AUDIO_FLAC, MimeTypes.AUDIO_ALAC, MimeTypes.AUDIO_RAW, MimeTypes.AUDIO_WAV)

    private fun bitsOf(encoding: Int): Int? = when (encoding) {
        C.ENCODING_PCM_8BIT -> 8
        C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> 16
        C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 24
        C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN, C.ENCODING_PCM_FLOAT -> 32
        else -> null
    }

    /** 44100 as "44.1", 48000 as "48". */
    private fun kiloHertz(hz: Int): String =
        if (hz % 1000 == 0) "${hz / 1000}" else "%.1f".format(java.util.Locale.ROOT, hz / 1000.0)
}
