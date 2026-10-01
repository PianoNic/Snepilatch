package ch.snepilatch.app.logic.playback

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * The real average bitrate of a downloaded file's audio, for when its header does not say (#959): YouTube's
 * fragmented m4a files carry 0 in `esds`, and Ogg Opus has no bitrate field at all. Only the audio counts,
 * never the cover or the container's own boxes and pages, so it comes out as ffprobe's stream bitrate does.
 */
object PayloadBitrate {

    /** The bitrate of the downloaded file at [document], measured from the file itself. */
    fun ofFile(ctx: android.content.Context, document: android.net.Uri, durationMs: Long): Int? = runCatching {
        ctx.contentResolver.openFileDescriptor(document, "r")?.use { fd ->
            java.io.FileInputStream(fd.fileDescriptor).channel.use { kbps(it, durationMs) }
        }
    }.getOrNull()

    /** Kilobits per second of audio, or null for a format this does not measure. */
    fun kbps(channel: FileChannel, durationMs: Long): Int? {
        if (durationMs <= 0) return null
        val head = read(channel, 0, 12) ?: return null
        val bytes = when {
            String(head, 4, 4, Charsets.ISO_8859_1) == "ftyp" -> mp4Audio(channel)
            String(head, 0, 4, Charsets.ISO_8859_1) == "OggS" -> oggAudio(channel)
            String(head, 0, 3, Charsets.ISO_8859_1) == "ID3" || head[0] == 0xFF.toByte() -> mp3Audio(channel, head)
            else -> null
        } ?: return null
        return ((bytes * 8 / durationMs.toDouble()) + 0.5).toInt().takeIf { it > 0 }
    }

    /** The payload of every `mdat`: one in a plain file, one per fragment in a fragmented one. */
    private fun mp4Audio(channel: FileChannel): Long? {
        var at = 0L
        var audio = 0L
        val size = channel.size()
        while (at + 8 <= size) {
            val h = read(channel, at, 16) ?: return null
            val buf = ByteBuffer.wrap(h)
            var boxSize = buf.getInt(0).toLong() and 0xFFFFFFFFL
            var header = 8L
            if (boxSize == 1L) {
                boxSize = buf.getLong(8)
                header = 16L
            } else if (boxSize == 0L) {
                boxSize = size - at
            }
            if (boxSize < header) return null
            if (String(h, 4, 4, Charsets.ISO_8859_1) == "mdat") audio += boxSize - header
            at += boxSize
        }
        return audio.takeIf { it > 0 }
    }

    /** The bodies of the audio pages: those with a granule position, which the header and tag pages lack. */
    private fun oggAudio(channel: FileChannel): Long? {
        var at = 0L
        var audio = 0L
        val size = channel.size()
        while (at + 27 <= size) {
            val h = read(channel, at, 27) ?: return null
            if (String(h, 0, 4, Charsets.ISO_8859_1) != "OggS") return null
            val granule = ByteBuffer.wrap(h, 6, 8).order(ByteOrder.LITTLE_ENDIAN).long
            val segments = h[26].toInt() and 0xFF
            val table = read(channel, at + 27, segments) ?: return null
            val body = table.sumOf { it.toInt() and 0xFF }.toLong()
            if (granule != 0L && granule != -1L) audio += body
            at += 27 + segments + body
        }
        return audio.takeIf { it > 0 }
    }

    /** The file without its ID3v2 tag in front and ID3v1 tag at the end. */
    private fun mp3Audio(channel: FileChannel, head: ByteArray): Long {
        val v2 = if (String(head, 0, 3, Charsets.ISO_8859_1) == "ID3") {
            // The tag size is synchsafe: four bytes of seven bits each, after a 10 byte header.
            10L + (6..9).fold(0L) { size, i -> (size shl 7) or (head[i].toLong() and 0x7F) }
        } else {
            0L
        }
        val size = channel.size()
        val v1 = read(channel, size - 128, 3)?.let { if (String(it, Charsets.ISO_8859_1) == "TAG") 128L else 0L } ?: 0L
        return size - v2 - v1
    }

    private fun read(channel: FileChannel, at: Long, count: Int): ByteArray? {
        if (at < 0 || count < 0) return null
        val buf = ByteBuffer.allocate(count)
        var pos = at
        while (buf.hasRemaining()) {
            val n = channel.read(buf, pos)
            if (n <= 0) break
            pos += n
        }
        return if (buf.hasRemaining()) null else buf.array()
    }
}
