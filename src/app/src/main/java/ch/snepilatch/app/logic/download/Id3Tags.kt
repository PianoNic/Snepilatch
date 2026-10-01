package ch.snepilatch.app.logic.download

import java.io.ByteArrayOutputStream

/**
 * ID3v2 for MP3 (#946): which of the fields the downloads write a file already has, and the file with the
 * ones it lacks added. The tag sits in front of the audio, so the audio bytes are copied as they are and
 * every frame already there is kept byte for byte; the new frames go first, where the folder scan's head
 * read finds the id before any cover. Null for a layout this does not change safely: ID3v2.2, an
 * unsynchronised tag, an extended header or a footer.
 */
internal object Id3Tags {

    private const val HEADER = 10
    private const val FLAG_UNSYNC = 0x80
    private const val FLAG_EXTENDED = 0x40
    private const val FLAG_FOOTER = 0x10
    private const val ENCODING_LATIN1 = 0
    private const val ENCODING_UTF16 = 1
    private const val ENCODING_UTF8 = 3
    private const val PICTURE_FRONT_COVER = 3

    fun isMp3(bytes: ByteArray): Boolean =
        startsWithId3(bytes) || (bytes.size > 1 && bytes[0] == 0xFF.toByte() && (bytes[1].toInt() and 0xE0) == 0xE0)

    private fun startsWithId3(bytes: ByteArray) =
        bytes.size >= HEADER && String(bytes, 0, 3, Charsets.ISO_8859_1) == "ID3"

    /** The frames already there, the version to write new ones in, and where the audio starts. */
    private class Tag(val version: Int, val frames: List<ByteArray>, val ids: Set<String>, val audioAt: Int)

    private fun read(bytes: ByteArray): Tag? {
        if (!startsWithId3(bytes)) return Tag(version = 3, frames = emptyList(), ids = emptySet(), audioAt = 0)
        val version = bytes[3].toInt()
        val flags = bytes[5].toInt() and 0xFF
        if (version !in 3..4 || flags and (FLAG_UNSYNC or FLAG_EXTENDED or FLAG_FOOTER) != 0) return null
        val end = HEADER + synchsafe(bytes, 6)
        if (end > bytes.size) return null
        val frames = ArrayList<ByteArray>()
        var at = HEADER
        while (at + HEADER <= end && bytes[at] != 0.toByte()) {
            val size = if (version == 4) synchsafe(bytes, at + 4) else readBe32(bytes, at + 4)
            if (size < 0 || at + HEADER + size > end) return null
            frames += bytes.copyOfRange(at, at + HEADER + size)
            at += HEADER + size
        }
        return Tag(version, frames, frames.mapTo(HashSet()) { String(it, 0, 4, Charsets.ISO_8859_1) }, end)
    }

    /** Where the audio starts: after the tag, or at 0 without one. Null when the tag lies past [bytes]. */
    fun audioAt(bytes: ByteArray): Int? = read(bytes)?.audioAt

    fun missing(bytes: ByteArray): Set<TrackIdWriter.Field>? {
        val tag = read(bytes) ?: return null
        return FRAME_OF.filterValues { it !in tag.ids }.keys
    }

    private val FRAME_OF = mapOf(
        TrackIdWriter.Field.TITLE to "TIT2",
        TrackIdWriter.Field.ARTIST to "TPE1",
        TrackIdWriter.Field.ALBUM to "TALB",
        TrackIdWriter.Field.COVER to "APIC",
    )

    fun withTags(bytes: ByteArray, tags: TrackTags): ByteArray? {
        val tag = read(bytes) ?: return null
        val added = buildList {
            // The id first: the folder scan reads the head of the file, and a cover could push it past that.
            tags.trackId?.let { add(frame(tag.version, "TXXX", latin1Pair(TrackTags.TRACK_ID_KEY, it))) }
            if ("TIT2" !in tag.ids && tags.title.isNotBlank()) add(frame(tag.version, "TIT2", text(tag.version, tags.title)))
            if ("TPE1" !in tag.ids && tags.artist.isNotBlank()) add(frame(tag.version, "TPE1", text(tag.version, tags.artist)))
            if ("TALB" !in tag.ids && !tags.album.isNullOrBlank()) add(frame(tag.version, "TALB", text(tag.version, tags.album)))
            if ("APIC" !in tag.ids) tags.cover?.let { add(frame(tag.version, "APIC", picture(it))) }
        }
        val frames = added + tag.frames
        val size = frames.sumOf { it.size }
        val out = ByteArrayOutputStream(HEADER + size + bytes.size - tag.audioAt)
        out.write("ID3".toByteArray(Charsets.ISO_8859_1))
        out.write(tag.version)
        out.write(0)
        out.write(0)
        out.write(synchsafeBytes(size))
        frames.forEach { out.write(it) }
        out.write(bytes, tag.audioAt, bytes.size - tag.audioAt)
        return out.toByteArray()
    }

    private fun frame(version: Int, id: String, payload: ByteArray): ByteArray {
        val size = if (version == 4) synchsafeBytes(payload.size) else beBytes(payload.size)
        return id.toByteArray(Charsets.ISO_8859_1) + size + ByteArray(2) + payload
    }

    /** UTF-8 where v2.4 allows it; v2.3 only knows Latin-1 and UTF-16, so UTF-16 with its byte order mark. */
    private fun text(version: Int, value: String): ByteArray =
        if (version == 4) {
            byteArrayOf(ENCODING_UTF8.toByte()) + value.toByteArray(Charsets.UTF_8)
        } else {
            byteArrayOf(ENCODING_UTF16.toByte(), 0xFF.toByte(), 0xFE.toByte()) + value.toByteArray(Charsets.UTF_16LE)
        }

    /** A TXXX body in Latin-1, so the key and id read as plain bytes wherever the scan looks for them. */
    private fun latin1Pair(description: String, value: String): ByteArray =
        byteArrayOf(ENCODING_LATIN1.toByte()) + description.toByteArray(Charsets.ISO_8859_1) + 0 +
            value.toByteArray(Charsets.ISO_8859_1)

    private fun picture(cover: TrackTags.Cover): ByteArray =
        byteArrayOf(ENCODING_LATIN1.toByte()) + cover.mimeType.toByteArray(Charsets.ISO_8859_1) + 0 +
            PICTURE_FRONT_COVER.toByte() + 0 + cover.bytes

    private operator fun ByteArray.plus(byte: Int): ByteArray = this + byte.toByte()

    private fun synchsafe(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0x7F) shl 21) or ((b[at + 1].toInt() and 0x7F) shl 14) or
            ((b[at + 2].toInt() and 0x7F) shl 7) or (b[at + 3].toInt() and 0x7F)

    private fun synchsafeBytes(v: Int) = ByteArray(4) { ((v ushr (7 * (3 - it))) and 0x7F).toByte() }

    private fun beBytes(v: Int) = ByteArray(4) { ((v ushr (8 * (3 - it))) and 0xFF).toByte() }

    private fun readBe32(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
            ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)
}
