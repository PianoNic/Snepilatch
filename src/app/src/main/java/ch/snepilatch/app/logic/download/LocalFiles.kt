package ch.snepilatch.app.logic.download

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import ch.snepilatch.app.R
import ch.snepilatch.app.data.TrackInfo
import ch.snepilatch.app.logic.shared.AppMessages
import ch.snepilatch.app.logic.shared.AppSettings
import ch.snepilatch.app.logic.shared.LokiLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.net.URLEncoder

/** One audio file from a folder the user picked. What it says is what its own tags say, nothing else. */
data class LocalTrack(
    val uri: String,
    val documentUri: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val coverPath: String?,
    val mimeType: String?,
    val size: Long,
    val modified: Long,
) {
    fun toTrackInfo() = TrackInfo(uri = uri, name = title, artist = artist, albumArt = coverPath, albumName = album, durationMs = durationMs)
}

/**
 * Local Files, as the desktop client has them (#1070): any number of folders the user picks, listed with
 * the tags their files carry and played on this phone. Kept apart from [Downloads] on purpose: those are
 * Snepilatch's own files, with its storage limit, retagging and catalogue matching, and none of that may
 * touch the user's music. Nothing is looked up and no file is written to; a grant is read only.
 */
object LocalFiles {

    const val URI_PREFIX = "spotify:local:"

    /** The library row and the context the page plays as, the desktop client's uri. */
    const val LIBRARY_URI = "spotify:collection:local-files"
    const val LIBRARY_TYPE = "local-files"

    private const val TAG = "LocalFiles"
    private const val PREF_ENABLED = "local_files_enabled"
    private const val PREF_FOLDERS = "local_files_folders"
    private const val MS_PER_SECOND = 1000L
    private val AUDIO = setOf("opus", "ogg", "flac", "m4a", "mp4", "mp3", "webm", "wav", "aac")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var scan: Job? = null
    private var helper: Helper? = null

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _folders = MutableStateFlow<List<Uri>>(emptyList())
    val folders: StateFlow<List<Uri>> = _folders.asStateFlow()

    private val _tracks = MutableStateFlow<List<LocalTrack>>(emptyList())
    val tracks: StateFlow<List<LocalTrack>> = _tracks.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    /** The saved folders still granted, the last listing at once, then a fresh one behind it. */
    fun load(context: Context) {
        val ctx = context.applicationContext
        helper = Helper(ctx)
        val prefs = prefs(ctx)
        _enabled.value = prefs.getBoolean(PREF_ENABLED, false)
        val held = ctx.contentResolver.persistedUriPermissions.mapTo(HashSet()) { it.uri }
        _folders.value = prefs.getStringSet(PREF_FOLDERS, emptySet()).orEmpty()
            .map(Uri::parse)
            .filter { it in held }
            .sortedBy { it.toString() }
        _tracks.value = stored()
        if (_enabled.value) rescan(ctx)
    }

    fun setEnabled(on: Boolean, context: Context) {
        prefs(context).edit().putBoolean(PREF_ENABLED, on).apply()
        _enabled.value = on
        if (on) rescan(context.applicationContext)
    }

    /** Takes a read grant on [uri] and lists it. The download folder is Snepilatch's own and stays out. */
    fun addFolder(uri: Uri, context: Context) {
        val ctx = context.applicationContext
        if (isDownloadFolder(uri)) {
            AppMessages.show(R.string.local_files_is_download_folder)
            return
        }
        if (uri in _folders.value) return
        runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            .onFailure { LokiLogger.e(TAG, "could not persist permission: ${it.message}") }
        saveFolders(ctx, _folders.value + uri)
        rescan(ctx)
    }

    fun removeFolder(uri: Uri, context: Context) {
        val ctx = context.applicationContext
        saveFolders(ctx, _folders.value - uri)
        // The same tree may be the download folder's grant, which has to stay.
        if (DownloadFolder.folder.value != uri) {
            runCatching { ctx.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        rescan(ctx)
    }

    /** The file behind a track, for the resolver. */
    fun find(uri: String): LocalTrack? = _tracks.value.firstOrNull { it.uri == uri }

    private fun saveFolders(ctx: Context, folders: List<Uri>) {
        prefs(ctx).edit().putStringSet(PREF_FOLDERS, folders.mapTo(HashSet()) { it.toString() }).apply()
        _folders.value = folders
    }

    private fun isDownloadFolder(uri: Uri): Boolean {
        val own = DownloadFolder.folder.value ?: return false
        return DocumentsContract.getTreeDocumentId(uri) == DocumentsContract.getTreeDocumentId(own)
    }

    private fun rescan(ctx: Context) {
        scan?.cancel()
        scan = scope.launch {
            _scanning.value = true
            runCatching { scanFolders(ctx) }.onFailure { LokiLogger.e(TAG, "scan failed", it) }
            _scanning.value = false
        }
    }

    /** Tags are read only for a file that is new or changed: reading one is slow and a folder can hold thousands. */
    private fun scanFolders(ctx: Context) {
        val known = _tracks.value.associateBy { it.documentUri }
        val ownRoot = DownloadFolder.folder.value?.let(DocumentsContract::getTreeDocumentId)
        // A folder deleted or unplugged since it was added costs its own songs, not the others'.
        val found = _folders.value.flatMap { folder ->
            runCatching { listAudio(ctx, folder, ownRoot) }
                .onFailure { LokiLogger.w(TAG, "could not list $folder: ${it.message}") }
                .getOrDefault(emptyList())
        }.distinctBy { it.uri }
        val tracks = found.mapNotNull { file ->
            known[file.uri.toString()]?.takeIf { it.size == file.size && it.modified == file.modified } ?: read(ctx, file)
        }.distinctBy { it.uri }.sortedBy { it.title.lowercase() }
        store(tracks)
        _tracks.value = tracks
        LokiLogger.i(TAG, "${tracks.size} local tracks in ${_folders.value.size} folder(s)")
    }

    private class Found(val uri: Uri, val name: String, val mimeType: String?, val size: Long, val modified: Long)

    /** Every audio file under [tree], its subfolders too, the download folder left out wherever it sits. */
    private fun listAudio(ctx: Context, tree: Uri, ownRoot: String?): List<Found> {
        val columns = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED,
        )
        val out = ArrayList<Found>()
        val pending = ArrayDeque(listOf(DocumentsContract.getTreeDocumentId(tree)))
        while (pending.isNotEmpty()) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, pending.removeFirst())
            ctx.contentResolver.query(children, columns, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val name = c.getString(1).orEmpty()
                    val mime = c.getString(2)
                    when {
                        mime == Document.MIME_TYPE_DIR -> if (id != ownRoot) pending.add(id)
                        name.substringAfterLast('.', "").lowercase() in AUDIO && c.getLong(3) > 0 ->
                            out += Found(DocumentsContract.buildDocumentUriUsingTree(tree, id), name, mime, c.getLong(3), c.getLong(4))
                    }
                }
            }
        }
        return out
    }

    private fun read(ctx: Context, file: Found): LocalTrack? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(ctx, file.uri)
            fun tag(key: Int) = retriever.extractMetadata(key)?.trim().orEmpty()
            val title = tag(MediaMetadataRetriever.METADATA_KEY_TITLE).ifEmpty { file.name.substringBeforeLast('.') }
            val artist = tag(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            val album = tag(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            val duration = tag(MediaMetadataRetriever.METADATA_KEY_DURATION).toLongOrNull() ?: 0L
            LocalTrack(
                uri = localTrackUri(artist, album, title, duration),
                documentUri = file.uri.toString(),
                title = title,
                artist = artist,
                album = album,
                durationMs = duration,
                coverPath = retriever.embeddedPicture?.let { keepCover(ctx, file.uri, it) },
                mimeType = file.mimeType,
                size = file.size,
                modified = file.modified,
            )
        } catch (e: Exception) {
            LokiLogger.w(TAG, "${file.name} cannot be read, left out: ${e.message}")
            null
        } finally {
            retriever.release()
        }
    }

    private fun keepCover(ctx: Context, file: Uri, bytes: ByteArray): String? = runCatching {
        val dir = File(ctx.filesDir, "local_covers").apply { mkdirs() }
        File(dir, "${file.toString().hashCode()}.img").apply { writeBytes(bytes) }.absolutePath
    }.getOrNull()

    private fun stored(): List<LocalTrack> {
        val db = helper?.readableDatabase ?: return emptyList()
        return db.query("tracks", null, null, null, null, null, "title COLLATE NOCASE").use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        LocalTrack(
                            uri = c.getString(c.getColumnIndexOrThrow("uri")),
                            documentUri = c.getString(c.getColumnIndexOrThrow("document_uri")),
                            title = c.getString(c.getColumnIndexOrThrow("title")),
                            artist = c.getString(c.getColumnIndexOrThrow("artist")),
                            album = c.getString(c.getColumnIndexOrThrow("album")),
                            durationMs = c.getLong(c.getColumnIndexOrThrow("duration_ms")),
                            coverPath = c.getString(c.getColumnIndexOrThrow("cover_path")),
                            mimeType = c.getString(c.getColumnIndexOrThrow("mime_type")),
                            size = c.getLong(c.getColumnIndexOrThrow("size")),
                            modified = c.getLong(c.getColumnIndexOrThrow("modified")),
                        )
                    )
                }
            }
        }
    }

    private fun store(tracks: List<LocalTrack>) {
        val db = helper?.writableDatabase ?: return
        db.beginTransaction()
        try {
            db.delete("tracks", null, null)
            tracks.forEach { t ->
                db.insert(
                    "tracks", null,
                    ContentValues().apply {
                        put("uri", t.uri)
                        put("document_uri", t.documentUri)
                        put("title", t.title)
                        put("artist", t.artist)
                        put("album", t.album)
                        put("duration_ms", t.durationMs)
                        put("cover_path", t.coverPath)
                        put("mime_type", t.mimeType)
                        put("size", t.size)
                        put("modified", t.modified)
                    }
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(AppSettings.PREFS, Context.MODE_PRIVATE)

    /**
     * The client's local track uri, `spotify:local:artist:album:title:seconds`, url encoded with `+` for a
     * space. `ponytail:` lives here while only this phone reads it; it moves to Kotify the day one is sent
     * to the server (a playlist, Connect).
     */
    internal fun localTrackUri(artist: String, album: String, title: String, durationMs: Long): String {
        fun encode(part: String) = URLEncoder.encode(part, "UTF-8").replace("*", "%2A")
        return "$URI_PREFIX${encode(artist)}:${encode(album)}:${encode(title)}:${durationMs / MS_PER_SECOND}"
    }

    private class Helper(context: Context) : SQLiteOpenHelper(context, "local_files.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE tracks (document_uri TEXT PRIMARY KEY, uri TEXT NOT NULL, title TEXT NOT NULL, " +
                    "artist TEXT NOT NULL, album TEXT NOT NULL, duration_ms INTEGER NOT NULL, cover_path TEXT, " +
                    "mime_type TEXT, size INTEGER NOT NULL, modified INTEGER NOT NULL)"
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
}
