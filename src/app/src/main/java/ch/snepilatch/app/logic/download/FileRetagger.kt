package ch.snepilatch.app.logic.download

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import ch.snepilatch.app.logic.shared.LokiLogger

/**
 * Writes the track id into a downloaded file (#932). The song is the user's, so the original is never
 * edited in place: the tagged version is written as a new file and read back in full, and only once it
 * matches exactly is the original deleted and the new file given its name. A failure at any point
 * leaves the original as it was.
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
        val bytes = ctx.contentResolver.openInputStream(original)?.use { it.readBytes() } ?: return null
        val missing = TrackIdWriter.missing(bytes) ?: return leftAlone(row)
        val hasId = FolderScan.trackIdIn(String(bytes, Charsets.ISO_8859_1)) == id
        // Asked only when something is missing. Without the details the id alone is still written, and the
        // row stays unchecked so a later migration fills in the rest.
        val info = if (missing.isEmpty()) null else lookup.info(id)
        val checked = missing.isEmpty() || info != null
        val tagged = if (hasId && info == null) {
            bytes
        } else {
            val tags = TrackTags(
                title = info?.title.orEmpty(),
                artist = info?.artist.orEmpty(),
                album = info?.album,
                cover = info?.coverUrl?.takeIf { TrackIdWriter.Field.COVER in missing }?.let { TrackDownloader.fetchCover(it) },
                trackId = id.takeUnless { hasId },
            )
            TrackIdWriter.withTags(bytes, tags)?.takeIf { FolderScan.trackIdIn(String(it, Charsets.ISO_8859_1)) == id }
                ?: return leftAlone(row)
        }
        if (tagged.contentEquals(bytes)) return Result(row.copy(idTagged = true, tagsChecked = checked), wrote = false)
        val replaced = replace(ctx, original, tagged) ?: return null
        LokiLogger.i(TAG, "tagged ${row.title}: ${if (hasId) "" else "id "}${missing.joinToString(" ").lowercase()}".trimEnd())
        return Result(
            row.copy(documentUri = replaced.toString(), idTagged = true, tagsChecked = checked, sizeBytes = tagged.size.toLong()),
            wrote = true,
        )
    }

    private fun leftAlone(row: DownloadedTrack): Result? {
        LokiLogger.i(TAG, "no safe way to tag ${row.title}, left as it was")
        return null
    }

    private fun replace(ctx: Context, original: Uri, bytes: ByteArray): Uri? {
        val resolver = ctx.contentResolver
        val tree = DownloadFolder.folder.value ?: return null
        val name = resolver.query(original, arrayOf(Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) to c.getString(1) else null } ?: return null
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val copy = DocumentsContract.createDocument(resolver, parent, name.second, name.first) ?: return null
        val written = runCatching {
            resolver.openOutputStream(copy, "wt")?.use { it.write(bytes) } ?: error("no output stream")
            resolver.openInputStream(copy)?.use { it.readBytes() }?.contentEquals(bytes) == true
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
