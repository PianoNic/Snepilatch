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
    private val MOOV = "moov".toByteArray(Charsets.US_ASCII)

    private class Box(val start: Int, val size: Int)

    /**
     * The top-level `moov`, but only when it runs to the end of the file. Anything else — `moov`
     * first, trailing boxes after it, a size field we cannot walk — returns null.
     */
    private fun findTrailingMoov(bytes: ByteArray): Box? {
        var offset = 0
        while (offset + HEADER <= bytes.size) {
            val size = readBeInt(bytes, offset)
            val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)
            // Size 0 means "to end of file" and 1 means a 64-bit size follows; neither is a layout
            // we rewrite, so both bail out rather than being guessed at.
            if (size < HEADER) return null
            if (type == "moov") {
                return if (offset + size == bytes.size) Box(offset, size) else null
            }
            offset += size
        }
        return null
    }

    private fun udta(tags: TrackTags): ByteArray = box("udta", box("meta", fullBoxVersion() + hdlr() + ilst(tags)))

    /** `meta` is a full box: a version byte and three flag bytes before its children. */
    private fun fullBoxVersion() = ByteArray(4)

    private fun hdlr(): ByteArray = box(
        "hdlr",
        ByteArray(4) + "mdir".toByteArray(Charsets.US_ASCII) + "appl".toByteArray(Charsets.US_ASCII) + ByteArray(9)
    )

    private fun ilst(tags: TrackTags): ByteArray {
        val out = ByteArrayOutputStream()
        // "©" is the 0xA9 byte the iTunes atom names start with, not a copyright sign in UTF-8.
        text(out, "©nam", tags.title)
        text(out, "©ART", tags.artist)
        tags.album?.let { text(out, "©alb", it) }
        tags.albumArtist?.let { text(out, "aART", it) }
        tags.year?.let { text(out, "©day", it) }
        tags.trackNumber?.let { number ->
            out.write(box("trkn", data(TYPE_BINARY, byteArrayOf(0, 0) + beShort(number) + ByteArray(4))))
        }
        tags.cover?.let { cover ->
            val type = if (cover.mimeType.contains("png")) TYPE_PNG else TYPE_JPEG
            out.write(box("covr", data(type, cover.bytes)))
        }
        return box("ilst", out.toByteArray())
    }

    private const val TYPE_BINARY = 0
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

    private fun beShort(value: Int) = ByteArray(2) { ((value ushr (8 * (1 - it))) and 0xFF).toByte() }

    private fun readBeInt(bytes: ByteArray, at: Int): Int {
        var value = 0
        for (i in 0 until 4) value = (value shl 8) or (bytes[at + i].toInt() and 0xFF)
        return value
    }
}
