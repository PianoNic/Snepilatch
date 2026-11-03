package ch.snepilatch.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.snepilatch.app.R
import ch.snepilatch.app.logic.download.DownloadedTrack
import ch.snepilatch.app.logic.download.Downloads
import ch.snepilatch.app.logic.download.offlineAlbum
import ch.snepilatch.app.logic.download.toTrackInfo
import ch.snepilatch.app.logic.playback.RecentPlays
import ch.snepilatch.app.logic.shared.ThemeController
import ch.snepilatch.app.logic.shared.spfyId
import ch.snepilatch.app.ui.shared.Avatar
import ch.snepilatch.app.ui.shared.LocalOpenProfileMenu
import ch.snepilatch.app.ui.shared.SpfyImage
import ch.snepilatch.app.ui.shared.TrackRow
import ch.snepilatch.app.ui.theme.SnepilatchBlack
import ch.snepilatch.app.ui.theme.SnepilatchGray
import ch.snepilatch.app.ui.theme.SnepilatchLightGray
import ch.snepilatch.app.ui.theme.SnepilatchWhite
import ch.snepilatch.app.viewmodel.DetailViewModel
import ch.snepilatch.app.viewmodel.PlaybackViewModel

/** One album among the downloads (#1038): grouped by the album and album artist their files' tags name. */
private class OfflineAlbum(val key: String, val name: String, val artist: String, val cover: String?, val tracks: List<DownloadedTrack>)

/** A list on the phone, as the quick picks and tiles show it. */
private class OfflineTile(val key: String, val name: String, val subtitle: String?, val cover: String?, val open: () -> Unit)

private enum class OfflineFilter(@StringRes val label: Int) {
    ALL(R.string.home_filter_all),
    PLAYLISTS(R.string.library_filter_playlists),
    ALBUMS(R.string.offline_albums),
    SONGS(R.string.offline_all_tracks),
}

private const val QUICK_PICKS = 8
private const val RECENT_ROW = 20
private const val PLAYLIST_TYPES = "playlist collection"

/** The offline home, laid out as the online one: header and chips, quick picks, rows of tiles. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OfflineHomeScreen(vm: PlaybackViewModel) {
    val detailVm: DetailViewModel = viewModel()
    val stored by Downloads.rows.collectAsState()
    val recentTracks by RecentPlays.tracks.collectAsState()
    val recentContexts by RecentPlays.contexts.collectAsState()
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
    var filter by rememberSaveable { mutableStateOf(OfflineFilter.ALL) }
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

    val playlists = remember(stored) {
        Downloads.groupsOf(stored).filter { it.type in PLAYLIST_TYPES.split(" ") }.map { group ->
            OfflineTile(group.uri, group.name, null, group.imageUrl) {
                if (group.type == "collection") detailVm.openLikedSongs() else detailVm.openPlaylist(spfyId(group.uri))
            }
        }
    }
    val albumTiles = remember(albums) {
        albums.map { album -> OfflineTile(album.key, album.name, album.artist, album.cover) { openAlbum = album.key } }
    }
    val byUri = remember(stored) { stored.associateBy { it.trackUri } }
    val recentRows = remember(recentTracks, byUri) { recentTracks.mapNotNull(byUri::get).take(RECENT_ROW) }
    // Nothing played yet on this phone: what arrived last says the most about what is wanted.
    val playedAny = recentRows.isNotEmpty()
    val songRow = remember(recentRows, stored) {
        if (playedAny) recentRows else stored.sortedByDescending { it.downloadedAt }.take(RECENT_ROW)
    }
    val quickPicks = remember(recentContexts, playlists, albumTiles) {
        val lists = playlists + albumTiles
        val played = recentContexts.mapNotNull { uri -> lists.firstOrNull { it.key == uri } }
        (played + lists.filterNot { it in played }).take(QUICK_PICKS)
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = LocalBottomOverlayHeight.current.value + 16.dp)
    ) {
        if (open != null) {
            openedAlbum(open, vm) { openAlbum = null }
            return@LazyColumn
        }
        stickyHeader { OfflineHeader(vm, filter) { filter = it } }
        when (filter) {
            OfflineFilter.ALL -> wholeHome(quickPicks, playedAny, songRow, playlists, albumTiles, stored, vm)
            OfflineFilter.PLAYLISTS -> items(playlists, key = { it.key }) { ListRow(it) }
            OfflineFilter.ALBUMS -> items(albumTiles, key = { it.key }) { ListRow(it) }
            OfflineFilter.SONGS -> items(stored, key = { it.trackUri }) { row -> TrackRow(row.toTrackInfo(), vm) }
        }
    }
}

private fun LazyListScope.wholeHome(
    quickPicks: List<OfflineTile>,
    playedAny: Boolean,
    songRow: List<DownloadedTrack>,
    playlists: List<OfflineTile>,
    albums: List<OfflineTile>,
    stored: List<DownloadedTrack>,
    vm: PlaybackViewModel,
) {
    if (quickPicks.isNotEmpty()) item { OfflineQuickPicks(quickPicks) }
    item {
        SectionTitle(stringResource(if (playedAny) R.string.offline_recently_played else R.string.offline_recently_downloaded))
    }
    item { SongTiles(songRow, vm) }
    if (playlists.isNotEmpty()) {
        item { SectionTitle(stringResource(R.string.library_filter_playlists)) }
        item { ListTiles(playlists) }
    }
    if (albums.isNotEmpty()) {
        item { SectionTitle(stringResource(R.string.offline_albums)) }
        item { ListTiles(albums) }
    }
    item { SectionTitle(stringResource(R.string.offline_all_tracks)) }
    items(stored, key = { it.trackUri }) { row -> TrackRow(row.toTrackInfo(), vm) }
}

private fun LazyListScope.openedAlbum(open: OfflineAlbum, vm: PlaybackViewModel, onBack: () -> Unit) {
    item {
        Row(Modifier.padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back), tint = SnepilatchWhite)
            }
            Column(Modifier.weight(1f)) {
                Text(open.name, color = SnepilatchWhite, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(open.artist, color = SnepilatchLightGray, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    // The album as its context, so playing a track goes on through the album in its order.
    items(open.tracks, key = { it.trackUri }) { row -> TrackRow(row.toTrackInfo(), vm, contextUri = open.key) }
}

/** The online home's header: the profile picture, then the chips, which here sort the downloads. */
@Composable
private fun OfflineHeader(vm: PlaybackViewModel, filter: OfflineFilter, onFilter: (OfflineFilter) -> Unit) {
    val account by vm.account.collectAsState()
    val openMenu = LocalOpenProfileMenu.current
    val accent = ThemeController.themeColors.collectAsState().value.primary
    Row(
        Modifier.fillMaxWidth().background(SnepilatchBlack).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(12.dp))
        IconButton(onClick = openMenu, modifier = Modifier.size(40.dp)) {
            Avatar(account.profileImageUrl, 34.dp)
        }
        LazyRow(contentPadding = PaddingValues(start = 6.dp, end = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(OfflineFilter.entries.toList(), key = { it.name }) { chip ->
                val chosen = chip == filter
                val behind = if (chosen) accent else SnepilatchGray
                Box(
                    Modifier.height(32.dp).clip(RoundedCornerShape(16.dp)).background(behind)
                        .clickable { onFilter(chip) }.padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(chip.label),
                        // A dark accent takes white text, a light one black (#1055).
                        color = if (behind.luminance() > HALF_LUMINANCE) Color.Black else SnepilatchWhite,
                        fontSize = 14.sp,
                        fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

private const val HALF_LUMINANCE = 0.5f

@Composable
private fun OfflineQuickPicks(tiles: List<OfflineTile>) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { tile ->
                    Row(
                        Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(6.dp)).background(SnepilatchGray).clickable(onClick = tile.open),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SpfyImage(url = tile.cover, modifier = Modifier.size(48.dp), shape = RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp))
                        Text(
                            tile.name,
                            color = SnepilatchWhite,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) = Text(
    text,
    color = SnepilatchWhite,
    fontSize = 20.sp,
    fontWeight = FontWeight.Bold,
    modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 10.dp)
)

@Composable
private fun ListTiles(tiles: List<OfflineTile>) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(tiles, key = { it.key }) { tile -> Tile(tile.name, tile.subtitle, tile.cover, tile.open) }
    }
}

@Composable
private fun SongTiles(rows: List<DownloadedTrack>, vm: PlaybackViewModel) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(rows, key = { it.trackUri }) { row ->
            Tile(row.title, row.artist, row.coverUrl) { vm.playTrack(row.toTrackInfo(), row.contextUri) }
        }
    }
}

/** The online home's tile. */
@Composable
private fun Tile(name: String, subtitle: String?, cover: String?, onClick: () -> Unit) {
    Column(Modifier.width(TILE).clickable(onClick = onClick)) {
        SpfyImage(url = cover, modifier = Modifier.size(TILE), shape = RoundedCornerShape(8.dp))
        Spacer(Modifier.height(8.dp))
        Text(name, color = SnepilatchWhite, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (subtitle != null) {
            Text(subtitle, color = SnepilatchLightGray, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ListRow(tile: OfflineTile) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = tile.open).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SpfyImage(url = tile.cover, modifier = Modifier.size(48.dp), shape = RoundedCornerShape(4.dp))
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(tile.name, color = SnepilatchWhite, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (tile.subtitle != null) {
                Text(tile.subtitle, color = SnepilatchLightGray, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private val TILE = 140.dp
