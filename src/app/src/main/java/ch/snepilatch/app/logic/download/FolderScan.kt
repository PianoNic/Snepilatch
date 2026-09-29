package ch.snepilatch.app.logic.download

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import ch.snepilatch.app.R
import ch.snepilatch.app.logic.shared.AppMessages
import ch.snepilatch.app.logic.shared.LokiLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * Rebuilds index rows from the files already in the download folder, by the track id each one carries
 * in its tags (#566). The index lives in app storage and is gone after a reinstall while the folder
 * survives, so without this every re-download wrote a duplicate next to the file it had lost (#567).
 * Runs when a folder is picked, which a reinstall has to do anyway since the grant is gone too.
 */
object FolderScan {

    private const val TAG = "FolderScan"

    /** Opus and FLAC keep their tags near the start; the cover may sit before them, so this is generous. */
    private const val HEAD_BYTES = 2 * 1024 * 1024

    /** MP4 keeps them in `moov`, which the muxer writes last. */
    private const val TAIL_BYTES = 512 * 1024
    private const val CHUNK = 64 * 1024

    private val AUDIO = setOf("opus", "ogg", "flac", "m4a", "mp4", "mp3", "webm")

    /** The key's value: after `=` in a Vorbis comment, after the `data` box header in an MP4 freeform atom. */
    private val TRACK_ID = Regex(
        Regex.escape(TrackTags.TRACK_ID_KEY) + "(?:=|.{0,24}?data.{8})([A-Za-z0-9]{22})",
        RegexOption.DOT_MATCHES_ALL,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start(context: Context) {
        val ctx = context.applicationContext
        scope.launch {
            val found = runCatching { scan(ctx) }
                .onFailure { LokiLogger.e(TAG, "folder scan failed", it) }
                .getOrDefault(0)
            LokiLogger.i(TAG, "folder scan found $found download(s) the index did not know")
            if (found > 0) AppMessages.show(R.string.downloads_found_in_folder, found)
        }
    }

    private fun scan(ctx: Context): Int {
        val tree = DownloadFolder.folder.value ?: return 0
        val knownDocs = Downloads.rows.value.mapTo(HashSet()) { it.documentUri }
        val knownTracks = Downloads.rows.value.mapTo(HashSet()) { it.trackUri }
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val columns = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED,
        )
        val files = buildList {
            ctx.contentResolver.query(children, columns, null, null, null)?.use { c ->
                while (c.moveToNext()) fileAt(c, tree)?.let(::add)
            }
        }
        var found = 0
        for (file in files.filter { it.uri.toString() !in knownDocs }) {
            val row = rowFor(ctx, file)
            // add() is false for a track already indexed, or a second copy of one this scan just took.
            if (row != null && knownTracks.add(row.trackUri)) {
                Downloads.put(row)
                found++
            }
        }
        return found
    }

    /** The audio file at the cursor's row, or null for anything else in the folder. */
    private fun fileAt(c: android.database.Cursor, tree: Uri): FoundFile? {
        val name = c.getString(1)?.takeIf { it.substringAfterLast('.', "").lowercase() in AUDIO } ?: return null
        val doc = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
        return FoundFile(doc, name, c.getString(2), c.getLong(3), c.getLong(4))
    }

    private class FoundFile(val uri: Uri, val name: String, val mimeType: String?, val size: Long, val modified: Long)

    private fun rowFor(ctx: Context, file: FoundFile): DownloadedTrack? {
        val id = readTrackId(ctx, file) ?: return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(ctx, file.uri)
            // "Artist - Title" is how downloads are named, for a file whose tags the reader cannot see.
            val stem = file.name.substringBeforeLast('.')
            DownloadedTrack(
                trackUri = "spotify:track:$id",
                documentUri = file.uri.toString(),
                source = "folder",
                provider = null,
                mimeType = file.mimeType,
                coverUrl = retriever.embeddedPicture?.let { keepCover(ctx, id, it) },
                contextUri = null,
                contextName = null,
                contextType = null,
                sizeBytes = file.size,
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: stem.substringAfter(" - "),
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: stem.substringBefore(" - "),
                downloadedAt = file.modified,
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
            )
        } catch (e: Exception) {
            LokiLogger.d(TAG, "could not read ${file.name}: ${e.message}")
            null
        } finally {
            retriever.release()
        }
    }

    /** The embedded art as a file the rows can show, since the url it came from is not in the file. */
    private fun keepCover(ctx: Context, id: String, bytes: ByteArray): String? = runCatching {
        val cover = File(ctx.filesDir, "covers/$id.jpg")
        cover.parentFile?.mkdirs()
        cover.writeBytes(bytes)
        Uri.fromFile(cover).toString()
    }.getOrNull()

    private fun readTrackId(ctx: Context, file: FoundFile): String? {
        ctx.contentResolver.openInputStream(file.uri)?.use { input -> scanStream(input, HEAD_BYTES)?.let { return it } }
        if (file.size <= HEAD_BYTES) return null
        return ctx.contentResolver.openFileDescriptor(file.uri, "r")?.use { fd ->
            FileInputStream(fd.fileDescriptor).use { input ->
                input.channel.position(maxOf(0L, file.size - TAIL_BYTES))
                scanStream(input, TAIL_BYTES)
            }
        }
    }

    /** Reads up to [limit] bytes in chunks, overlapping each with the last, until the id turns up. */
    private fun scanStream(input: InputStream, limit: Int): String? {
        val buffer = ByteArray(CHUNK)
        var carried = ""
        var read = 0
        while (read < limit) {
            val n = input.read(buffer)
            if (n <= 0) break
            read += n
            val text = carried + String(buffer, 0, n, Charsets.ISO_8859_1)
            trackIdIn(text)?.let { return it }
            carried = text.takeLast(OVERLAP)
        }
        return null
    }

    private const val OVERLAP = 128

    /** The id in a run of tag bytes read as Latin-1, so every byte maps to one char. */
    internal fun trackIdIn(text: String): String? = TRACK_ID.find(text)?.groupValues?.get(1)
}
