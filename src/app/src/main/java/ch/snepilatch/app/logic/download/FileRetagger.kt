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

    /** Null when the file was left alone. */
    fun tag(ctx: Context, row: DownloadedTrack): Result? {
        val id = row.trackUri.takeIf { it.startsWith("spotify:track:") }?.removePrefix("spotify:track:") ?: return null
        val resolver = ctx.contentResolver
        val original = Uri.parse(row.documentUri)
        return try {
            val bytes = resolver.openInputStream(original)?.use { it.readBytes() } ?: return null
            // Already there (a download from after #566): nothing to write, only the index to update.
            if (FolderScan.trackIdIn(String(bytes, Charsets.ISO_8859_1)) == id) return Result(row.copy(idTagged = true), wrote = false)
            val tagged = TrackIdWriter.withTrackId(bytes, id)
                ?.takeIf { FolderScan.trackIdIn(String(it, Charsets.ISO_8859_1)) == id }
                ?: return null.also { LokiLogger.i(TAG, "no safe way to tag ${row.title}, left as it was") }
            val replaced = replace(ctx, original, tagged) ?: return null
            Result(row.copy(documentUri = replaced.toString(), idTagged = true, sizeBytes = tagged.size.toLong()), wrote = true)
        } catch (e: Exception) {
            LokiLogger.w(TAG, "could not tag ${row.title}: ${e.message}")
            null
        }
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
