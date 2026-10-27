package ch.snepilatch.app.ui.screens

import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.CheckCircle
import ch.snepilatch.app.logic.shared.ThemeController
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
import ch.snepilatch.app.viewmodel.DetailRoutes
import ch.snepilatch.app.ui.shared.LoadErrorState
import ch.snepilatch.app.data.LIKED_SONGS_COVER_URL
import ch.snepilatch.app.ui.theme.SnepilatchWhite
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
import ch.snepilatch.app.ui.shared.ShimmerBox
import ch.snepilatch.app.ui.shared.EntityMenuSheet
import ch.snepilatch.app.ui.shared.MenuAction
import ch.snepilatch.app.ui.shared.SpfyImage
import ch.snepilatch.app.ui.theme.SnepilatchGray
import ch.snepilatch.app.ui.theme.SnepilatchBlack
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import ch.snepilatch.app.ui.shared.Avatar
import ch.snepilatch.app.ui.shared.LocalOpenProfileMenu
import ch.snepilatch.app.ui.theme.SnepilatchLightGray
import ch.snepilatch.app.logic.shared.CardColors
import ch.snepilatch.app.logic.shared.extractCardColorsFromArt
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.snepilatch.app.viewmodel.DetailViewModel
import ch.snepilatch.app.viewmodel.HomeViewModel
import ch.snepilatch.app.viewmodel.LibraryViewModel
import ch.snepilatch.app.viewmodel.PlaybackViewModel
import ch.snepilatch.app.logic.shared.shareSpfyUri
import ch.snepilatch.app.logic.shared.spfyId

// --- Home Screen ---

/**
 * The profile picture, which opens the profile menu, and the feed's filter chips, laid out as the
 * official app does (#976). Every chip stays in the row. A chosen chip becomes a group with its
 * sub-chips: grey with the chip as a pill on it, or, once a sub-chip is chosen, all in the accent with
 * the chip in a lighter pill, so which one is chosen always shows. Tapping a chosen chip goes back to
 * the whole feed. The picture stays put while the chips scroll under it.
 */
@Composable
private fun HomeHeader(vm: PlaybackViewModel, homeVm: HomeViewModel) {
    val account by vm.account.collectAsState()
    val chips by homeVm.chips.collectAsState()
    val facet by homeVm.facet.collectAsState()
    val openMenu = LocalOpenProfileMenu.current
    val allLabel = stringResource(R.string.home_filter_all)
    val groups = remember(chips, facet, allLabel) { chipGroups(chips, facet, allLabel) }
    val rowState = rememberLazyListState()
    // A chosen chip cut off at the edge scrolls into view, as in the official row; one already in full
    // view leaves the row where it is, so "All" does not slide away under the picture.
    LaunchedEffect(facet) {
        val chosen = groups.indexOfFirst { it.chosen }
        if (chosen < 0) return@LaunchedEffect
        val info = rowState.layoutInfo
        val shown = info.visibleItemsInfo.firstOrNull { it.index == chosen }
        val whole = shown != null && shown.offset >= info.viewportStartOffset && shown.offset + shown.size <= info.viewportEndOffset
        if (!whole) rowState.animateScrollToItem(chosen)
    }

    Row(
        Modifier.fillMaxWidth().background(SnepilatchBlack).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(12.dp))
        IconButton(onClick = openMenu, modifier = Modifier.size(40.dp)) {
            Avatar(account.profileImageUrl, 34.dp)
        }
        LazyRow(
            state = rowState,
            contentPadding = PaddingValues(start = 6.dp, end = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(groups, key = { it.key }) { group -> ChipGroupView(group) { homeVm.selectChip(it) } }
        }
    }
}

private const val SHORTCUTS_SECTION = "HomeShortsSectionData"
private const val MUSIC_FOLLOWING_CHIP = "music-following-chip"

/**
 * One stretch of the chip row: a lone chip, or a chosen chip with its sub-chips. [filled] paints the
 * whole stretch in the accent; a [ChipSegment.raised] segment sits on it as a pill of its own.
 */
private data class ChipGroup(val key: String, val filled: Boolean, val segments: List<ChipSegment>) {
    val chosen get() = filled || segments.any { it.raised }
}

/** What a segment says, whether it is raised as a pill, and which feed a tap asks for (null: all). */
private data class ChipSegment(val label: String, val raised: Boolean, val target: String?)

private fun chipGroups(chips: List<kotify.api.home.HomeChip>, facet: String, allLabel: String): List<ChipGroup> = buildList {
    add(ChipGroup("all", filled = facet.isEmpty(), listOf(ChipSegment(allLabel, raised = false, target = null))))
    chips.forEach { chip ->
        val chosenSub = chip.subChips.firstOrNull { it.id == facet }
        val chipChosen = facet == chip.id
        val segments = when {
            chosenSub != null || chipChosen -> listOf(ChipSegment(chip.label, raised = true, target = null)) +
                // A chosen sub-chip tapped again goes back to its chip, not to the whole feed.
                chip.subChips.map { sub -> ChipSegment(sub.label, raised = false, target = if (sub == chosenSub) chip.id else sub.id) }
            else -> listOf(ChipSegment(chip.label, raised = false, target = chip.id))
        }
        add(ChipGroup(chip.id, filled = chosenSub != null, segments))
    }
}

@Composable
private fun ChipGroupView(group: ChipGroup, onSelect: (String?) -> Unit) {
    val theme by ThemeController.themeColors.collectAsState()
    val accent = theme.primary
    // On the filled group the raised chip is a lighter accent, as the official row shades it.
    val raisedColor = if (group.filled) lerp(accent, Color.White, RAISED_LIGHTEN) else accent
    Row(
        Modifier
            .height(32.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (group.filled) accent else SnepilatchGray),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        group.segments.forEach { segment ->
            val dark = group.filled || segment.raised
            Box(
                Modifier
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (segment.raised) raisedColor else Color.Transparent)
                    .clickable { onSelect(segment.target) }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    segment.label,
                    color = if (dark) Color.Black else SnepilatchWhite,
                    fontSize = 14.sp,
                    fontWeight = if (dark) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

private const val RAISED_LIGHTEN = 0.3f

/**
 * What the two Following feeds add around their cards (#976): the heading the web player gives them,
 * since they come without titles, a "caught up" note in its words when there is nothing new, and for
 * podcasts the new episodes themselves, which come from the What's New feed rather than the home query.
 */
private fun LazyListScope.followingFeed(
    facet: String,
    loaded: Boolean,
    noSections: Boolean,
    episodes: List<kotify.api.whatsnew.WhatsNewItem>,
    vm: PlaybackViewModel,
) {
    val podcasts = facet == HomeViewModel.PODCASTS_FOLLOWING_CHIP
    // Still loading: the skeleton stands in for the heading as well.
    if (facet != MUSIC_FOLLOWING_CHIP && !podcasts || !loaded) return
    item {
        Text(
            stringResource(if (podcasts) R.string.home_following_podcasts_header else R.string.home_following_music_header),
            color = SnepilatchWhite,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)
        )
    }
    val empty = if (podcasts) episodes.isEmpty() else noSections
    if (loaded && empty) {
        item {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.home_following_caught_up_title), color = SnepilatchWhite, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(if (podcasts) R.string.home_following_podcasts_caught_up else R.string.home_following_music_caught_up),
                    color = SnepilatchLightGray, fontSize = 14.sp, textAlign = TextAlign.Center,
                )
            }
        }
    }
    if (podcasts) {
        items(episodes, key = { it.id }) { item ->
            val episode = item.content as? kotify.api.whatsnew.WhatsNewContent.Episode ?: return@items
            EpisodeRow(episode) {
                // The show's page, as an episode card opens it; the row has no play of its own to steal.
                val show = episode.podcastUri
                if (show != null) DetailRoutes.openShow(spfyId(show), episode.podcastName) else vm.playTrack(episode.uri)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(vm: PlaybackViewModel) {
    val homeVm: HomeViewModel = viewModel()
    val homeData by homeVm.homeData.collectAsState()
    val isHomeLoading by homeVm.isLoading.collectAsState()
    val chips by homeVm.chips.collectAsState()
    val facet by homeVm.facet.collectAsState()
    val episodes by homeVm.episodes.collectAsState()

    // Home shown again, or the app back in front: the feed is asked for again only once it is stale,
    // as the web player's home query does on mount and window focus. Adding the observer delivers the
    // current ON_START, which covers opening the tab.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_START) homeVm.refreshIfStale()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Only the very first load shimmers; a chip's feed loads under the row with nothing below it,
    // as the official app shows it.
    if (isHomeLoading && homeData == null && chips.isEmpty()) {
        HomeShimmer()
        return
    }
    val loadError by homeVm.loadError.collectAsState()
    if (homeData == null && chips.isEmpty()) {
        loadError?.let {
            LoadErrorState(it, onRetry = { homeVm.retry() })
            return
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = LocalBottomOverlayHeight.current.value + 16.dp)
    ) {
        stickyHeader { HomeHeader(vm, homeVm) }
        // A chip's feed not seen yet: the skeleton under the row until it is in, not a blank page.
        if (homeData == null) item { HomeShimmer(homeSkeletonFor(facet)) }

        // The quick picks are the shortcuts section the whole feed opens with. A chip's feed opens with an
        // ordinary section instead (Following's first release card), which the grid used to swallow.
        val sections = homeData?.sections.orEmpty()
        val shortcuts = sections.firstOrNull()?.takeIf { it.type == SHORTCUTS_SECTION && it.items.isNotEmpty() }
        if (shortcuts != null) {
            item {
                QuickPickGrid(shortcuts.items.take(8), vm)
            }
        }
        followingFeed(facet, loaded = homeData != null, sections.isEmpty(), episodes, vm)

        sections.drop(if (shortcuts != null) 1 else 0).forEach { section ->
            if (section.items.isNotEmpty()) {
                // A chip's feed can send sections without a title (Following's new releases); an empty
                // title row would only leave a gap above them.
                if (section.title.isNotBlank()) {
                    item {
                        Text(
                            section.title,
                            color = SnepilatchWhite,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 10.dp)
                        )
                    }
                }
                // A section holding one thing reads as a lone tile in a row that goes nowhere, so it
                // gets a card wide enough to say what it is instead.
                if (section.items.size == 1) {
                    // Untitled cards that follow one another get room between them, as in the official
                    // feed; one under a heading sits right below it, the heading's own gap is enough.
                    val gap = if (section.title.isBlank()) 20.dp else 0.dp
                    item { Box(Modifier.padding(top = gap)) { HomeSingleCard(section.items.first(), vm) } }
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
                                if (!detailVm.openEntity(item.type, item.uri, item.owner, item.imageUrl, item.showUri)) vm.playTrack(item.uri)
                            },
                        shape = RoundedCornerShape(6.dp),
                        colors = CardDefaults.cardColors(containerColor = SnepilatchGray)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SpfyImage(
                                url = homeArt(item),
                                modifier = Modifier.size(48.dp),
                                shape = RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp)
                            )
                            Text(
                                homeLabel(item),
                                color = SnepilatchWhite,
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

/** Which shape the skeleton takes: the one the feed about to load will have. */
enum class HomeSkeleton { SHORTCUTS, CARDS, EPISODES }

/**
 * The skeleton for [facet]'s feed. The whole feed and Music open with the shortcuts and rows of tiles;
 * Following, Podcasts and Audiobooks are a heading over big cards; podcasts' Following is episode rows.
 */
fun homeSkeletonFor(facet: String): HomeSkeleton = when (facet) {
    "", "music-chip" -> HomeSkeleton.SHORTCUTS
    HomeViewModel.PODCASTS_FOLLOWING_CHIP -> HomeSkeleton.EPISODES
    else -> HomeSkeleton.CARDS
}

@Composable
fun HomeShimmer(layout: HomeSkeleton = HomeSkeleton.SHORTCUTS) {
    // One shared transition drives every placeholder; ShimmerBox reads phase in the draw phase, so the
    // sweep animates without recomposing any box. Keep `phase` as State<Float> (no `by`, never read
    // .value here) so this composable is not invalidated each frame.
    val phase = rememberInfiniteTransition(label = "shimmer").animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)),
        label = "shimmer"
    )
    when (layout) {
        HomeSkeleton.SHORTCUTS -> ShortcutsSkeleton(phase)
        HomeSkeleton.CARDS -> CardsSkeleton(phase)
        HomeSkeleton.EPISODES -> EpisodesSkeleton(phase)
    }
}

@Composable
private fun ShortcutsSkeleton(phase: State<Float>) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        repeat(4) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(2) {
                    ShimmerBox(phase, Modifier.weight(1f).height(56.dp).clip(RoundedCornerShape(6.dp)))
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

/** A heading over the big single-item cards, sized as [HomeSingleCard] draws them. */
@Composable
private fun CardsSkeleton(phase: State<Float>) {
    // Each card under its own heading, as Podcasts and Audiobooks send them; the same paddings as the
    // section title and the card, so the loaded feed lands where the skeleton stood.
    Column(Modifier.fillMaxWidth()) {
        repeat(2) {
            ShimmerBox(
                phase,
                Modifier.padding(start = 16.dp, top = 20.dp, bottom = 10.dp).width(180.dp).height(24.dp).clip(RoundedCornerShape(4.dp)),
            )
            ShimmerBox(phase, Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(170.dp).clip(RoundedCornerShape(12.dp)))
        }
    }
}

/** A heading over episode rows, sized as the What's New rows the feed shows. */
@Composable
private fun EpisodesSkeleton(phase: State<Float>) {
    Column(Modifier.fillMaxWidth()) {
        ShimmerBox(
            phase,
            Modifier.padding(start = 16.dp, top = 12.dp, bottom = 8.dp).width(200.dp).height(28.dp).clip(RoundedCornerShape(4.dp)),
        )
        repeat(6) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ShimmerBox(phase, Modifier.size(64.dp).clip(RoundedCornerShape(4.dp)))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ShimmerBox(phase, Modifier.fillMaxWidth(0.75f).height(15.dp).clip(RoundedCornerShape(4.dp)))
                    ShimmerBox(phase, Modifier.fillMaxWidth(0.5f).height(13.dp).clip(RoundedCornerShape(4.dp)))
                    ShimmerBox(phase, Modifier.fillMaxWidth(0.3f).height(12.dp).clip(RoundedCornerShape(4.dp)))
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
    } ?: SnepilatchGray
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
        if (!detailVm.openEntity(item.type, item.uri, item.owner, item.imageUrl, item.showUri)) detailVm.openPlaylist(spfyId(item.uri))
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
                        color = SnepilatchWhite,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    item.owner?.takeIf { it.isNotBlank() }?.let { owner ->
                        Text(owner, color = SnepilatchLightGray, fontSize = 13.sp, maxLines = 1)
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
                .background(SnepilatchWhite)
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
        Icon(Icons.Rounded.MoreVert, stringResource(R.string.more), tint = SnepilatchWhite, modifier = Modifier.size(26.dp))
    }
    if (!showMenu) return
    val shareLabel = stringResource(R.string.share)
    val actions = buildList {
        if (item.type != "collection") {
            add(
                MenuAction(Icons.Rounded.AddCircleOutline, stringResource(R.string.save_to_library)) {
                    showMenu = false
                    vm.saveToLibrary(item.type, spfyId(item.uri))
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
                shareSpfyUri(context, item.uri, shareLabel)
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
                withStyle(SpanStyle(color = SnepilatchWhite, fontWeight = FontWeight.Bold)) { append(count) }
            }
            if (count != null && shown != null) append(" • ")
            if (shown != null) append(shown)
        },
        color = SnepilatchLightGray,
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
        val id = spfyId(item.uri)
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
            tint = SnepilatchWhite,
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
                if (!detailVm.openEntity(item.type, item.uri, item.owner, item.imageUrl, item.showUri)) vm.playTrack(item.uri)
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
            color = SnepilatchWhite,
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
                color = SnepilatchLightGray,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = if (isArtist) TextAlign.Center else TextAlign.Start,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
