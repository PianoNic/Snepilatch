package ch.snepilatch.app.logic.download

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import ch.snepilatch.app.logic.shared.LokiLogger
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Writes the track id into a downloaded file (#932). The song is the user's, so the original is never
 * edited in place: the tagged version is written as a new file and read back in full, and only once it
 * matches exactly is the original deleted and the new file given its name. A failure at any point
 * leaves the original as it was. Only the tag area is ever held in memory for FLAC and MP3; the audio is
 * streamed across and compared in chunks, so a file of hundreds of megabytes costs a few (#953).
 */
internal object FileRetagger {

    private const val TAG = "FileRetagger"

    /** The row after tagging, and whether the file was written or already carried the id. */
    class Result(val row: DownloadedTrack, val wrote: Boolean)

    /**
     * Writes the id and whatever of the title, artist, album and cover the file lacks (#946), taking those
     * from [lookup]. Null when the file was left alone.
     */
    suspend fun tag(ctx: Context, row: DownloadedTrack, lookup: CatalogueLookup): Result? {
        val id = row.trackUri.takeIf { it.startsWith("spotify:track:") }?.removePrefix("spotify:track:") ?: return null
        return try {
            retag(ctx, row, id, lookup)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            LokiLogger.w(TAG, "could not tag ${row.title}: ${e.message}")
            null
        }
    }

    private suspend fun retag(ctx: Context, row: DownloadedTrack, id: String, lookup: CatalogueLookup): Result? {
        val original = Uri.parse(row.documentUri)
        val content = read(ctx.contentResolver, original, row) ?: return null
        val bytes = content.bytes
        val missing = TrackIdWriter.missing(bytes) ?: return leftAlone(row)
        val hasId = FolderScan.trackIdIn(String(bytes, Charsets.ISO_8859_1)) == id
        // Asked only when something is missing. Without the details the id alone is still written, and the
        // row stays unchecked so a later migration fills in the rest.
        val info = if (missing.isEmpty()) null else lookup.info(id)
        val checked = missing.isEmpty() || info != null
        val edit = if (hasId && info == null) null else editFor(content, missing, info, id, hasId) ?: return leftAlone(row)
        if (edit == null || !edit.changes(bytes)) return Result(row.copy(idTagged = true, tagsChecked = checked), wrote = false)
        val replaced = replace(ctx, original, edit) ?: return null
        LokiLogger.i(TAG, "tagged ${row.title}: ${if (hasId) "" else "id "}${missing.joinToString(" ").lowercase()}".trimEnd())
        val newSize = content.size - edit.replaced + edit.prefix.size
        return Result(
            row.copy(documentUri = replaced.toString(), idTagged = true, tagsChecked = checked, sizeBytes = newSize),
            wrote = true,
        )
    }

    /** What tagging needs of a file: its start for FLAC and MP3, the whole of anything else (#953). */
    private class Content(val bytes: ByteArray, val size: Long) {
        val whole get() = bytes.size.toLong() == size
    }

    private fun read(resolver: ContentResolver, original: Uri, row: DownloadedTrack): Content? {
        val size = resolver.openFileDescriptor(original, "r")?.use { it.statSize } ?: return null
        val head = resolver.openInputStream(original)?.use { readUpTo(it, TrackIdWriter.HEAD_BYTES) } ?: return null
        return when {
            TrackIdWriter.tagsInFront(head) || head.size.toLong() == size -> Content(head, size)
            size <= TrackIdWriter.WHOLE_FILE_LIMIT -> resolver.openInputStream(original)?.use { Content(it.readBytes(), size) }
            else -> leftAlone(row, "too large to rewrite in memory:").let { null }
        }
    }

    /** The edit giving the file its id and what it lacks of [info]; null when the result would not read back. */
    private fun editFor(
        content: Content,
        missing: Set<TrackIdWriter.Field>,
        info: CatalogueLookup.TrackInfo?,
        id: String,
        hasId: Boolean,
    ): TrackIdWriter.Edit? {
        val tags = TrackTags(
            title = info?.title.orEmpty(),
            artist = info?.artist.orEmpty(),
            album = info?.album,
            cover = info?.coverUrl?.takeIf { TrackIdWriter.Field.COVER in missing }?.let { TrackDownloader.fetchCover(it) },
            trackId = id.takeUnless { hasId },
        )
        return TrackIdWriter.edit(content.bytes, content.whole, tags)
            ?.takeIf { FolderScan.trackIdIn(String(it.prefix, Charsets.ISO_8859_1)) == id }
    }

    private fun leftAlone(row: DownloadedTrack, why: String = "no safe way to tag"): Result? {
        LokiLogger.i(TAG, "$why ${row.title}, left as it was")
        return null
    }

    private const val CHUNK = 256 * 1024

    private fun readUpTo(input: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(CHUNK)
        while (out.size() < limit) {
            val n = input.read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (n <= 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private fun skipFully(input: InputStream, count: Int) {
        var left = count.toLong()
        while (left > 0) {
            val skipped = input.skip(left)
            if (skipped > 0) left -= skipped else if (input.read() < 0) error("file ended early") else left--
        }
    }

    /** The edited file: the new tag area, then the original from where the edit ends, a chunk at a time. */
    private fun writeEdited(resolver: ContentResolver, original: Uri, edit: TrackIdWriter.Edit, out: OutputStream) {
        out.write(edit.prefix)
        resolver.openInputStream(original)?.use { input ->
            skipFully(input, edit.replaced)
            input.copyTo(out, CHUNK)
        } ?: error("no input stream")
    }

    /** Whether [copy] holds exactly what [writeEdited] meant to write, read back a chunk at a time. */
    private fun matches(resolver: ContentResolver, copy: Uri, original: Uri, edit: TrackIdWriter.Edit): Boolean {
        val written = resolver.openInputStream(copy) ?: return false
        val source = resolver.openInputStream(original) ?: return false.also { written.close() }
        return written.use { w ->
            source.use { s ->
                skipFully(s, edit.replaced)
                sameBytes(w, edit.prefix.inputStream()) && sameBytes(w, s) && w.read() < 0
            }
        }
    }

    /** Reads [expected] to its end and [actual] alongside it; true when every byte agreed. */
    private fun sameBytes(actual: InputStream, expected: InputStream): Boolean {
        val want = ByteArray(CHUNK)
        val got = ByteArray(CHUNK)
        while (true) {
            val n = readUpTo(expected, want)
            if (n == 0) return true
            if (readUpTo(actual, got, n) != n) return false
            for (i in 0 until n) if (want[i] != got[i]) return false
        }
    }

    private fun readUpTo(input: InputStream, buffer: ByteArray, count: Int = buffer.size): Int {
        var total = 0
        while (total < count) {
            val n = input.read(buffer, total, count - total)
            if (n <= 0) break
            total += n
        }
        return total
    }

    private fun replace(ctx: Context, original: Uri, edit: TrackIdWriter.Edit): Uri? {
        val resolver = ctx.contentResolver
        val tree = DownloadFolder.folder.value ?: return null
        val name = resolver.query(original, arrayOf(Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) to c.getString(1) else null } ?: return null
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val copy = DocumentsContract.createDocument(resolver, parent, name.second, name.first) ?: return null
        val written = runCatching {
            resolver.openOutputStream(copy, "wt")?.use { writeEdited(resolver, original, edit, it) } ?: error("no output stream")
            matches(resolver, copy, original, edit)
        }.getOrDefault(false)
        if (!written) {
            DocumentsContract.deleteDocument(resolver, copy)
            LokiLogger.w(TAG, "the tagged copy of ${name.first} did not read back intact, kept the original")
            return null
        }
        if (!DocumentsContract.deleteDocument(resolver, original)) {
            // Both exist now; drop the copy rather than leave the song twice in the folder.
            DocumentsContract.deleteDocument(resolver, copy)
            return null
        }
        // The copy got a suffixed name ("… (1).opus") while the original existed; give it the original's.
        return runCatching { DocumentsContract.renameDocument(resolver, copy, name.first) }.getOrNull() ?: copy
    }
}
