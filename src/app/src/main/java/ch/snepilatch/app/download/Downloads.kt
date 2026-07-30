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
    private const val DB_VERSION = 2
    private const val TABLE = "downloads"

    private var helper: Helper? = null

    private val _downloaded = MutableStateFlow<Set<String>>(emptySet())

    /** Track URIs with a local copy, for the UI to tint rows without querying per item. */
    val downloaded: StateFlow<Set<String>> = _downloaded.asStateFlow()

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
        sizeBytes = getLong(getColumnIndexOrThrow("size_bytes")),
        title = getString(getColumnIndexOrThrow("title")),
        artist = getString(getColumnIndexOrThrow("artist")),
        downloadedAt = getLong(getColumnIndexOrThrow("downloaded_at")),
    )

    private fun android.database.Cursor.getStringOrNull(column: String): String? =
        getColumnIndexOrThrow(column).let { if (isNull(it)) null else getString(it) }
}
