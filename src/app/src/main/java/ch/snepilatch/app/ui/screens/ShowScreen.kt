@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package ch.snepilatch.app.ui.screens

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.snepilatch.app.R
import ch.snepilatch.app.data.DetailData
import ch.snepilatch.app.data.ShowEpisode
import ch.snepilatch.app.data.TrackInfo
import ch.snepilatch.app.logic.podcast.EpisodeProgress
import ch.snepilatch.app.logic.podcast.ReleaseLabel
import ch.snepilatch.app.logic.podcast.YourEpisodes
import ch.snepilatch.app.logic.podcast.durationParts
import ch.snepilatch.app.logic.podcast.episodeProgress
import ch.snepilatch.app.logic.podcast.isNewEpisode
import ch.snepilatch.app.logic.podcast.releaseDay
import ch.snepilatch.app.logic.podcast.releaseLabel
import ch.snepilatch.app.logic.shared.ThemeController
import ch.snepilatch.app.logic.shared.shareLink
import ch.snepilatch.app.logic.shared.spfyLink
import ch.snepilatch.app.logic.shared.stripHtml
import ch.snepilatch.app.ui.shared.EntityMenuSheet
import ch.snepilatch.app.ui.shared.MenuAction
import ch.snepilatch.app.ui.shared.SpfyImage
import ch.snepilatch.app.ui.shared.TrackMenuOptions
import ch.snepilatch.app.ui.shared.trackMenuActions
import ch.snepilatch.app.ui.theme.*
import ch.snepilatch.app.viewmodel.DetailViewModel
import ch.snepilatch.app.viewmodel.PlaybackViewModel
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** Which episodes the list shows, after the web player's show filter. */
private enum class EpisodeFilter { ALL, UNPLAYED, IN_PROGRESS }

/**
 * A podcast show, laid out after the web player's show page (#1044): cover, name and publisher, the
 * follow, share and more actions, About with the rating, then the episodes under their filter and sort.
 */
@Composable
internal fun ShowScreen(vm: PlaybackViewModel, detailVm: DetailViewModel, detail: DetailData) {
    val header = detail.show
    val hasMore = detail.totalCount < 0 || detail.tracks.size < detail.totalCount
    var filter by rememberSaveable(detail.uri) { mutableStateOf(EpisodeFilter.ALL) }
    // Newest first unless the show is meant to be heard in order, as the web player defaults.
    var oldestFirst by rememberSaveable(detail.uri) { mutableStateOf(header?.consumptionOrder == "SEQUENTIAL") }
    val loadingAll by detailVm.isLoadingAll.collectAsState()

    // The service pages newest first only, so oldest first needs the whole list in hand.
    LaunchedEffect(oldestFirst, detail.uri) {
        if (oldestFirst && hasMore) detailVm.loadAllTracks(detail.uri) {}
    }
    // Each page's + buttons, looked up once per page.
    var lookedUp by remember(detail.uri) { mutableIntStateOf(0) }
    LaunchedEffect(detail.tracks.size) {
        if (detail.tracks.size > lookedUp) {
            detailVm.refreshSavedEpisodes(detail.tracks.drop(lookedUp).map { it.uri })
            lookedUp = detail.tracks.size
        }
    }

    // Indexes into the loaded page order, which is also the order the play command names.
    val rows = remember(detail.tracks, detail.episodes, filter, oldestFirst) {
        detail.tracks.indices.filter { i ->
            val episode = detail.episodes.getOrNull(i) ?: return@filter false
            when (filter) {
                EpisodeFilter.ALL -> true
                EpisodeFilter.UNPLAYED -> !episode.isPlayed
                EpisodeFilter.IN_PROGRESS -> !episode.isPlayed && episode.resumePositionMs > 0
            }
        }.let { if (oldestFirst) it.reversed() else it }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomOverlayHeight.current.value + 16.dp)) {
        item { DetailBackButton { vm.goBack() } }
        item { ShowHeaderBlock(detail) }
        item { ShowActions(detail, detailVm) }
        if (header != null && (header.about.isNotBlank() || header.averageRating != null)) {
            item { ShowAbout(header.about, header.averageRating, header.totalRatings) }
        }
        item { HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = SnepilatchGray) }
        item {
            EpisodeListHeader(
                filter = filter, oldestFirst = oldestFirst,
                onFilter = { filter = it }, onOldestFirst = { oldestFirst = it },
            )
        }
        items(rows, key = { detail.tracks[it].uri }) { index ->
            ShowEpisodeRow(
                track = detail.tracks[index],
                episode = detail.episodes[index],
                showName = detail.name,
                onPlay = { vm.playTrack(detail.tracks[index], detail.uri, index) },
                vm = vm,
                detailVm = detailVm,
                pageUri = detail.uri,
            )
            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = SnepilatchGray)
            // Newest first pages as it scrolls; oldest first already asked for everything above.
            if (!oldestFirst && hasMore && index >= detail.tracks.size - 5) {
                LaunchedEffect(detail.tracks.size) { detailVm.loadMoreDetail() }
            }
        }
        if (loadingAll) {
            item {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    LoadingIndicator(color = SnepilatchLightGray)
                }
            }
        }
    }
}

@Composable
private fun ShowHeaderBlock(detail: DetailData) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        SpfyImage(url = detail.imageUrl, modifier = Modifier.size(128.dp), shape = RoundedCornerShape(8.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.show_type), color = SnepilatchLightGray, fontSize = 12.sp)
            Text(
                detail.name, color = SnepilatchWhite, fontSize = 26.sp, fontWeight = FontWeight.Bold,
                maxLines = 3, overflow = TextOverflow.Ellipsis,
            )
            detail.publisher?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, color = SnepilatchWhite, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Follow, share and more, as the web player's action bar. It has no play button. */
@Composable
private fun ShowActions(detail: DetailData, detailVm: DetailViewModel) {
    val context = LocalContext.current
    val shareLabel = stringResource(R.string.share)
    val shareUrl = detail.show?.shareUrl ?: spfyLink(detail.uri)
    var showMenu by remember { mutableStateOf(false) }
    val followed = detail.show?.isFollowed == true
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (detail.show != null) {
            OutlinedButton(
                onClick = { detailVm.toggleShowFollow() },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = SnepilatchWhite),
                border = androidx.compose.foundation.BorderStroke(1.dp, SnepilatchLightGray),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                modifier = Modifier.height(32.dp),
            ) {
                Text(stringResource(if (followed) R.string.following else R.string.follow), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.width(8.dp))
        }
        IconButton(onClick = { shareLink(context, shareUrl, shareLabel) }) {
            Icon(Icons.Rounded.Share, shareLabel, tint = SnepilatchLightGray)
        }
        IconButton(onClick = { showMenu = true }) {
            Icon(Icons.Rounded.MoreVert, stringResource(R.string.more), tint = SnepilatchLightGray)
        }
    }
    if (showMenu) {
        val actions = listOfNotNull(
            detail.show?.let {
                MenuAction(
                    if (followed) Icons.Rounded.CheckCircle else Icons.Rounded.AddCircleOutline,
                    stringResource(if (followed) R.string.following else R.string.follow),
                ) {
                    showMenu = false
                    detailVm.toggleShowFollow()
                }
            },
            MenuAction(Icons.Rounded.Share, shareLabel) {
                showMenu = false
                shareLink(context, shareUrl, shareLabel)
            },
        )
        EntityMenuSheet(detail.imageUrl, detail.name, detail.publisher, actions, onDismiss = { showMenu = false })
    }
}

/** The description, four lines until tapped, and the rating, as the web player's About block. */
@Composable
private fun ShowAbout(about: String, averageRating: Double?, totalRatings: Int) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val text = remember(about) { stripHtml(about) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(stringResource(R.string.show_about), color = SnepilatchWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        if (text.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text, color = SnepilatchLightGray, fontSize = 14.sp,
                maxLines = if (expanded) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable { expanded = !expanded },
            )
        }
        if (averageRating != null) {
            Spacer(Modifier.height(10.dp))
            val locale = LocalConfiguration.current.locales[0]
            Row(
                Modifier.background(SnepilatchGray, RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Star, null, tint = SnepilatchWhite, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    stringResource(R.string.show_rating, String.format(locale, "%.1f", averageRating), String.format(locale, "%,d", totalRatings)),
                    color = SnepilatchWhite, fontSize = 13.sp,
                )
            }
        }
    }
}

/** "All episodes · Newest", opening the filter and the sort, the two dropdowns of the web player's list. */
@Composable
private fun EpisodeListHeader(
    filter: EpisodeFilter,
    oldestFirst: Boolean,
    onFilter: (EpisodeFilter) -> Unit,
    onOldestFirst: (Boolean) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val filterLabel = stringResource(
        when (filter) {
            EpisodeFilter.ALL -> R.string.episodes_all
            EpisodeFilter.UNPLAYED -> R.string.episodes_unplayed
            EpisodeFilter.IN_PROGRESS -> R.string.episodes_in_progress
        }
    )
    val sortLabel = stringResource(if (oldestFirst) R.string.episodes_oldest else R.string.episodes_newest)
    Box(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
        TextButton(onClick = { open = true }) {
            Icon(Icons.Rounded.Tune, null, tint = SnepilatchWhite, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("$filterLabel · $sortLabel", color = SnepilatchWhite, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MenuSection(stringResource(R.string.episodes_filter_by))
            EpisodeFilter.entries.forEach { option ->
                val label = stringResource(
                    when (option) {
                        EpisodeFilter.ALL -> R.string.episodes_all
                        EpisodeFilter.UNPLAYED -> R.string.episodes_unplayed
                        EpisodeFilter.IN_PROGRESS -> R.string.episodes_in_progress
                    }
                )
                MenuChoice(label, selected = option == filter) {
                    onFilter(option)
                    open = false
                }
            }
            HorizontalDivider()
            MenuSection(stringResource(R.string.episodes_sort_by))
            MenuChoice(stringResource(R.string.episodes_newest), selected = !oldestFirst) {
                onOldestFirst(false)
                open = false
            }
            MenuChoice(stringResource(R.string.episodes_oldest), selected = oldestFirst) {
                onOldestFirst(true)
                open = false
            }
        }
    }
}

@Composable
private fun MenuSection(title: String) {
    Text(title, color = SnepilatchLightGray, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
}

@Composable
private fun MenuChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal) },
        trailingIcon = if (selected) ({ Icon(Icons.Rounded.CheckCircle, null, modifier = Modifier.size(18.dp)) }) else null,
        onClick = onClick,
    )
}

/**
 * One episode as the web player's show page draws it: cover, title, show, two lines of description,
 * release date with the length or time left, then +, share and more on the left and play on the right.
 */
@Composable
private fun ShowEpisodeRow(
    track: TrackInfo,
    episode: ShowEpisode,
    showName: String,
    onPlay: () -> Unit,
    vm: PlaybackViewModel,
    detailVm: DetailViewModel,
    pageUri: String,
) {
    val context = LocalContext.current
    val theme by ThemeController.themeColors.collectAsState()
    val accent = theme.primary
    val currentUri by vm.currentTrackUri.collectAsState()
    val playing by vm.isPlayingFlow.collectAsState()
    val isCurrent = currentUri == track.uri
    val savedEpisodes by YourEpisodes.saved.collectAsState()
    val saved = track.uri in savedEpisodes
    val shareLabel = stringResource(R.string.share)
    var showMenu by remember { mutableStateOf(false) }
    val today = remember { LocalDate.now() }
    val release = remember(episode.releaseDate) { releaseDay(episode.releaseDate) }

    Column(
        Modifier.fillMaxWidth().clickable(enabled = track.isPlayable) { if (isCurrent) vm.togglePlayPause() else onPlay() }
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SpfyImage(url = track.albumArt, modifier = Modifier.size(56.dp), shape = RoundedCornerShape(6.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isNewEpisode(release, today, episode.resumePositionMs, episode.isPlayed)) {
                        Box(Modifier.size(8.dp).background(accent, CircleShape))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        track.name, color = if (isCurrent) accent else SnepilatchWhite, fontSize = 16.sp,
                        fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(showName, color = SnepilatchLightGray, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        val description = remember(episode.description) { stripHtml(episode.description) }
        if (description.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(description, color = SnepilatchLightGray, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(6.dp))
        EpisodeMetaLine(track, episode, release, today, isCurrent, vm, accent)
        EpisodeActions(
            saved = saved,
            playable = track.isPlayable,
            showPause = isCurrent && playing,
            onToggleSaved = { detailVm.toggleEpisodeSaved(track.uri) },
            onShare = { shareLink(context, spfyLink(track.uri), shareLabel) },
            onMore = { showMenu = true },
            onPlay = { if (isCurrent) vm.togglePlayPause() else onPlay() },
        )
    }

    if (showMenu) {
        EntityMenuSheet(
            imageUrl = track.albumArt,
            title = track.name,
            subtitle = showName,
            actions = trackMenuActions(
                track, vm, detailVm,
                close = { showMenu = false },
                options = TrackMenuOptions(pageUri = pageUri),
            ),
            onDismiss = { showMenu = false },
        )
    }
}

/** The row's footer: +, share and more on the left, play or pause on the right. */
@Composable
private fun EpisodeActions(
    saved: Boolean,
    playable: Boolean,
    showPause: Boolean,
    onToggleSaved: () -> Unit,
    onShare: () -> Unit,
    onMore: () -> Unit,
    onPlay: () -> Unit,
) {
    val theme by ThemeController.themeColors.collectAsState()
    val accent = theme.primary
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onToggleSaved) {
            Icon(
                if (saved) Icons.Rounded.CheckCircle else Icons.Rounded.AddCircleOutline,
                stringResource(if (saved) R.string.remove_from_your_episodes else R.string.save_to_your_episodes),
                tint = if (saved) accent else SnepilatchLightGray,
            )
        }
        IconButton(onClick = onShare) {
            Icon(Icons.Rounded.Share, stringResource(R.string.share), tint = SnepilatchLightGray)
        }
        IconButton(onClick = onMore) {
            Icon(Icons.Rounded.MoreVert, stringResource(R.string.more), tint = SnepilatchLightGray)
        }
        Spacer(Modifier.weight(1f))
        IconButton(
            onClick = onPlay,
            enabled = playable,
            modifier = Modifier.size(40.dp).background(SnepilatchWhite, CircleShape),
        ) {
            Icon(
                if (showPause) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                stringResource(if (showPause) R.string.pause else R.string.play),
                tint = SnepilatchBlack,
            )
        }
    }
}

/** "Oct 20 · 16 min", "Oct 20 · 9 min left" with the progress bar, or "Oct 20 · Played". */
@Composable
private fun EpisodeMetaLine(
    track: TrackInfo,
    episode: ShowEpisode,
    release: LocalDate?,
    today: LocalDate,
    isCurrent: Boolean,
    vm: PlaybackViewModel,
    accent: androidx.compose.ui.graphics.Color,
) {
    // The playing episode counts down live; the others show where they were left.
    val livePosition by vm.positionFlow.collectAsState()
    val position = if (isCurrent) livePosition else episode.resumePositionMs
    val progress = episodeProgress(track.durationMs, position, episode.isPlayed && !isCurrent)
    val parts = listOfNotNull(
        release?.let { releaseText(releaseLabel(it, today)) },
        if (episode.isExplicit) "E" else null,
        when (progress) {
            is EpisodeProgress.NotStarted -> durationText(progress.durationMs)
            is EpisodeProgress.Started -> stringResource(R.string.episode_time_left, durationText(progress.leftMs))
            EpisodeProgress.Played -> stringResource(R.string.episode_played)
        },
    )
    Text(parts.joinToString(" · "), color = SnepilatchWhite, fontSize = 13.sp)
    if (progress is EpisodeProgress.Started) {
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { progress.fraction },
            color = accent,
            trackColor = SnepilatchGray,
            // A plain bar like the web player's, without Material's end dot and gap.
            gapSize = 0.dp,
            drawStopIndicator = {},
            modifier = Modifier.fillMaxWidth(0.4f).height(3.dp),
        )
    }
}

@Composable
private fun releaseText(label: ReleaseLabel): String {
    val locale = LocalConfiguration.current.locales[0]
    return when (label) {
        ReleaseLabel.Today -> stringResource(R.string.episode_today)
        is ReleaseLabel.ThisWeek -> label.day.getDisplayName(TextStyle.FULL, locale)
        is ReleaseLabel.ThisYear -> formatDate(label.date, "MMMd", locale)
        is ReleaseLabel.OtherYear -> formatDate(label.date, "yMMMd", locale)
    }
}

private fun formatDate(date: LocalDate, skeleton: String, locale: Locale): String =
    java.time.format.DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale).format(date)

/** "1 hr 5 min", "16 min", or seconds when under a minute, as the web player's short durations. */
@Composable
private fun durationText(ms: Long): String {
    val (hours, minutes, seconds) = durationParts(ms)
    val hourText = stringResource(R.string.episode_hours_short, hours)
    val minuteText = stringResource(R.string.episode_minutes_short, minutes)
    return when {
        hours > 0 && minutes > 0 -> "$hourText $minuteText"
        hours > 0 -> hourText
        minutes > 0 -> minuteText
        else -> stringResource(R.string.episode_seconds_short, seconds)
    }
}
