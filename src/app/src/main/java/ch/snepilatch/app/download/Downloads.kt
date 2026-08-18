package ch.snepilatch.app.download

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

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

    private val _rows = MutableStateFlow<List<DownloadedTrack>>(emptyList())

    /**
     * Every indexed row, newest first. Published rather than re-queried because [refresh] already
     * reads them all off the main thread on every write; the UI reading this instead of calling
     * [all] keeps SQLite out of composition.
     */
    val rows: StateFlow<List<DownloadedTrack>> = _rows.asStateFlow()

    private val _downloaded = MutableStateFlow<Set<String>>(emptySet())

    /** Track URIs with a local copy, for the UI to tint rows without querying per item. */
    val downloaded: StateFlow<Set<String>> = _downloaded.asStateFlow()

    private val _index = MutableStateFlow<Set<String>>(emptySet())

    /** Uri and title/artist keys of every row, so a list row costs a hash lookup, not a scan. */
    val index: StateFlow<Set<String>> = _index.asStateFlow()

    private val _inProgress = MutableStateFlow<Set<String>>(emptySet())

    /** Track URIs being fetched right now, so a row can show a spinner instead of a download icon. */
    val inProgress: StateFlow<Set<String>> = _inProgress.asStateFlow()

    /** What is being fetched right now, as the album, playlist or single the user asked for. */
    data class ActiveJob(
        val name: String,
        val type: String,
        val imageUrl: String?,
        val done: Int,
        val total: Int,
        val trackPercent: Int,
    )

    private val _activeJob = MutableStateFlow<ActiveJob?>(null)
    val activeJob: StateFlow<ActiveJob?> = _activeJob.asStateFlow()

    /**
     * Which job owns the card. There is one slot and several things can download at once — a batch, a
     * tapped row, an auto-save — so an update or a clear has to prove it is the current owner. Without
     * that, whichever finished first blanked the card while the others were still running.
     */
    private val jobOwner = java.util.concurrent.atomic.AtomicInteger(0)

    /** [ActiveJob.type] for a track kept from what was played rather than fetched. */
    const val TYPE_REENCODE = "reencode"

    /**
     * @param onlyIfIdle leaves a running job's card alone and returns 0, which no update can match.
     *   The auto-save uses it: it belongs on the list, but not at the price of blanking an album's
     *   progress mid-batch.
     * @return the token to pass back to [updateJob] and [clearJob].
     */
    fun startJob(name: String, type: String, imageUrl: String?, total: Int, onlyIfIdle: Boolean = false): Int {
        // Safe to hand back 0: the card is only taken once jobOwner has passed 1, so a live job can
        // never own that token.
        if (onlyIfIdle && _activeJob.value != null) return 0
        val token = jobOwner.incrementAndGet()
        _activeJob.value = ActiveJob(name, type, imageUrl, done = 1, total = total, trackPercent = 0)
        return token
    }

    fun updateJob(token: Int, done: Int, trackPercent: Int) {
        if (token != jobOwner.get()) return
        _activeJob.update { it?.copy(done = done, trackPercent = trackPercent) }
    }

    fun clearJob(token: Int) {
        if (token != jobOwner.get()) return
        _activeJob.value = null
    }

    // update {} rather than value = value + x: several downloads run on independent IO coroutines,
    // and a lost remove would leave a row spinning for the rest of the process.
    fun markStarted(trackUri: String) {
        _inProgress.update { it + trackUri }
    }

    fun markFinished(trackUri: String) {
        _inProgress.update { it - trackUri }
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
                listOf("context_uri", "context_name", "context_type")
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

    /**
     * A downloaded row matching by title and artist rather than uri, for when the same song sits in
     * the catalogue under more than one id: separate releases, or the per-market instances Spotify
     * relinks between. Title must match exactly (ignoring case and width); artists only have to
     * overlap, because the credit list and its order differ between releases of the same recording.
     *
     * Known limitation, and the likely cause of any "this played the wrong version" report: two
     * genuinely different recordings that share a title and an artist — a re-record, or an album cut
     * and a single edit released under the same name — are indistinguishable here, so whichever was
     * downloaded first wins. That is the accepted cost of matching on metadata at all; keying on the
     * uri instead is what made a relinked track miss its own download.
     *
     * If it ever produces a real report, the cheap narrowing is duration: [DownloadedTrack] does not
     * store it today, but the schema migrates additively (see [Helper.onUpgrade]) and the caller
     * already has it. That separates an album cut from a single edit, though not a re-record of the
     * same arrangement, so it narrows the collision rather than closing it.
     */
    fun findByMetadata(title: String, artist: String): DownloadedTrack? =
        matchByMetadata(_rows.value, title, artist)

    /** The matching itself, separated from the database so it can be tested directly. */
    internal fun matchByMetadata(rows: List<DownloadedTrack>, title: String, artist: String): DownloadedTrack? {
        val wanted = normalize(title)
        if (wanted.isBlank()) return null
        val artists = artistSet(artist)
        return rows.firstOrNull {
            normalize(it.title) == wanted &&
                (artists.isEmpty() || artistSet(it.artist).any(artists::contains))
        }
    }

    /**
     * Whether [trackUri] is downloaded, checking the exact uri first and falling back to
     * title/artist like [ch.snepilatch.app.playback.AudioSourceResolver.localOrNull] does to
     * resolve playback. Without this, a track that only matches through the fallback plays from
     * disk correctly but every checkmark in the UI still called it not downloaded.
     *
     * Pure over [index]: callers pass the snapshot they already collected, so a row costs a
     * hash lookup rather than a scan of every downloaded row.
     */
    fun isDownloaded(index: Set<String>, trackUri: String, title: String? = null, artist: String? = null): Boolean {
        if (trackUri in index) return true
        if (title.isNullOrBlank()) return false
        val key = normalize(title)
        val artists = artistSet(artist.orEmpty())
        return if (artists.isEmpty()) "$key$SEP" in index else artists.any { "$key$SEP$it" in index }
    }

    internal fun indexOf(rows: List<DownloadedTrack>): Set<String> = rows.flatMapTo(mutableSetOf(), ::keysOf)

    private fun keysOf(row: DownloadedTrack): List<String> {
        val key = normalize(row.title)
        return listOf(row.trackUri, "$key$SEP") + artistSet(row.artist).map { "$key$SEP$it" }
    }

    private val SEP = 0.toChar()

    /** Case- and width-insensitive: half- and full-width forms of a title are the same song. */
    private fun normalize(value: String): String =
        java.text.Normalizer.normalize(value.trim(), java.text.Normalizer.Form.NFKC).lowercase()

    private fun artistSet(artist: String): Set<String> =
        artist.split(',', '&', ';').map(::normalize).filter { it.isNotBlank() }.toSet()

    /** The one place that reads the whole table. Callers want [rows], which this keeps fed. */
    private fun all(): List<DownloadedTrack> {
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
    fun prune(exists: (String) -> Boolean?) {
        val stale = _rows.value.filter { exists(it.documentUri) == false }
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

    /** Every downloaded track belonging to one [Group], keyed by the same uri groups() reports. */
    fun tracksInGroup(groupUri: String): List<DownloadedTrack> =
        _rows.value.filter { groupUriOf(it) == groupUri }

    /**
     * A one-off download groups under itself. It deliberately does not fall back to the album it
     * happens to belong to: two singles off the same record would then present as two identical
     * library entries, both named after the album and both holding one track.
     */
    private fun groupUriOf(row: DownloadedTrack): String = row.contextUri ?: row.trackUri

    fun groups(): List<Group> = groupsOf(_rows.value)

    /** Bytes on disk across every downloaded track, for enforcing AppSettings.downloadCapGb. */
    fun totalSizeBytes(): Long = _rows.value.sumOf { it.sizeBytes }

    /** The grouping itself, separated from the store so it can be tested directly. */
    internal fun groupsOf(rows: List<DownloadedTrack>): List<Group> = rows
        .groupBy(::groupUriOf)
        .map { (uri, tracks) ->
            val first = tracks.first()
            Group(
                uri = uri,
                name = first.contextName ?: first.title,
                type = first.contextType ?: "single",
                imageUrl = first.coverUrl,
                trackCount = tracks.size,
            )
        }
        .sortedBy { it.name.lowercase() }

    private fun refresh() {
        val loaded = all()
        _rows.value = loaded
        _downloaded.value = loaded.mapTo(mutableSetOf()) { it.trackUri }
        _index.value = indexOf(loaded)
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
        sizeBytes = getLong(getColumnIndexOrThrow("size_bytes")),
        title = getString(getColumnIndexOrThrow("title")),
        artist = getString(getColumnIndexOrThrow("artist")),
        downloadedAt = getLong(getColumnIndexOrThrow("downloaded_at")),
    )

    private fun android.database.Cursor.getStringOrNull(column: String): String? =
        getColumnIndexOrThrow(column).let { if (isNull(it)) null else getString(it) }
}
