@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package ch.snepilatch.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Person
import androidx.compose.ui.draw.clip
import androidx.compose.material3.*
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.snepilatch.app.R
import ch.snepilatch.app.ui.components.SpotifyImage
import ch.snepilatch.app.ui.components.TrackRow
import ch.snepilatch.app.ui.theme.*
import ch.snepilatch.app.util.stripHtml
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.snepilatch.app.viewmodel.ThemeController
import ch.snepilatch.app.viewmodel.DetailRoutes
import ch.snepilatch.app.viewmodel.DetailViewModel
import ch.snepilatch.app.viewmodel.PlaybackViewModel

@Composable
fun DetailScreen(vm: PlaybackViewModel) {
    val detailVm: DetailViewModel = viewModel()
    val detail by detailVm.detail.collectAsState()
    val isLoading by detailVm.isLoading.collectAsState()
    val isLoadingMore by detailVm.isLoadingMore.collectAsState()
    val theme by ThemeController.themeColors.collectAsState()
    val accentColor by androidx.compose.animation.animateColorAsState(theme.primary, androidx.compose.animation.core.tween(800), label = "detailAccent")
    val isArtist = detail.type == "artist"

    if (isLoading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            LoadingIndicator(color = SpotifyLightGray)
        }
        return
    }

    val hasMore = detail.totalCount < 0 || detail.tracks.size < detail.totalCount

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomOverlayHeight.current.value + 16.dp)) {
        // Hero image
        item {
            Box(Modifier.fillMaxWidth().height(if (isArtist) 350.dp else 300.dp)) {
                SpotifyImage(
                    url = detail.imageUrl,
                    modifier = Modifier.fillMaxSize(),
                    shape = RoundedCornerShape(0.dp)
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Brush.verticalGradient(
                            listOf(Color.Transparent, SpotifyBlack),
                            startY = if (isArtist) 400f else 150f
                        ))
                )
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(12.dp)
                        .size(36.dp)
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                        .clip(CircleShape)
                        .clickable { vm.goBack() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back), tint = SpotifyWhite, modifier = Modifier.size(20.dp))
                }
                Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                    Text(
                        detail.name,
                        color = SpotifyWhite,
                        fontSize = if (isArtist) 36.sp else 26.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Playlist metadata (owner, description, followers)
        if (detail.type == "playlist") {
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    val desc = remember(detail.description) { detail.description?.let { stripHtml(it) } }
                    if (desc != null) {
                        Text(desc, color = SpotifyLightGray, fontSize = 13.sp, maxLines = 2)
                        Spacer(Modifier.height(4.dp))
                    }
                    // Owner · songs · duration all on one line
                    val meta = mutableListOf<String>()
                    if (detail.ownerName != null) meta.add(detail.ownerName!!)
                    if (detail.followers != null && detail.followers!! > 0) {
                        val f = if (detail.followers!! >= 1_000_000) String.format("%.1fM", detail.followers!! / 1_000_000.0)
                            else if (detail.followers!! >= 1_000) String.format("%,d", detail.followers)
                            else detail.followers.toString()
                        meta.add(stringResource(R.string.detail_likes, f))
                    }
                    if (detail.tracks.isNotEmpty()) {
                        val totalMin = detail.tracks.sumOf { it.durationMs } / 60000
                        meta.add(stringResource(R.string.detail_songs, detail.totalCount.takeIf { it > 0 } ?: detail.tracks.size))
                        meta.add(stringResource(R.string.detail_minutes, totalMin.toInt()))
                    }
                    if (meta.isNotEmpty()) {
                        Text(meta.joinToString(" · "), color = SpotifyLightGray, fontSize = 13.sp)
                    }
                }
            }
        }

        // Album metadata (artist, type, date)
        if (detail.type == "album" && (detail.artistName != null || detail.albumType != null)) {
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    // Artist name (tappable)
                    if (detail.artistName != null) {
                        Text(
                            detail.artistName!!,
                            color = SpotifyWhite,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable {
                                detail.artistUri?.substringAfterLast(":")?.let { detailVm.openArtist(it) }
                            }
                        )
                    }
                    // Type + date + summary all on one line
                    val summary = remember(detail.description) { detail.description?.let { stripHtml(it) } }
                    val meta = listOfNotNull(detail.albumType, detail.releaseDate, summary).joinToString(" · ")
                    if (meta.isNotBlank()) {
                        Text(meta, color = SpotifyLightGray, fontSize = 13.sp)
                    }
                }
            }
        }

        // Show metadata (podcast): publisher + episode count
        if (detail.type == "show") {
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    if (detail.publisher != null) {
                        Text(detail.publisher!!, color = SpotifyWhite, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                    if (detail.description != null) {
                        Text(detail.description!!, color = SpotifyLightGray, fontSize = 13.sp)
                    }
                }
            }
        }

        // Monthly listeners (artist pages) — above action buttons
        if (isArtist && detail.monthlyListeners != null && detail.monthlyListeners!! > 0) {
            item {
                val formatted = when {
                    detail.monthlyListeners!! >= 1_000_000 -> String.format("%.1f Mio.", detail.monthlyListeners!! / 1_000_000.0)
                    detail.monthlyListeners!! >= 1_000 -> String.format("%,d", detail.monthlyListeners)
                    else -> detail.monthlyListeners.toString()
                }
                Text(
                    stringResource(R.string.detail_monthly_listeners, formatted),
                    color = SpotifyLightGray,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 0.dp)
                )
            }
        }

        // Action buttons row
        item {
            val playback by vm.playback.collectAsState()
            // Collect unconditionally: this collectAsState() was on the RHS of && and skipped when the
            // track was null/paused, making a @Composable call conditional.
            val playingContext by vm.playingContext.collectAsState()
            val isPlayingThis = playback.track != null && playback.isPlaying &&
                (playingContext?.let { detail.uri.contains(it.name) || detail.name == it.name } == true)
            val shuffling = playback.isShuffling
            val context = androidx.compose.ui.platform.LocalContext.current

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Save/Follow (albums & artists)
                if (detail.type == "album" || isArtist) {
                    val saved by detailVm.detailSaved.collectAsState()
                    val detailId = detail.uri
                        .removePrefix("spotify:album:")
                        .removePrefix("spotify:artist:")
                    LaunchedEffect(detail.uri) { detailVm.checkDetailSaved(detail.type, detailId) }

                    if (isArtist) {
                        OutlinedButton(
                            onClick = { detailVm.toggleDetailSaved(detail.type, detailId) },
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = SpotifyWhite
                            ),
                            border = androidx.compose.foundation.BorderStroke(1.dp, SpotifyLightGray),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text(
                                if (saved) stringResource(R.string.following) else stringResource(R.string.follow),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    } else {
                        IconToggleButton(
                            checked = saved,
                            onCheckedChange = { detailVm.toggleDetailSaved(detail.type, detailId) },
                            colors = IconButtonDefaults.iconToggleButtonColors(
                                contentColor = SpotifyWhite,
                                checkedContentColor = accentColor,
                            ),
                        ) {
                            Icon(
                                if (saved) Icons.Rounded.Favorite else Icons.Filled.FavoriteBorder,
                                stringResource(R.string.save),
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                }

                // 3-dot menu — opens a bottom sheet with every action
                // KotifyClient supports for the current detail type.
                var showHeaderMenu by remember { mutableStateOf(false) }
                IconButton(onClick = { showHeaderMenu = true }) {
                    Icon(Icons.Rounded.MoreVert, stringResource(R.string.more), tint = SpotifyLightGray, modifier = Modifier.size(24.dp))
                }
                if (showHeaderMenu) {
                    DetailHeaderMenu(
                        detail = detail,
                        vm = vm,
                        context = context,
                        onDismiss = { showHeaderMenu = false }
                    )
                }

                Spacer(Modifier.weight(1f))

                IconButton(onClick = { vm.toggleShuffle() }) {
                    Icon(
                        Icons.Rounded.Shuffle, stringResource(R.string.shuffle),
                        tint = if (shuffling) accentColor else SpotifyWhite,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(Modifier.width(8.dp))

                Box(
                    Modifier
                        .size(48.dp)
                        .background(accentColor, CircleShape)
                        .clip(CircleShape)
                        .clickable {
                            if (isPlayingThis) {
                                vm.togglePlayPause()
                            } else {
                                val first = detail.tracks.firstOrNull() ?: return@clickable
                                vm.playTrack(first, detail.uri, 0)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (isPlayingThis) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        stringResource(R.string.play),
                        tint = Color.Black,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }

        // "Popular" header for artist pages
        if (isArtist) {
            item {
                Text(
                    stringResource(R.string.popular),
                    color = SpotifyWhite,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
        }

        // Tracks
        itemsIndexed(detail.tracks, key = { index, track -> "$index-${track.uri}" }) { index, track ->
            if (hasMore && index >= detail.tracks.size - 5) {
                LaunchedEffect(detail.tracks.size) { detailVm.loadMoreDetail() }
            }
            when {
                isArtist -> ArtistTrackRow(index + 1, track, vm, detail.uri, detail.topTrackPlaycounts.getOrNull(index))
                detail.type == "album" -> AlbumTrackRow(track, vm, detail.uri, index)
                else -> TrackRow(track, vm, contextUri = detail.uri, trackIndex = index)
            }
        }

        if (isLoadingMore) {
            item {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    LoadingIndicator(color = SpotifyLightGray, modifier = Modifier.size(24.dp))
                }
            }
        }

        // Popular Releases (artist pages)
        if (isArtist && detail.popularReleases.isNotEmpty()) {
            item {
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.popular_releases),
                    color = SpotifyWhite,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            item {
                androidx.compose.foundation.lazy.LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(detail.popularReleases.size) { i ->
                        val rel = detail.popularReleases[i]
                        Column(
                            Modifier
                                .width(130.dp)
                                .clickable {
                                    val id = rel.uri.substringAfterLast(":")
                                    detailVm.openAlbum(id)
                                }
                        ) {
                            SpotifyImage(
                                url = rel.imageUrl,
                                modifier = Modifier.size(130.dp),
                                shape = RoundedCornerShape(8.dp)
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(rel.name, color = SpotifyWhite, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOfNotNull(rel.year, rel.albumType).joinToString(" · "),
                                color = SpotifyLightGray, fontSize = 11.sp, maxLines = 1
                            )
                        }
                    }
                }
            }
        }

        // Fans also like (artist pages)
        if (isArtist && detail.relatedArtists.isNotEmpty()) {
            item {
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.fans_also_like),
                    color = SpotifyWhite,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            item {
                androidx.compose.foundation.lazy.LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(detail.relatedArtists.size) { i ->
                        val ra = detail.relatedArtists[i]
                        Column(
                            Modifier
                                .width(120.dp)
                                .clickable {
                                    val id = ra.uri.substringAfterLast(":")
                                    detailVm.openArtist(id)
                                },
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            SpotifyImage(
                                url = ra.imageUrl,
                                modifier = Modifier.size(120.dp),
                                shape = CircleShape
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(ra.name, color = SpotifyWhite, fontSize = 13.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }

        // Bio (artist pages)
        if (isArtist && detail.biography != null) {
            item {
                val bio = remember(detail.biography) { stripHtml(detail.biography!!) }
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.about),
                    color = SpotifyWhite,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                Text(
                    bio,
                    color = SpotifyLightGray,
                    fontSize = 14.sp,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
        }

        // More by artist section (album pages)
        if (detail.type == "album" && detail.moreByArtist.isNotEmpty()) {
            item {
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.more_by, detail.artistName ?: stringResource(R.string.unknown)),
                    color = SpotifyWhite,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            item {
                androidx.compose.foundation.lazy.LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(detail.moreByArtist.size) { i ->
                        val rel = detail.moreByArtist[i]
                        Column(
                            Modifier
                                .width(130.dp)
                                .clickable {
                                    val id = rel.uri.substringAfterLast(":")
                                    detailVm.openAlbum(id)
                                }
                        ) {
                            SpotifyImage(
                                url = rel.imageUrl,
                                modifier = Modifier.size(130.dp),
                                shape = RoundedCornerShape(8.dp)
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(rel.name, color = SpotifyWhite, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOfNotNull(rel.year, rel.albumType).joinToString(" · "),
                                color = SpotifyLightGray, fontSize = 11.sp, maxLines = 1
                            )
                        }
                    }
                }
            }
        }

        // Copyright (album pages)
        if (detail.type == "album" && detail.copyright != null) {
            item {
                Spacer(Modifier.height(24.dp))
                Text(
                    detail.copyright!!,
                    color = SpotifyLightGray.copy(alpha = 0.6f),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArtistTrackRow(
    number: Int,
    track: ch.snepilatch.app.data.TrackInfo,
    vm: PlaybackViewModel,
    contextUri: String,
    playcount: String? = null
) {
    val detailVm: DetailViewModel = viewModel()
    val playback by vm.playback.collectAsState()
    val isPlaying = playback.track?.uri == track.uri && playback.isPlaying
    val theme by ThemeController.themeColors.collectAsState()
    val accent = theme.primary

    Row(
        Modifier
            .fillMaxWidth()
            .clickable { vm.playTrack(track, contextUri, number - 1) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "$number",
            color = if (isPlaying) accent else SpotifyLightGray,
            fontSize = 15.sp,
            modifier = Modifier.width(28.dp)
        )
        SpotifyImage(
            url = track.albumArt,
            modifier = Modifier.size(44.dp),
            shape = RoundedCornerShape(4.dp)
        )
        Spacer(Modifier.width(12.dp))
        // Track name only (no artist — we're on the artist page)
        Column(Modifier.weight(1f)) {
            Text(
                track.name,
                color = if (isPlaying) accent else SpotifyWhite,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (playcount != null) {
                val formatted = try {
                    val num = playcount.toLong()
                    String.format("%,d", num)
                } catch (_: Exception) { playcount }
                Text(formatted, color = SpotifyLightGray, fontSize = 12.sp)
            }
        }
        // 3-dot menu
        var showMenu by remember { mutableStateOf(false) }
        IconButton(onClick = { showMenu = true }, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Rounded.MoreVert, stringResource(R.string.more), tint = SpotifyLightGray, modifier = Modifier.size(20.dp))
        }
        if (showMenu) {
            val sheetState = rememberBottomSheetState(
                initialValue = SheetValue.Hidden,
                enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
            )
            ModalBottomSheet(
                onDismissRequest = { showMenu = false },
                sheetState = sheetState,
                containerColor = SpotifyElevated,
                dragHandle = {
                    Box(
                        Modifier
                            .padding(vertical = 12.dp)
                            .width(40.dp)
                            .height(4.dp)
                            .background(SpotifyLightGray.copy(alpha = 0.4f), RoundedCornerShape(2.dp))
                    )
                }
            ) {
                ch.snepilatch.app.ui.components.SheetNavBarFix()
                // Track header
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SpotifyImage(url = track.albumArt, modifier = Modifier.size(48.dp), shape = RoundedCornerShape(8.dp))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(track.name, color = SpotifyWhite, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(track.artist, color = SpotifyLightGray, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = SpotifyLightGray.copy(alpha = 0.15f))

                val menuContext = androidx.compose.ui.platform.LocalContext.current
                val shareLabel = stringResource(R.string.share)
                val addQueueLabel = stringResource(R.string.add_to_queue)
                val addPlaylistLabel = stringResource(R.string.add_to_playlist)
                val likeLabel = stringResource(R.string.like)
                val visitAlbumLabel = stringResource(R.string.visit_album)
                val items = listOf(
                    Triple(Icons.AutoMirrored.Rounded.QueueMusic, addQueueLabel) {
                        vm.addToQueue(track.uri); showMenu = false
                    },
                    Triple(Icons.AutoMirrored.Rounded.PlaylistAdd, addPlaylistLabel) {
                        showMenu = false; vm.showPlaylistPickerForTrack(track.uri)
                    },
                    Triple(Icons.Rounded.Favorite, likeLabel) {
                        vm.likeSong(track.uri.removePrefix("spotify:track:")); showMenu = false
                    },
                    Triple(Icons.Rounded.Album, visitAlbumLabel) {
                        showMenu = false; detailVm.openAlbumForTrack(track.uri)
                    },
                    Triple(Icons.Rounded.Share, shareLabel) {
                        showMenu = false
                        val id = track.uri.removePrefix("spotify:track:")
                        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(android.content.Intent.EXTRA_TEXT, "https://open.spotify.com/track/$id")
                        }
                        menuContext.startActivity(android.content.Intent.createChooser(intent, shareLabel))
                    }
                )

                items.forEach { (icon, label, onClick) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onClick() }
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(icon, null, tint = SpotifyWhite, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(16.dp))
                        Text(label, color = SpotifyWhite, fontSize = 15.sp)
                    }
                }
                Spacer(Modifier.navigationBarsPadding().height(12.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlbumTrackRow(
    track: ch.snepilatch.app.data.TrackInfo,
    vm: PlaybackViewModel,
    contextUri: String,
    trackIndex: Int? = null
) {
    val detailVm: DetailViewModel = viewModel()
    val playback by vm.playback.collectAsState()
    val isPlaying = playback.track?.uri == track.uri && playback.isPlaying
    val theme by ThemeController.themeColors.collectAsState()
    val accent = theme.primary

    Row(
        Modifier
            .fillMaxWidth()
            .clickable { vm.playTrack(track, contextUri, trackIndex) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                track.name,
                color = if (isPlaying) accent else SpotifyWhite,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (track.artist.isNotBlank()) {
                Text(
                    track.artist,
                    color = SpotifyLightGray,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        var showMenu by remember { mutableStateOf(false) }
        IconButton(onClick = { showMenu = true }, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Rounded.MoreVert, stringResource(R.string.more), tint = SpotifyLightGray, modifier = Modifier.size(20.dp))
        }
        if (showMenu) {
            val sheetState = rememberBottomSheetState(
                initialValue = SheetValue.Hidden,
                enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
            )
            ModalBottomSheet(
                onDismissRequest = { showMenu = false },
                sheetState = sheetState,
                containerColor = SpotifyElevated,
                dragHandle = {
                    Box(
                        Modifier
                            .padding(vertical = 12.dp)
                            .width(40.dp)
                            .height(4.dp)
                            .background(SpotifyLightGray.copy(alpha = 0.4f), RoundedCornerShape(2.dp))
                    )
                }
            ) {
                ch.snepilatch.app.ui.components.SheetNavBarFix()
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SpotifyImage(url = track.albumArt, modifier = Modifier.size(48.dp), shape = RoundedCornerShape(8.dp))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(track.name, color = SpotifyWhite, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(track.artist, color = SpotifyLightGray, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = SpotifyLightGray.copy(alpha = 0.15f))
                val menuContext = androidx.compose.ui.platform.LocalContext.current
                val shareLabel = stringResource(R.string.share)
                val addQueueLabel = stringResource(R.string.add_to_queue)
                val addPlaylistLabel = stringResource(R.string.add_to_playlist)
                val likeLabel = stringResource(R.string.like)
                val visitArtistLabel = stringResource(R.string.visit_artist)
                val items = listOf(
                    Triple(Icons.AutoMirrored.Rounded.QueueMusic, addQueueLabel) {
                        vm.addToQueue(track.uri); showMenu = false
                    },
                    Triple(Icons.AutoMirrored.Rounded.PlaylistAdd, addPlaylistLabel) {
                        showMenu = false; vm.showPlaylistPickerForTrack(track.uri)
                    },
                    Triple(Icons.Rounded.Favorite, likeLabel) {
                        vm.likeSong(track.uri.removePrefix("spotify:track:")); showMenu = false
                    },
                    Triple(Icons.Rounded.Person, visitArtistLabel) {
                        showMenu = false; detailVm.openArtistForTrack(track.uri)
                    },
                    Triple(Icons.Rounded.Share, shareLabel) {
                        showMenu = false
                        val id = track.uri.removePrefix("spotify:track:")
                        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(android.content.Intent.EXTRA_TEXT, "https://open.spotify.com/track/$id")
                        }
                        menuContext.startActivity(android.content.Intent.createChooser(intent, shareLabel))
                    }
                )
                items.forEach { (icon, label, onClick) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onClick() }
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(icon, null, tint = SpotifyWhite, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(16.dp))
                        Text(label, color = SpotifyWhite, fontSize = 15.sp)
                    }
                }
                Spacer(Modifier.navigationBarsPadding().height(12.dp))
            }
        }
    }
}

/**
 * Bottom-sheet menu opened from the 3-dot button on the detail header
 * (album, playlist, artist, Liked Songs). Each row is a single action that
 * KotifyClient actually supports — no Premium-gated entries, no UI-only
 * "show Spotify Code" filler. Action set adapts to the detail type.
 *
 * Skips "Remove from Library" because the like/save toggle already lives
 * elsewhere in the header.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailHeaderMenu(
    detail: ch.snepilatch.app.data.DetailData,
    vm: ch.snepilatch.app.viewmodel.PlaybackViewModel,
    context: android.content.Context,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    val trackUris = detail.tracks.map { it.uri }; val hasTracks = trackUris.isNotEmpty(); val type = detail.type
    val isAlbum = type == "album"; val isCollection = type == "collection"; val isArtist = type == "artist"
    val shareLabel = stringResource(R.string.share); val addToQueueLabel = stringResource(R.string.add_to_queue)
    val addToPlaylistLabel = stringResource(R.string.add_to_playlist); val visitArtistLabel = stringResource(R.string.visit_artist)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SpotifyElevated,
    ) {
        ch.snepilatch.app.ui.components.SheetNavBarFix()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ch.snepilatch.app.ui.components.SpotifyImage(
                url = detail.imageUrl,
                modifier = Modifier.size(48.dp),
                shape = RoundedCornerShape(8.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(detail.name, color = SpotifyWhite, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val subtitle = detail.artistName ?: detail.ownerName ?: type.replaceFirstChar { it.uppercase() }
                Text(subtitle, color = SpotifyLightGray, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = SpotifyLightGray.copy(alpha = 0.15f))
        val items = buildList<Triple<androidx.compose.ui.graphics.vector.ImageVector, String, () -> Unit>> {
            if (!isCollection) {
                val id = detail.uri.substringAfterLast(":")
                add(Triple(Icons.Rounded.Share, shareLabel) {
                    onDismiss()
                    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        this.type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_TEXT, "https://open.spotify.com/$type/$id")
                    }
                    context.startActivity(android.content.Intent.createChooser(intent, shareLabel))
                })
            }
            if (hasTracks && !isArtist) {
                add(Triple(Icons.AutoMirrored.Rounded.QueueMusic, addToQueueLabel) {
                    onDismiss()
                    vm.addAllToQueue(trackUris)
                })
                add(Triple(Icons.AutoMirrored.Rounded.PlaylistAdd, addToPlaylistLabel) {
                    onDismiss()
                    vm.showPlaylistPickerForTracks(trackUris)
                })
            }
            if (isAlbum && detail.artistUri != null) {
                add(Triple(Icons.Rounded.Person, visitArtistLabel) {
                    onDismiss()
                    val artistId = detail.artistUri.substringAfterLast(":")
                    DetailRoutes.openArtist(artistId)
                })
            }
        }
        items.forEach { (icon, label, onClick) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onClick() }
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(icon, null, tint = SpotifyWhite, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(16.dp))
                Text(label, color = SpotifyWhite, fontSize = 15.sp)
            }
        }
        Spacer(Modifier.navigationBarsPadding().height(12.dp))
    }
}
