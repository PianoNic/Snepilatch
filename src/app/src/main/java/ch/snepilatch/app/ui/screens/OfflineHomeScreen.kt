package ch.snepilatch.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.snepilatch.app.R
import ch.snepilatch.app.logic.download.DownloadedTrack
import ch.snepilatch.app.logic.download.Downloads
import ch.snepilatch.app.logic.download.offlineAlbum
import ch.snepilatch.app.logic.download.toTrackInfo
import ch.snepilatch.app.ui.shared.TrackRow
import ch.snepilatch.app.ui.theme.SnepilatchGray
import ch.snepilatch.app.ui.theme.SnepilatchLightGray
import ch.snepilatch.app.ui.theme.SnepilatchWhite
import ch.snepilatch.app.viewmodel.PlaybackViewModel
import coil.compose.AsyncImage

/** One album among the downloads (#1038): grouped by the album and album artist their files' tags name. */
private class OfflineAlbum(val key: String, val name: String, val artist: String, val cover: String?, val tracks: List<DownloadedTrack>)

@Composable
fun OfflineHomeScreen(vm: PlaybackViewModel) {
    val stored by Downloads.rows.collectAsState()
    val albums = remember(stored) {
        stored.filter { it.offlineAlbum() != null }
            .groupBy { it.offlineAlbum()!! }
            .map { (key, rows) ->
                val sorted = rows.sortedWith(compareBy({ it.discNumber }, { it.trackNumber }))
                val first = sorted.first()
                val artist = first.albumArtist?.ifBlank { null } ?: first.artist
                OfflineAlbum(key, first.album.orEmpty(), artist, sorted.firstNotNullOfOrNull { row -> row.coverUrl }, sorted)
            }
            .sortedBy { it.name.lowercase() }
    }
    var openAlbum by rememberSaveable { mutableStateOf<String?>(null) }
    val open = albums.firstOrNull { it.key == openAlbum }
    BackHandler(open != null) { openAlbum = null }

    if (stored.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.offline_no_downloads),
                color = SnepilatchLightGray,
                fontSize = 15.sp,
                textAlign = TextAlign.Center
            )
        }
        return
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = LocalBottomOverlayHeight.current.value + 16.dp)
    ) {
        if (open != null) {
            item {
                Row(Modifier.padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { openAlbum = null }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back), tint = SnepilatchWhite)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(open.name, color = SnepilatchWhite, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(open.artist, color = SnepilatchLightGray, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            // The album as its context, so playing a track goes on through the album in its order.
            items(open.tracks, key = { it.trackUri }) { row -> TrackRow(row.toTrackInfo(), vm, contextUri = open.key) }
            return@LazyColumn
        }
        item { Heading(stringResource(R.string.offline_while_youre_offline)) }
        if (albums.isNotEmpty()) {
            item { SubHeading(stringResource(R.string.offline_albums)) }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(albums, key = { it.key }) { album -> AlbumCard(album) { openAlbum = album.key } }
                }
            }
            item { SubHeading(stringResource(R.string.offline_all_tracks)) }
        }
        items(stored, key = { it.trackUri }) { row -> TrackRow(row.toTrackInfo(), vm) }
    }
}

@Composable
private fun Heading(text: String) = Text(
    text,
    color = SnepilatchWhite,
    fontSize = 22.sp,
    fontWeight = FontWeight.Bold,
    modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)
)

@Composable
private fun SubHeading(text: String) = Text(
    text,
    color = SnepilatchWhite,
    fontSize = 17.sp,
    fontWeight = FontWeight.SemiBold,
    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)
)

@Composable
private fun AlbumCard(album: OfflineAlbum, onClick: () -> Unit) {
    Column(Modifier.width(ALBUM_CARD).clickable(onClick = onClick)) {
        AsyncImage(
            model = album.cover,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(ALBUM_CARD).clip(RoundedCornerShape(8.dp)).background(SnepilatchGray),
        )
        Spacer(Modifier.height(6.dp))
        Text(album.name, color = SnepilatchWhite, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(album.artist, color = SnepilatchLightGray, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private val ALBUM_CARD = 132.dp
