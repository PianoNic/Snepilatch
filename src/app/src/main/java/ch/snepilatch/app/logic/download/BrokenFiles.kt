package ch.snepilatch.app.logic.download

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import ch.snepilatch.app.R
import ch.snepilatch.app.logic.download.FolderScan.FoundFile
import ch.snepilatch.app.logic.shared.AppMessages
import ch.snepilatch.app.logic.shared.LokiLogger
import kotlinx.coroutines.CoroutineScope

/**
 * The folder files that are not the track they were downloaded as (#1053): empty ones, and ones a wrong
 * Qobuz or Deezer hit filled with another song or another cut of it. Each track is downloaded again, the
 * hit checked this time, and the broken file goes only once the new one is there.
 */
internal object BrokenFiles {

    private const val TAG = "FolderScan"

    /** A file that is not the track it was downloaded as, the track, and what is wrong, for the log. */
    class Broken(val file: FoundFile, val id: String, val why: String)

    /** The track [file]'s name says, "Artist - Title" or the other way round, whatever its tags say. */
    suspend fun byName(file: FoundFile, lookup: CatalogueLookup): String? {
        val stem = file.name.substringBeforeLast('.')
        // Split at the first and at the last " - ", and try each side as the title.
        val splits = listOf(stem.indexOf(" - "), stem.lastIndexOf(" - ")).filter { it > 0 }.distinct()
            .map { stem.substring(0, it) to stem.substring(it + 3) }
        return splits.firstNotNullOfOrNull { (a, b) -> lookup.find(b, a, 0) ?: lookup.find(a, b, 0) }
    }

    /**
     * Downloads each broken file's track again (#1053), one at a time like any batch, and deletes the broken
     * file only once its replacement is in the folder: a download that finds no source keeps the old file.
     * Returns how many were replaced.
     */
    suspend fun replace(
        ctx: Context,
        scope: CoroutineScope,
        broken: List<Broken>,
        lookup: CatalogueLookup,
        onStep: (done: Int, total: Int) -> Unit,
    ): Int {
        val files = broken.distinctBy { it.file.uri }
        if (files.isEmpty()) return 0
        AppMessages.show(R.string.downloads_redownloading, files.size)
        var replaced = 0
        for ((i, b) in files.withIndex()) {
            onStep(i, files.size)
            val info = lookup.info(b.id)
            val stem = b.file.name.substringBeforeLast('.')
            val track = ch.snepilatch.app.data.TrackInfo(
                uri = "spotify:track:${b.id}",
                name = info?.title ?: stem.substringAfter(" - "),
                artist = info?.artist ?: stem.substringBefore(" - "),
                albumArt = info?.coverUrl,
                albumName = info?.album,
                // The source's hit is checked against it, so the new file is the track this time.
                durationMs = info?.durationMs ?: 0L,
            )
            LokiLogger.i(TAG, "${b.file.name} ${b.why}, downloading ${track.artist} - ${track.name} again")
            val done = DownloadActions.downloadTrack(scope, track, ctx).await() as? DownloadOutcome.Done
            if (done == null) {
                LokiLogger.w(TAG, "no source for ${track.artist} - ${track.name}, ${b.file.name} stays as it is")
                continue
            }
            if (swapIn(ctx, b.file, done.track)) replaced++
        }
        return replaced
    }

    private val COPY_SUFFIX = Regex("""^(.*) \(\d+\)$""")

    /**
     * Deletes [old] now that [row]'s file holds its track, and gives the new file the old one's name when it
     * only lacks it for the clash.
     */
    private fun swapIn(ctx: Context, old: FoundFile, row: DownloadedTrack): Boolean {
        if (row.documentUri == old.uri.toString()) return false
        val resolver = ctx.contentResolver
        val deleted = runCatching { DocumentsContract.deleteDocument(resolver, old.uri) }.getOrDefault(false)
        if (!deleted) {
            LokiLogger.w(TAG, "could not delete ${old.name} after downloading its track again")
            return false
        }
        val newName = Uri.decode(row.documentUri).substringAfterLast('/')
        val base = COPY_SUFFIX.matchEntire(newName.substringBeforeLast('.'))?.groupValues?.get(1)
        if (base != null && base == old.name.substringBeforeLast('.')) {
            val name = "$base.${newName.substringAfterLast('.')}"
            runCatching { DocumentsContract.renameDocument(resolver, Uri.parse(row.documentUri), name) }
                .getOrNull()?.let { Downloads.put(row.copy(documentUri = it.toString())) }
        }
        // A file the downloads knew as another track no longer holds it.
        Downloads.rows.value.filter { it.documentUri == old.uri.toString() }.forEach { Downloads.remove(it.trackUri) }
        LokiLogger.i(TAG, "${old.name} replaced by ${row.artist} - ${row.title}")
        return true
    }

    /**
     * The "copies" that hold another song (#1053): one whose name names another track than its id is one a
     * wrong source hit saved, its tags and so its id that other song's. The file the downloads know for the
     * id may be one too. Each is to be downloaded again as the song its name says; the wrong copies leave
     * [copyRows], so they are not tagged as the song they hold.
     */
    suspend fun wrongSongs(
        copyRows: MutableList<DownloadedTrack>,
        copyFiles: Map<String, FoundFile>,
        rowOf: Map<String, DownloadedTrack>,
        listed: Map<String, FoundFile>,
        lookup: CatalogueLookup,
    ): List<Broken> = buildList {
        for (row in copyRows.mapTo(HashSet()) { it.trackUri }.mapNotNull { rowOf[it] }) {
            val file = listed[row.documentUri] ?: continue
            namesAnotherSong(file, row, lookup)?.let { add(Broken(file, it, "holds ${row.title}")) }
        }
        for (copy in copyRows.toList()) {
            val wrong = copyFiles[copy.documentUri]?.let { namesAnotherSong(it, copy, lookup) } ?: continue
            add(Broken(copyFiles.getValue(copy.documentUri), wrong, "holds ${copy.title}"))
            copyRows.remove(copy)
        }
    }

    /**
     * The track [file]'s name says, when that is not [copy]'s: the file holds another song than it is named
     * for. Null when the name holds the copy's title, or says no track.
     */
    private suspend fun namesAnotherSong(file: FoundFile, copy: DownloadedTrack, lookup: CatalogueLookup): String? {
        val title = lookup.info(copy.trackUri.substringAfterLast(':'))?.title ?: copy.title
        val core = TrackMatcher.normalize(title)
        if (core.isEmpty() || core in TrackMatcher.normalize(file.name.substringBeforeLast('.').replace(" - ", " "))) return null
        return byName(file, lookup)?.takeIf { "spotify:track:$it" != copy.trackUri }
    }
}
