package ch.snepilatch.app.download

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One downloaded file. [documentUri] is a SAF document in the folder the user picked. */
data class DownloadedTrack(
    val trackUri: String,
    val documentUri: String,
    val source: String,
    val provider: String?,
    val mimeType: String?,
    val coverUrl: String?,
    /** What it was downloaded from: an album, a playlist, or nothing for a one-off track. */
    val contextUri: String?,
    val contextName: String?,
    val contextType: String?,
    val albumUri: String?,
    val albumName: String?,
    val sizeBytes: Long,
    val title: String,
    val artist: String,
    val downloadedAt: Long,
)

/**
 * Index of what has been downloaded. Process-scoped like [ch.snepilatch.app.viewmodel.AppSettings],
 * backed by a single SQLite table.
 *
 * Plain SQLiteOpenHelper rather than Room: one table, no migrations to speak of, and Room would add
 * an annotation processor to the build for a handful of statements.
 */
object Downloads {

    private const val DB_NAME = "downloads.db"
    private const val DB_VERSION = 3
    private const val TABLE = "downloads"

    private var helper: Helper? = null

    private val _downloaded = MutableStateFlow<Set<String>>(emptySet())

    /** Track URIs with a local copy, for the UI to tint rows without querying per item. */
    val downloaded: StateFlow<Set<String>> = _downloaded.asStateFlow()

    private val _inProgress = MutableStateFlow<Set<String>>(emptySet())

    /** Track URIs being fetched right now, so a row can show a spinner instead of a download icon. */
    val inProgress: StateFlow<Set<String>> = _inProgress.asStateFlow()

    fun markStarted(trackUri: String) {
        _inProgress.value = _inProgress.value + trackUri
    }

    fun markFinished(trackUri: String) {
        _inProgress.value = _inProgress.value - trackUri
    }

    private class Helper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE $TABLE (
                    track_uri TEXT PRIMARY KEY,
                    document_uri TEXT NOT NULL,
                    source TEXT NOT NULL,
                    provider TEXT,
                    mime_type TEXT,
                    cover_url TEXT,
                    context_uri TEXT,
                    context_name TEXT,
                    context_type TEXT,
                    album_uri TEXT,
                    album_name TEXT,
                    size_bytes INTEGER NOT NULL,
                    title TEXT NOT NULL,
                    artist TEXT NOT NULL,
                    downloaded_at INTEGER NOT NULL
                )
                """.trimIndent()
            )
        }

        /**
         * Migrations must be additive. Dropping the table would silently orphan every downloaded
         * file: the audio stays in the user's folder while the app forgets it was ever downloaded,
         * and re-downloading is the only way back. Add columns, never recreate.
         */
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // v2 added the cover art url. Additive, so existing downloads keep working untagged.
            if (oldVersion < 2) db.execSQL("ALTER TABLE $TABLE ADD COLUMN cover_url TEXT")
            if (oldVersion < 3) {
                // v3 records where a download came from so the library can group them.
                listOf("context_uri", "context_name", "context_type", "album_uri", "album_name")
                    .forEach { db.execSQL("ALTER TABLE $TABLE ADD COLUMN $it TEXT") }
            }
        }

        /** Sideloading an older build must not crash; the default implementation throws. */
        override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    fun init(context: Context) {
        if (helper != null) return
        helper = Helper(context.applicationContext)
        refresh()
    }

    fun find(trackUri: String): DownloadedTrack? {
        val db = helper?.readableDatabase ?: return null
        db.query(TABLE, null, "track_uri = ?", arrayOf(trackUri), null, null, null).use { c ->
            return if (c.moveToFirst()) c.toTrack() else null
        }
    }

    fun all(): List<DownloadedTrack> {
        val db = helper?.readableDatabase ?: return emptyList()
        db.query(TABLE, null, null, null, null, null, "downloaded_at DESC").use { c ->
            val out = mutableListOf<DownloadedTrack>()
            while (c.moveToNext()) out += c.toTrack()
            return out
        }
    }

    fun put(track: DownloadedTrack) {
        val db = helper?.writableDatabase ?: return
        db.insertWithOnConflict(TABLE, null, track.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
        refresh()
    }

    fun remove(trackUri: String) {
        val db = helper?.writableDatabase ?: return
        db.delete(TABLE, "track_uri = ?", arrayOf(trackUri))
        refresh()
    }

    /** Drops rows whose file is gone, which happens when the user deletes from the folder. */
    fun prune(exists: (String) -> Boolean) {
        val stale = all().filterNot { exists(it.documentUri) }
        if (stale.isEmpty()) return
        val db = helper?.writableDatabase ?: return
        stale.forEach { db.delete(TABLE, "track_uri = ?", arrayOf(it.trackUri)) }
        refresh()
    }

    /**
     * Downloaded content grouped for the library: the album or playlist it came from, or the track
     * itself when it was downloaded on its own.
     */
    data class Group(
        val uri: String,
        val name: String,
        val type: String,
        val imageUrl: String?,
        val trackCount: Int,
    )

    fun groups(): List<Group> = all()
        .groupBy { it.contextUri ?: it.albumUri ?: it.trackUri }
        .map { (uri, tracks) ->
            val first = tracks.first()
            Group(
                uri = uri,
                name = first.contextName ?: first.albumName ?: first.title,
                type = first.contextType ?: if (first.albumUri != null) "album" else "single",
                imageUrl = first.coverUrl,
                trackCount = tracks.size,
            )
        }
        .sortedBy { it.name.lowercase() }

    private fun refresh() {
        _downloaded.value = all().map { it.trackUri }.toSet()
    }

    private fun DownloadedTrack.toValues() = ContentValues().apply {
        put("track_uri", trackUri)
        put("document_uri", documentUri)
        put("source", source)
        put("provider", provider)
        put("mime_type", mimeType)
        put("cover_url", coverUrl)
        put("context_uri", contextUri)
        put("context_name", contextName)
        put("context_type", contextType)
        put("album_uri", albumUri)
        put("album_name", albumName)
        put("size_bytes", sizeBytes)
        put("title", title)
        put("artist", artist)
        put("downloaded_at", downloadedAt)
    }

    private fun android.database.Cursor.toTrack() = DownloadedTrack(
        trackUri = getString(getColumnIndexOrThrow("track_uri")),
        documentUri = getString(getColumnIndexOrThrow("document_uri")),
        source = getString(getColumnIndexOrThrow("source")),
        provider = getStringOrNull("provider"),
        mimeType = getStringOrNull("mime_type"),
        coverUrl = getStringOrNull("cover_url"),
        contextUri = getStringOrNull("context_uri"),
        contextName = getStringOrNull("context_name"),
        contextType = getStringOrNull("context_type"),
        albumUri = getStringOrNull("album_uri"),
        albumName = getStringOrNull("album_name"),
        sizeBytes = getLong(getColumnIndexOrThrow("size_bytes")),
        title = getString(getColumnIndexOrThrow("title")),
        artist = getString(getColumnIndexOrThrow("artist")),
        downloadedAt = getLong(getColumnIndexOrThrow("downloaded_at")),
    )

    private fun android.database.Cursor.getStringOrNull(column: String): String? =
        getColumnIndexOrThrow(column).let { if (isNull(it)) null else getString(it) }
}
