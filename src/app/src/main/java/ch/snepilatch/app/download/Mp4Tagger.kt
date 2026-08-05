package ch.snepilatch.app.download

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Writes iTunes-style metadata into an .m4a, the tag format MP4 uses instead of the Vorbis comments
 * Ogg and FLAC share. Same job as [VorbisComments], different container.
 *
 * The tags go in a `udta` appended inside `moov`. That only works when `moov` is the last box in the
 * file: growing it then shifts nothing, so the chunk offsets in `stco` still point at the right
 * bytes. MediaMuxer writes `moov` last, which is the case this exists for — any other layout is left
 * untagged rather than rewritten, because fixing up offsets is a lot of machinery for a file we
 * would rather just save untagged.
 */
internal object Mp4Tagger {

    /** Copies [input] to [output] with [tags] written in. False leaves [output] unusable. */
    fun tag(input: InputStream, output: OutputStream, tags: TrackTags): Boolean {
        val bytes = input.readBytes()
        val moov = findTrailingMoov(bytes) ?: return false
        val udta = udta(tags)
        output.write(bytes, 0, moov.start)
        output.write(beInt(moov.size + udta.size))
        output.write(MOOV)
        // The original children, then ours: everything already inside moov is preserved untouched.
        output.write(bytes, moov.start + HEADER, moov.size - HEADER)
        output.write(udta)
        return true
    }

    private const val HEADER = 8

    /** Box header when the 32-bit size field is 1 and a 64-bit largesize follows the type. */
    private const val WIDE_HEADER = 16
    private val MOOV = "moov".toByteArray(Charsets.US_ASCII)

    private class Box(val start: Int, val size: Int)

    /**
     * The top-level `moov`, but only when it runs to the end of the file. Anything else — `moov`
     * first, trailing boxes after it, a size field we cannot walk — returns null.
     */
    private fun findTrailingMoov(bytes: ByteArray): Box? {
        var offset = 0
        while (offset + HEADER <= bytes.size) {
            val declared = readBeInt(bytes, offset)
            val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)
            // A declared size of 1 means the real size is a 64-bit largesize sitting after the type.
            // Every mp4 YouTube serves writes its mdat that way, and treating it as unwalkable left
            // all of those downloads with no title, artist or cover. Stepping over the box is all we
            // need — mdat's bytes are copied through untouched, never rewritten. Size 0 means "runs
            // to the end of the file", so nothing can follow it and there is no trailing moov.
            val header = if (declared == 1) WIDE_HEADER else HEADER
            val size = if (declared == 1) {
                if (offset + WIDE_HEADER > bytes.size) return null
                val wide = readBeLong(bytes, offset + HEADER)
                if (wide < WIDE_HEADER || wide > Int.MAX_VALUE) return null
                wide.toInt()
            } else {
                if (declared < HEADER) return null
                declared
            }
            if (type == "moov") {
                // The rewrite in [tag] emits a 32-bit moov header, so a wide one is out of scope.
                if (header != HEADER) return null
                return if (offset + size == bytes.size) Box(offset, size) else null
            }
            offset += size
        }
        return null
    }

    private fun udta(tags: TrackTags): ByteArray = box("udta", box("meta", fullBoxVersion() + hdlr() + ilst(tags)))

    /** `meta` is a full box: a version byte and three flag bytes before its children. */
    private fun fullBoxVersion() = ByteArray(4)

    /**
     * HandlerBox: version+flags, then a `pre_defined` word before `handler_type`. Leaving the
     * pre_defined word out shifted everything up one field, so a strict reader saw handler_type
     * 'appl' instead of 'mdir' and a box four bytes shorter than the structure it declares.
     */
    private fun hdlr(): ByteArray = box(
        "hdlr",
        ByteArray(8) + "mdir".toByteArray(Charsets.US_ASCII) + "appl".toByteArray(Charsets.US_ASCII) + ByteArray(9)
    )

    private fun ilst(tags: TrackTags): ByteArray {
        val out = ByteArrayOutputStream()
        // "©" is the 0xA9 byte the iTunes atom names start with, not a copyright sign in UTF-8.
        text(out, "©nam", tags.title)
        text(out, "©ART", tags.artist)
        tags.album?.let { text(out, "©alb", it) }
        tags.cover?.let { cover ->
            val type = if (cover.mimeType.contains("png")) TYPE_PNG else TYPE_JPEG
            out.write(box("covr", data(type, cover.bytes)))
        }
        return box("ilst", out.toByteArray())
    }

    private const val TYPE_UTF8 = 1
    private const val TYPE_JPEG = 13
    private const val TYPE_PNG = 14

    private fun text(out: ByteArrayOutputStream, atom: String, value: String) {
        if (value.isBlank()) return
        out.write(box(atom, data(TYPE_UTF8, value.toByteArray(Charsets.UTF_8))))
    }

    /** The `data` box every metadata atom wraps its value in: a type, a locale, then the payload. */
    private fun data(type: Int, payload: ByteArray): ByteArray =
        box("data", beInt(type) + ByteArray(4) + payload)

    private fun box(type: String, payload: ByteArray): ByteArray {
        val name = type.toByteArray(Charsets.ISO_8859_1)
        return beInt(HEADER + payload.size) + name + payload
    }

    private fun beInt(value: Int) = ByteArray(4) { ((value ushr (8 * (3 - it))) and 0xFF).toByte() }

    private fun readBeInt(bytes: ByteArray, at: Int): Int {
        var value = 0
        for (i in 0 until 4) value = (value shl 8) or (bytes[at + i].toInt() and 0xFF)
        return value
    }

    private fun readBeLong(bytes: ByteArray, at: Int): Long {
        var value = 0L
        for (i in 0 until 8) value = (value shl 8) or (bytes[at + i].toLong() and 0xFF)
        return value
    }
}
