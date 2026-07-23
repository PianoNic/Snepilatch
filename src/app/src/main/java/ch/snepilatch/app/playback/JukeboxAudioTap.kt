package ch.snepilatch.app.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import ch.snepilatch.app.util.LokiLogger
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Taps the DECODED PCM in ExoPlayer's audio pipeline (proven readable despite Widevine DRM) and, while
 * [analyzing] is on, accumulates the track as interleaved stereo samples — the raw material for both
 * [WaveformAnalyzer] (mono downmix) and the seamless [PcmJukeboxEngine] (stereo playback). No separate
 * download: this is the same audio being decoded for playback. Pure pass-through; playback is untouched.
 */
class JukeboxAudioTap : BaseAudioProcessor() {

    @Volatile var analyzing: Boolean = false

    private var sampleRate = 0
    private var channels = 0
    private var pcm16 = false

    // Rolling interleaved capture of the current track (cap ~5.5 min so memory is bounded).
    private var pcm = ShortArray(0)
    private var len = 0 // number of shorts written (interleaved)
    private var capturedRate = 0
    private var capturedChannels = 0

    fun sampleRate(): Int = capturedRate
    fun channelCount(): Int = capturedChannels

    /** Frames captured so far (a frame = one sample per channel). */
    fun capturedFrames(): Int = if (capturedChannels > 0) len / capturedChannels else 0

    /** Interleaved stereo (or mono) PCM captured so far — for the playback engine. */
    @Synchronized
    fun snapshotInterleaved(): ShortArray = pcm.copyOf(len)

    /** Mono downmix of the capture — for the analyzer. */
    @Synchronized
    fun snapshotMono(): ShortArray {
        val ch = capturedChannels
        if (ch <= 1) return pcm.copyOf(len)
        val frames = len / ch
        val out = ShortArray(frames)
        var i = 0
        var f = 0
        while (f < frames) {
            var s = 0
            repeat(ch) { s += pcm[i++] }
            out[f++] = (s / ch).toShort()
        }
        return out
    }

    /** Allocate the (up to ~63MB) capture buffer once the format is known. Cheap no-op if already sized. */
    @Synchronized
    private fun ensureBuffer() {
        val needed = capturedRate * capturedChannels * 60 * MAX_MINUTES
        if (pcm.size < needed && capturedRate > 0 && capturedChannels > 0) pcm = ShortArray(needed)
    }

    @Synchronized
    fun resetCapture() {
        ensureBuffer()
        len = 0
    }

    /** Release the ~63MB capture buffer when analysis stops, instead of holding it for the sink lifetime. */
    @Synchronized
    fun releaseBuffer() {
        pcm = ShortArray(0)
        len = 0
    }

    // Only participate in the audio chain while analyzing: when the jukebox is off ExoPlayer bypasses
    // this processor entirely, so there is no per-buffer queueInput copy. isActive is re-read on a
    // pipeline flush — the enable path sets analyzing before seeking, so the seek's flush activates it.
    override fun isActive(): Boolean = analyzing

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        sampleRate = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount
        pcm16 = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT
        capturedRate = sampleRate
        capturedChannels = channels
        // Allocate only if analysis is already running (e.g. capturing across a format change);
        // otherwise stay unallocated until resetCapture() on the next enable.
        if (analyzing) ensureBuffer()
        LokiLogger.i(TAG, "tap configured: ${sampleRate}Hz ch=$channels enc=${inputAudioFormat.encoding} pcm16=$pcm16")
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val canCapture = analyzing && pcm16
        if (canCapture && pcm.isNotEmpty()) {
            capture(inputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN))
        }

        // Pure pass-through.
        val out = replaceOutputBuffer(remaining)
        out.put(inputBuffer)
        out.flip()
    }

    @Synchronized
    private fun capture(buf: ByteBuffer) {
        // Bulk copy instead of per-sample buf.short: asShortBuffer() inherits the duplicate's
        // LITTLE_ENDIAN order; count is clamped to the remaining capacity to keep the overflow guard.
        val count = minOf(buf.remaining() / 2, pcm.size - len)
        if (count > 0) {
            buf.asShortBuffer().get(pcm, len, count)
            len += count
        }
    }

    private companion object {
        const val TAG = "JukeboxTap"
        const val MAX_MINUTES = 6
    }
}
