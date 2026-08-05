package ch.snepilatch.app.download

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import ch.snepilatch.app.util.LokiLogger
import java.io.File
import java.nio.ByteOrder

/**
 * Encodes captured PCM into AAC in an .m4a, using the platform codec and muxer.
 *
 * This is what makes saving the actual Spfy recording possible: the stream is Widevine, so its
 * encoded bytes can never be written out, but the decoded samples passing through the audio chain
 * can. It re-encodes, so it is lossy on top of a lossy source — the alternative it replaces is a
 * second download of somebody else's upload of the same song, which is a different recording.
 */
internal object PcmAacEncoder {

    private const val TAG = "PcmAacEncoder"

    /** Comfortably above the 128 kbit/s source, so the second encode costs little. */
    private const val BIT_RATE = 192_000
    private const val MAX_INPUT_BYTES = 64 * 1024
    private const val TIMEOUT_US = 10_000L

    /** Encodes the first [count] samples of [pcm]; the rest is spare capture buffer, not audio. */
    fun encode(pcm: ShortArray, count: Int, sampleRate: Int, channels: Int, target: File): Boolean {
        val hasAudio = count > 0 && count <= pcm.size
        val hasFormat = sampleRate > 0 && channels > 0
        if (!hasAudio || !hasFormat) return false
        return try {
            run(pcm, count, sampleRate, channels, target)
            true
        } catch (e: Exception) {
            // A codec failure must not fail the download: the caller falls back to fetching.
            LokiLogger.e(TAG, "encode failed: ${e.message}", e)
            target.delete()
            false
        }
    }

    private fun run(pcm: ShortArray, count: Int, sampleRate: Int, channels: Int, target: File) {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_BYTES)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val muxer = MediaMuxer(target.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var muxing = false
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var fed = 0 // shorts handed to the encoder so far
            var inputDone = false
            while (true) {
                if (!inputDone) {
                    // Non-blocking, unlike the output side. One 64KB input buffer is about 0.37s of
                    // audio and comes back as ~16 AAC packets, so the input queue is full almost
                    // immediately and only a drained packet frees a slot. Waiting here waited for
                    // something only the drain below can cause: every packet cost a full timeout,
                    // and a three minute track took 87 seconds to encode while the CPU sat idle.
                    // The output dequeue keeps its timeout — that one waits on real work, and
                    // dropping it too would turn an empty pipeline into a busy-wait.
                    val index = codec.dequeueInputBuffer(0)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index)!!.also { it.clear() }
                        val shorts = minOf(buffer.remaining() / 2, count - fed)
                        // Derived from the frames already consumed rather than accumulated per buffer,
                        // so the timestamps stay exact instead of drifting with the codec's buffer sizes.
                        val timeUs = 1_000_000L * (fed / channels) / sampleRate
                        if (shorts > 0) {
                            buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm, fed, shorts)
                            fed += shorts
                        }
                        inputDone = fed >= count
                        val flags = if (inputDone) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                        codec.queueInputBuffer(index, 0, shorts * 2, timeUs, flags)
                    }
                }
                val out = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxing = true
                } else if (out >= 0) {
                    val encoded = codec.getOutputBuffer(out)!!
                    // CODEC_CONFIG is the AAC header, which the muxer already took from the format.
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (muxing && !isConfig && info.size > 0) muxer.writeSampleData(track, encoded, info)
                    codec.releaseOutputBuffer(out, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            // stop() throws if no sample ever landed, which is why it is guarded rather than assumed.
            if (muxing) runCatching { muxer.stop() }
            muxer.release()
        }
    }
}
