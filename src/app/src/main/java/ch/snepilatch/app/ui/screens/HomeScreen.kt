package ch.snepilatch.app.ui.screens

import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import ch.snepilatch.app.R
import ch.snepilatch.app.data.LIKED_SONGS_COVER_URL
import ch.snepilatch.app.ui.theme.SpfyWhite
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.snepilatch.app.ui.components.ShimmerBox
import ch.snepilatch.app.ui.components.EntityMenuSheet
import ch.snepilatch.app.ui.components.MenuAction
import ch.snepilatch.app.ui.components.SpfyImage
import ch.snepilatch.app.ui.theme.SpfyCardBg
import ch.snepilatch.app.ui.theme.SpfyLightGray
import ch.snepilatch.app.util.CardColors
import ch.snepilatch.app.util.extractCardColorsFromArt
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.snepilatch.app.viewmodel.DetailViewModel
import ch.snepilatch.app.viewmodel.HomeViewModel
import ch.snepilatch.app.viewmodel.LibraryViewModel
import ch.snepilatch.app.viewmodel.PlaybackViewModel

// --- Home Screen ---

@Composable
fun HomeScreen(vm: PlaybackViewModel) {
    val homeVm: HomeViewModel = viewModel()
    val homeData by homeVm.homeData.collectAsState()
    val isHomeLoading by homeVm.isLoading.collectAsState()

    if (isHomeLoading && homeData == null) {
        HomeShimmer()
        return
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 8.dp, bottom = LocalBottomOverlayHeight.current.value + 16.dp)
    ) {
        item {
            Text(
                homeData?.greeting ?: stringResource(R.string.greeting_fallback),
                color = SpfyWhite,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        // Quick-pick grid (first section as compact grid)
        val firstSection = homeData?.sections?.firstOrNull()
        if (firstSection != null && firstSection.items.isNotEmpty()) {
            item {
                QuickPickGrid(firstSection.items.take(8), vm)
            }
        }

        homeData?.sections?.drop(1)?.forEach { section ->
            if (section.items.isNotEmpty()) {
                item {
                    Text(
                        section.title,
                        color = SpfyWhite,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 10.dp)
                    )
                }
                // A section holding one thing reads as a lone tile in a row that goes nowhere, so it
                // gets a card wide enough to say what it is instead.
                if (section.items.size == 1) {
                    item { HomeSingleCard(section.items.first(), vm) }
                    return@forEach
                }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // No per-card entrance animation: in a LazyRow-inside-LazyColumn the cards
                        // are disposed/recomposed every time they scroll into view, so an Animatable
                        // fade+slide re-ran (and the offset{} relayed out per frame) on every scroll —
                        // that was the homepage jank. Keys keep item identity stable across scroll.
                        // ponytail: index in the key — some feed items come back with a blank
                        // uri, and two of those made Compose throw on a duplicate key.
                        itemsIndexed(section.items, key = { i, item -> "$i-${item.uri}" }) { _, item ->
                            HomeSectionCard(item, vm)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun QuickPickGrid(items: List<kotify.api.home.HomeSectionItem>, vm: PlaybackViewModel) {
    val detailVm: DetailViewModel = viewModel()
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items.chunked(2).forEach { row ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                row.forEach { item ->
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .clickable {
                                val id = item.uri.split(":").lastOrNull() ?: return@clickable
                                when (item.type) {
                                    "collection" -> detailVm.openLikedSongs()
                                    "playlist" -> detailVm.openPlaylist(id)
                                    "album" -> detailVm.openAlbum(id)
                                    "artist" -> detailVm.openArtist(id)
                                    "show" -> detailVm.openShow(id, item.owner, item.imageUrl)
                                    else -> vm.playTrack(item.uri)
                                }
                            },
                        shape = RoundedCornerShape(6.dp),
                        colors = CardDefaults.cardColors(containerColor = SpfyCardBg)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SpfyImage(
                                url = homeArt(item),
                                modifier = Modifier.size(48.dp),
                                shape = RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp)
                            )
                            Text(
                                homeLabel(item),
                                color = SpfyWhite,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 8.dp)
                            )
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun HomeShimmer() {
    // One shared transition drives every placeholder; ShimmerBox reads phase in the draw phase, so the
    // sweep animates without recomposing any box. Keep `phase` as State<Float> (no `by`, never read
    // .value here) so this composable is not invalidated each frame.
    val phase = rememberInfiniteTransition(label = "shimmer").animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)),
        label = "shimmer"
    )
    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        ShimmerBox(phase, Modifier.width(180.dp).height(28.dp).clip(RoundedCornerShape(4.dp)))
        Spacer(Modifier.height(16.dp))
        repeat(3) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(2) {
                    ShimmerBox(
                        phase,
                        Modifier
                            .weight(1f)
                            .height(56.dp)
                            .clip(RoundedCornerShape(6.dp))
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        Spacer(Modifier.height(24.dp))
        ShimmerBox(phase, Modifier.width(150.dp).height(22.dp).clip(RoundedCornerShape(4.dp)))
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(3) {
                Column {
                    ShimmerBox(phase, Modifier.size(140.dp).clip(RoundedCornerShape(8.dp)))
                    Spacer(Modifier.height(8.dp))
                    ShimmerBox(phase, Modifier.width(100.dp).height(14.dp).clip(RoundedCornerShape(4.dp)))
                }
            }
        }
    }
}

/**
 * The card's three colours, read off the cover. Until that read lands (and if it fails) the colour
 * the feed states for the artwork stands in, so the card never flashes grey first.
 */
@Composable
private fun homeCardColors(item: kotify.api.home.HomeSectionItem): CardColors {
    val stated = remember(item.accentColor) {
        item.accentColor
            ?.let { hex -> runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrNull() }
            ?.let { lerp(it, Color.Black, 0.3f) }
    } ?: SpfyCardBg
    val art = homeArt(item)
    val context = LocalContext.current
    val palette by produceState<CardColors?>(null, art) {
        value = art?.let { runCatching { extractCardColorsFromArt(context, it) }.getOrNull() }
    }
    return palette ?: CardColors(
        base = stated,
        glow = lerp(stated, Color.White, 0.15f),
        counterGlow = lerp(stated, Color.Black, 0.35f)
    )
}

/**
 * A section holding a single entity, given room to describe itself: cover, name, owner, how many
 * tracks it holds, and a play button. Tinted with the colour the feed states for the artwork, so it
 * matches the cover without sampling it.
 */
@Composable
fun HomeSingleCard(item: kotify.api.home.HomeSectionItem, vm: PlaybackViewModel) {
    val detailVm: DetailViewModel = viewModel()
    val (base, glow, counterGlow) = homeCardColors(item)
    val open = {
        val id = item.uri.substringAfterLast(":")
        when (item.type) {
            "collection" -> detailVm.openLikedSongs()
            "album" -> detailVm.openAlbum(id)
            "artist" -> detailVm.openArtist(id)
            "show" -> detailVm.openShow(id, item.owner, item.imageUrl)
            else -> detailVm.openPlaylist(id)
        }
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clickable(onClick = open),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = base)
    ) {
        Column(
            Modifier
                // Light blooming out of the cover, not a ramp across the card: a wide glow anchored
                // on the artwork, a cooler one thrown back from the far corner, and a scrim down the
                // bottom so the white play button still reads against it.
                .drawBehind {
                    drawRect(
                        Brush.radialGradient(
                            listOf(glow.copy(alpha = 0.55f), glow.copy(alpha = 0f)),
                            center = Offset(size.width * 0.16f, size.height * 0.26f),
                            radius = size.width * 0.78f
                        )
                    )
                    drawRect(
                        Brush.radialGradient(
                            listOf(counterGlow.copy(alpha = 0.5f), counterGlow.copy(alpha = 0f)),
                            center = Offset(size.width, size.height * 1.05f),
                            radius = size.width * 0.7f
                        )
                    )
                    drawRect(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.22f))
                        )
                    )
                }
                .padding(14.dp)
        ) {
            Row {
                SpfyImage(
                    url = homeArt(item),
                    modifier = Modifier.size(80.dp),
                    shape = RoundedCornerShape(6.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f).padding(top = 6.dp)) {
                    Text(
                        homeLabel(item),
                        color = SpfyWhite,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    item.owner?.takeIf { it.isNotBlank() }?.let { owner ->
                        Text(owner, color = SpfyLightGray, fontSize = 13.sp, maxLines = 1)
                    }
                }
                HomeCardMenu(item, vm)
            }
            HomeCardMeta(item)
            Spacer(Modifier.height(14.dp))
            HomeCardActions(item, vm)
        }
    }
}

/**
 * Play or pause this entity, and save it. The play button follows the transport: if this is what is
 * playing, it offers to pause rather than restarting from the top.
 */
@Composable
private fun HomeCardActions(item: kotify.api.home.HomeSectionItem, vm: PlaybackViewModel) {
    val playing by vm.isPlayingFlow.collectAsState()
    val context by vm.playingContext.collectAsState()
    val isThis = context?.uri == item.uri && playing
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (item.type != "collection") {
            HomeCardLike(item, vm)
            Spacer(Modifier.width(12.dp))
        }
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(SpfyWhite)
                .clickable { if (isThis) vm.togglePlayPause() else vm.playTrack(item.uri) },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (isThis) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                stringResource(if (isThis) R.string.pause else R.string.play),
                tint = Color.Black,
                modifier = Modifier.size(32.dp)
            )
        }
    }
}

/** The same action sheet the detail header opens, for an entity reached straight from the feed. */
@Composable
private fun HomeCardMenu(item: kotify.api.home.HomeSectionItem, vm: PlaybackViewModel) {
    val detailVm: DetailViewModel = viewModel()
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }
    IconButton(onClick = { showMenu = true }, modifier = Modifier.size(40.dp)) {
        Icon(Icons.Rounded.MoreVert, stringResource(R.string.more), tint = SpfyWhite, modifier = Modifier.size(26.dp))
    }
    if (!showMenu) return
    val shareLabel = stringResource(R.string.share)
    val actions = buildList {
        if (item.type != "collection") {
            add(
                MenuAction(Icons.Rounded.AddCircleOutline, stringResource(R.string.save_to_library)) {
                    showMenu = false
                    vm.saveToLibrary(item.type, item.uri.substringAfterLast(":"))
                }
            )
        }
        if (item.type != "artist") {
            add(
                MenuAction(Icons.AutoMirrored.Rounded.QueueMusic, stringResource(R.string.add_to_queue)) {
                    showMenu = false
                    detailVm.fetchTrackUris(item.uri) { vm.addAllToQueue(it) }
                }
            )
            add(
                MenuAction(Icons.AutoMirrored.Rounded.PlaylistAdd, stringResource(R.string.add_to_playlist)) {
                    showMenu = false
                    detailVm.fetchTrackUris(item.uri) { vm.showPlaylistPickerForTracks(it) }
                }
            )
        }
        add(
            MenuAction(Icons.Rounded.Share, shareLabel) {
                showMenu = false
                val parts = item.uri.split(":")
                val link = "https://open.spotify.com/${parts.getOrNull(1)}/${parts.lastOrNull()}"
                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(android.content.Intent.EXTRA_TEXT, link)
                }
                context.startActivity(android.content.Intent.createChooser(intent, shareLabel))
            }
        )
    }
    EntityMenuSheet(
        imageUrl = homeArt(item),
        title = homeLabel(item),
        subtitle = item.owner?.takeIf { it.isNotBlank() },
        actions = actions,
        onDismiss = { showMenu = false }
    )
}

/** How many tracks the entity holds, then who is on it, in the card's own line under the cover. */
@Composable
private fun HomeCardMeta(item: kotify.api.home.HomeSectionItem) {
    val count = item.trackCount?.let { stringResource(R.string.home_card_songs, it) }
    // Playlists carry no artist list in the feed; a radio playlist names its artists in its
    // description instead, which is the same line the reference client puts here. Descriptions are
    // HTML and link their seed playlists, so the markup has to come off before it is shown. On an
    // album the artist is also the owner, and saying it twice reads as a bug.
    val artists = item.artists?.takeIf { it.isNotEmpty() }?.joinToString(", ")
        ?: remember(item.description) {
            item.description?.takeIf { it.isNotBlank() }?.let {
                android.text.Html.fromHtml(it, android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim()
            }?.takeIf { it.isNotBlank() }
        }
    val shown = artists?.takeIf { !it.equals(item.owner, ignoreCase = true) }
    if (count == null && shown == null) return
    Spacer(Modifier.height(14.dp))
    Text(
        buildAnnotatedString {
            if (count != null) {
                withStyle(SpanStyle(color = SpfyWhite, fontWeight = FontWeight.Bold)) { append(count) }
            }
            if (count != null && shown != null) append(" • ")
            if (shown != null) append(shown)
        },
        color = SpfyLightGray,
        fontSize = 13.sp,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
    )
}

/**
 * Saves the entity to the library. The save call does not refresh the library list, so the tap is
 * also held locally, otherwise the heart stays hollow until the next load.
 */
@Composable
private fun HomeCardLike(item: kotify.api.home.HomeSectionItem, vm: PlaybackViewModel) {
    val libraryVm: LibraryViewModel = viewModel()
    val library by libraryVm.library.collectAsState()
    var tapped by remember(item.uri) { mutableStateOf<Boolean?>(null) }
    val saved = library.any { it.uri == item.uri }
    val liked = tapped ?: saved
    IconButton(onClick = {
        val id = item.uri.substringAfterLast(":")
        if (liked) {
            vm.removeFromLibrary(item.type, id) { libraryVm.loadLibrary() }
        } else {
            vm.saveToLibrary(item.type, id) { libraryVm.loadLibrary() }
        }
        tapped = !liked
    }) {
        Icon(
            if (liked) Icons.Rounded.CheckCircle else Icons.Rounded.AddCircleOutline,
            stringResource(R.string.save_to_library),
            tint = SpfyWhite,
            modifier = Modifier.size(30.dp)
        )
    }
}

/**
 * The saved songs entry arrives from the feed as a uri and nothing else, so its label and cover come
 * from here rather than from the payload.
 */
@Composable
private fun homeLabel(item: kotify.api.home.HomeSectionItem): String =
    if (item.type == "collection") stringResource(R.string.liked_songs) else item.name

private fun homeArt(item: kotify.api.home.HomeSectionItem): String? =
    if (item.type == "collection") LIKED_SONGS_COVER_URL else item.imageUrl

@Composable
fun HomeSectionCard(item: kotify.api.home.HomeSectionItem, vm: PlaybackViewModel, modifier: Modifier = Modifier) {
    val detailVm: DetailViewModel = viewModel()
    val isArtist = item.type == "artist"
    Column(
        modifier
            .width(140.dp)
            .clickable {
                val id = item.uri.split(":").lastOrNull() ?: return@clickable
                when (item.type) {
                    "collection" -> detailVm.openLikedSongs()
                    "playlist" -> detailVm.openPlaylist(id)
                    "album" -> detailVm.openAlbum(id)
                    "artist" -> detailVm.openArtist(id)
                    "show" -> detailVm.openShow(id, item.owner, item.imageUrl)
                    else -> vm.playTrack(item.uri)
                }
            },
        horizontalAlignment = if (isArtist) Alignment.CenterHorizontally else Alignment.Start
    ) {
        SpfyImage(
            url = homeArt(item),
            modifier = Modifier.size(140.dp),
            shape = if (isArtist) CircleShape else RoundedCornerShape(8.dp),
            icon = if (isArtist) Icons.Rounded.Person else Icons.Rounded.MusicNote
        )
        Spacer(Modifier.height(8.dp))
        Text(
            homeLabel(item),
            color = SpfyWhite,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (isArtist) TextAlign.Center else TextAlign.Start,
            modifier = Modifier.fillMaxWidth()
        )
        val subtitle = item.artists?.joinToString(", ") ?: item.owner
        if (subtitle != null) {
            Text(
                subtitle,
                color = SpfyLightGray,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = if (isArtist) TextAlign.Center else TextAlign.Start,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
