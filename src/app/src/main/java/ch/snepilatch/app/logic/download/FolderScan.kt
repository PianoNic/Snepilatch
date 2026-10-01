package ch.snepilatch.app.logic.download

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import ch.snepilatch.app.R
import ch.snepilatch.app.logic.shared.AppMessages
import ch.snepilatch.app.logic.shared.AppSettings
import ch.snepilatch.app.logic.shared.LokiLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * Brings files already in the download folder into the index. The index lives in app storage and is
 * gone after a reinstall while the folder survives, so without this every re-download wrote a duplicate
 * next to the file it had lost (#567).
 *
 * Picking a folder takes back every file that carries its track id (#566): that is exact. A file without
 * one is only counted, and the user is told; matching those to the catalogue is a guess, so it runs when
 * the user asks for it ([migrate], #930).
 */
object FolderScan {

    private const val TAG = "FolderScan"
    private const val PREF_NOTIFIED = "folder_unindexed_notified"

    /** Files a migration found to be a second copy of an indexed track; not counted as unknown again. */
    private const val PREF_DUPLICATES = "folder_duplicates"

    /** Opus and FLAC keep their tags near the start; the cover may sit before them, so this is generous. */
    private const val HEAD_BYTES = 2 * 1024 * 1024

    /** MP4 keeps them in `moov`, which the muxer writes last. */
    private const val TAIL_BYTES = 512 * 1024
    private const val CHUNK = 64 * 1024
    private const val PARALLEL_LOOKUPS = 4

    private val AUDIO = setOf("opus", "ogg", "flac", "m4a", "mp4", "mp3", "webm")

    /**
     * The key's value: after `=` in a Vorbis comment, after the `data` box header in an MP4 freeform atom,
     * after the terminating zero in an ID3 TXXX frame.
     */
    private val TRACK_ID = Regex(
        Regex.escape(TrackTags.TRACK_ID_KEY) + "(?:=|\\u0000|.{0,24}?data.{8})([A-Za-z0-9]{22})",
        RegexOption.DOT_MATCHES_ALL,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Audio files in the folder that are not in the index, from the last count. */
    private val _unindexed = MutableStateFlow(0)
    val unindexed: StateFlow<Int> = _unindexed.asStateFlow()

    /** How far a running [migrate] is: matching unknown files, then writing ids into the indexed ones. */
    data class Progress(val writingIds: Boolean, val done: Int, val total: Int)

    private val _migrating = MutableStateFlow<Progress?>(null)
    val migrating: StateFlow<Progress?> = _migrating.asStateFlow()

    /** After a folder is picked: take back the tagged files, then count the rest. */
    fun start(context: Context) {
        val ctx = context.applicationContext
        scope.launch {
            val found = runCatching { importFiles(ctx, lookup = null) }
                .onFailure { LokiLogger.e(TAG, "folder scan failed", it) }
                .getOrDefault(0)
            LokiLogger.i(TAG, "folder scan took back $found tagged download(s)")
            if (found > 0) AppMessages.show(R.string.downloads_found_in_folder, found)
            count(ctx)
        }
    }

    /** Counts the files the index does not know, from the folder listing alone: cheap enough for every start. */
    fun check(context: Context) {
        val ctx = context.applicationContext
        scope.launch { count(ctx) }
    }

    /**
     * On the user's request: matches the unknown files to the catalogue, then writes the track id into
     * every indexed file that does not carry it yet, so a reinstall finds them without matching (#932).
     */
    fun migrate(context: Context) {
        val ctx = context.applicationContext
        if (_migrating.value != null) return
        _migrating.value = Progress(writingIds = false, done = 0, total = _unindexed.value)
        scope.launch {
            // One lookup for both steps: the details of each track the matching found fill in its tags.
            val lookup = CatalogueLookup()
            // Remembered copies are looked at again too: one may be the only file left of its track (#948).
            if (_unindexed.value > 0 || duplicates(ctx).isNotEmpty()) {
                val unindexed = _unindexed.value
                val matched = runCatching { importFiles(ctx, lookup) }
                    .onFailure { LokiLogger.e(TAG, "migration failed", it) }
                    .getOrDefault(0)
                val total = _migrating.value?.total ?: 0
                LokiLogger.i(TAG, "matched $matched of $total file(s) to the catalogue")
                if (unindexed > 0) AppMessages.show(R.string.downloads_matched, matched, total)
            }
            val written = runCatching { writeTags(ctx, lookup) }
                .onFailure { LokiLogger.e(TAG, "writing ids failed", it) }
                .getOrDefault(0)
            if (written > 0) AppMessages.show(R.string.downloads_ids_written, written)
            _migrating.value = null
            count(ctx)
        }
    }

    /**
     * The id and the missing tags into every file not checked yet (#946). One file at a time: each is read,
     * rewritten and read back whole, and it is the user's music.
     */
    private suspend fun writeTags(ctx: Context, lookup: CatalogueLookup): Int {
        val unchecked = Downloads.rows.value.filter { needsTags(it) }
        var written = 0
        unchecked.forEachIndexed { i, row ->
            _migrating.value = Progress(writingIds = true, done = i, total = unchecked.size)
            // A file deleted outside the app is gone for good: its row goes, rather than being tried again on
            // every migration and keeping the prompt up (#954).
            if (DownloadFolder.exists(row.documentUri) == false) {
                Downloads.remove(row.trackUri)
                LokiLogger.i(TAG, "${row.title} is no longer in the folder, removed from the downloads")
                return@forEachIndexed
            }
            val result = FileRetagger.tag(ctx, row, lookup) ?: return@forEachIndexed
            Downloads.put(result.row)
            if (result.wrote) written++
        }
        LokiLogger.i(TAG, "wrote tags into $written of ${unchecked.size} file(s)")
        return written
    }

    /** A row whose file may still lack its id or tags, in a format the writer can change. */
    fun needsTags(row: DownloadedTrack): Boolean =
        !(row.idTagged && row.tagsChecked) && TrackIdWriter.canTag(Uri.decode(row.documentUri))

    /** Also tells the user, but only when the number went up since they were last told, not on every start. */
    private fun count(ctx: Context) {
        val known = Downloads.rows.value.mapTo(HashSet()) { it.documentUri } + duplicates(ctx)
        val unknown = runCatching { listFiles(ctx).count { it.uri.toString() !in known } }
            .onFailure { LokiLogger.w(TAG, "could not list the folder: ${it.message}") }
            .getOrNull() ?: return
        _unindexed.value = unknown
        val prefs = ctx.getSharedPreferences(AppSettings.PREFS, Context.MODE_PRIVATE)
        if (unknown > prefs.getInt(PREF_NOTIFIED, 0)) AppMessages.show(R.string.downloads_unindexed_found, unknown)
        prefs.edit().putInt(PREF_NOTIFIED, unknown).apply()
    }

    private fun duplicates(ctx: Context): Set<String> =
        ctx.getSharedPreferences(AppSettings.PREFS, Context.MODE_PRIVATE).getStringSet(PREF_DUPLICATES, emptySet()).orEmpty()

    private fun rememberDuplicates(ctx: Context, uris: Collection<String>) {
        if (uris.isEmpty()) return
        val prefs = ctx.getSharedPreferences(AppSettings.PREFS, Context.MODE_PRIVATE)
        prefs.edit().putStringSet(PREF_DUPLICATES, duplicates(ctx) + uris).apply()
    }

    private fun listFiles(ctx: Context): List<FoundFile> {
        val tree = DownloadFolder.folder.value ?: return emptyList()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val columns = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED,
        )
        return buildList {
            ctx.contentResolver.query(children, columns, null, null, null)?.use { c ->
                while (c.moveToNext()) fileAt(c, tree)?.let(::add)
            }
        }
    }

    /**
     * Adds a row for each unknown file that says which track it is: by its tag, and with a [lookup] also
     * by a catalogue match. Returns how many were added.
     */
    private suspend fun importFiles(ctx: Context, lookup: CatalogueLookup?): Int {
        val knownDocs = Downloads.rows.value.mapTo(HashSet()) { it.documentUri }
        val knownTracks = Downloads.rows.value.mapTo(HashSet()) { it.trackUri }
        val rowOf = Downloads.rows.value.associateByTo(HashMap()) { it.trackUri }
        val files = listFiles(ctx).filter { it.uri.toString() !in knownDocs }
        if (lookup != null) _migrating.value = Progress(writingIds = false, done = 0, total = files.size)
        var added = 0
        var done = 0
        val copies = mutableListOf<String>()
        val copyRows = mutableListOf<DownloadedTrack>()
        // A lookup is a network round trip of about a second; one at a time a folder of a few hundred
        // files took minutes. A few in flight, like the web player's own requests.
        val gate = Semaphore(PARALLEL_LOOKUPS)
        val index = Mutex()
        coroutineScope {
            for (file in files) {
                launch {
                    val row = gate.withPermit { rowFor(ctx, file, lookup) }
                    index.withLock {
                        // add() is false for a track already indexed, or a second copy of one just taken.
                        when {
                            row == null -> LokiLogger.i(TAG, "no track found for ${file.name}")
                            !knownTracks.add(row.trackUri) -> {
                                val lost = rowOf[row.trackUri]?.takeIf { isLost(it) }
                                if (lost != null) {
                                    // The row's own file is out of reach, so this one takes its place (#948).
                                    val moved = lost.copy(
                                        documentUri = row.documentUri, mimeType = row.mimeType, sizeBytes = row.sizeBytes,
                                        idTagged = row.idTagged,
                                    )
                                    Downloads.put(moved)
                                    rowOf[row.trackUri] = moved
                                    LokiLogger.i(TAG, "${file.name} takes over ${row.trackUri} from a file that is gone")
                                } else {
                                    LokiLogger.i(TAG, "${file.name} is another copy of ${row.trackUri}")
                                    copyRows += row
                                }
                            }
                            else -> {
                                Downloads.put(row)
                                added++
                            }
                        }
                        done++
                        if (lookup != null) _migrating.value = Progress(writingIds = false, done = done, total = files.size)
                    }
                }
            }
        }
        // A copy is the user's music as much as the file the downloads know, so it gets the same tags (#950).
        // Tagging writes a new file, so the copy is remembered under the name it has afterwards.
        for (copy in copyRows) {
            val tagged = lookup?.let { FileRetagger.tag(ctx, copy, it) }?.row
            copies += tagged?.documentUri ?: copy.documentUri
        }
        rememberDuplicates(ctx, copies)
        return added
    }

    /**
     * Whether [row]'s file is out of reach: outside the download folder (one that was moved or renamed,
     * which the app can no longer open) or gone from inside it. An inconclusive check is not a yes.
     */
    private fun isLost(row: DownloadedTrack): Boolean {
        val tree = DownloadFolder.folder.value ?: return false
        val inFolder = runCatching {
            DocumentsContract.getDocumentId(Uri.parse(row.documentUri))
                .startsWith(DocumentsContract.getTreeDocumentId(tree) + "/")
        }.getOrDefault(true)
        return !inFolder || DownloadFolder.exists(row.documentUri) == false
    }

    /** The audio file at the cursor's row, or null for anything else in the folder. */
    private fun fileAt(c: android.database.Cursor, tree: Uri): FoundFile? {
        val name = c.getString(1)?.takeIf { it.substringAfterLast('.', "").lowercase() in AUDIO } ?: return null
        val doc = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
        return FoundFile(doc, name, c.getString(2), c.getLong(3), c.getLong(4))
    }

    private class FoundFile(val uri: Uri, val name: String, val mimeType: String?, val size: Long, val modified: Long)

    /**
     * The row for [file]: its own track id when it carries one, else the catalogue match for its title,
     * artist and length (#930). Null when neither says which track it is.
     */
    private suspend fun rowFor(ctx: Context, file: FoundFile, lookup: CatalogueLookup?): DownloadedTrack? {
        val meta = readMeta(ctx, file) ?: return null
        val tagged = readTrackId(ctx, file)
        val id = tagged ?: lookup?.find(meta.title, meta.artist, meta.durationMs) ?: return null
        return DownloadedTrack(
            trackUri = "spotify:track:$id",
            documentUri = file.uri.toString(),
            source = "folder",
            provider = null,
            mimeType = file.mimeType,
            coverUrl = meta.picture?.let { keepCover(ctx, id, it) },
            contextUri = null,
            contextName = null,
            contextType = null,
            sizeBytes = file.size,
            title = meta.title,
            artist = meta.artist,
            downloadedAt = file.modified,
            durationMs = meta.durationMs,
            idTagged = tagged != null,
        )
    }

    private class Meta(val title: String, val artist: String, val durationMs: Long, val picture: ByteArray?)

    private fun readMeta(ctx: Context, file: FoundFile): Meta? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(ctx, file.uri)
            // "Artist - Title" is how downloads are named, for a file whose tags the reader cannot see.
            val stem = file.name.substringBeforeLast('.')
            Meta(
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: stem.substringAfter(" - "),
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: stem.substringBefore(" - "),
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
                picture = retriever.embeddedPicture,
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
