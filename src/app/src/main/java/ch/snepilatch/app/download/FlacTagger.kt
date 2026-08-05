package ch.snepilatch.app.download

import ch.snepilatch.app.util.LokiLogger
import java.io.InputStream
import java.io.OutputStream

/**
 * Rewrites a FLAC file's metadata blocks with our tags and cover, copying the audio untouched.
 *
 * FLAC needs no offset fixups: frames are self-contained, so metadata blocks can be replaced without
 * rewriting anything after them. The comment payload is the same [VorbisComments] block Ogg Opus
 * uses, which is the whole reason downloads rehouse Opus into Ogg.
 */
internal object FlacTagger {

    private const val TAG = "FlacTagger"
    private const val TYPE_VORBIS_COMMENT = 4
    private const val TYPE_PICTURE = 6
    private const val LAST_BLOCK = 0x80

    private class Block(val type: Int, val data: ByteArray)

    /** Returns false and leaves [output] untouched if the input is not FLAC. */
    fun tag(input: InputStream, output: OutputStream, tags: TrackTags): Boolean = try {
        val magic = input.readExactly(4)
        require(String(magic, Charsets.US_ASCII) == "fLaC") { "not a FLAC file" }

        val kept = mutableListOf<Block>()
        while (true) {
            val header = input.readExactly(4)
            val isLast = (header[0].toInt() and LAST_BLOCK) != 0
            val type = header[0].toInt() and 0x7F
            val length = ((header[1].toInt() and 0xFF) shl 16) or
                ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
            val data = input.readExactly(length)
            // Ours replace any that were already there.
            if (type != TYPE_VORBIS_COMMENT && type != TYPE_PICTURE) kept += Block(type, data)
            if (isLast) break
        }

        kept += Block(TYPE_VORBIS_COMMENT, VorbisComments.commentBlock(tags))
        tags.cover?.let { kept += Block(TYPE_PICTURE, VorbisComments.pictureBlock(it)) }

        output.write(magic)
        kept.forEachIndexed { index, block ->
            val last = index == kept.lastIndex
            output.write(block.type or (if (last) LAST_BLOCK else 0))
            output.write((block.data.size ushr 16) and 0xFF)
            output.write((block.data.size ushr 8) and 0xFF)
            output.write(block.data.size and 0xFF)
            output.write(block.data)
        }
        input.copyTo(output, 64 * 1024)
        true
    } catch (e: Exception) {
        LokiLogger.w(TAG, "leaving the file untagged: ${e.message}")
        false
    }

    private fun InputStream.readExactly(count: Int): ByteArray {
        val out = ByteArray(count)
        var read = 0
        while (read < count) {
            val n = read(out, read, count - read)
            require(n >= 0) { "truncated FLAC metadata" }
            read += n
        }
        return out
    }
}
