package ch.snepilatch.app.ui.screens

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.snepilatch.app.R
import ch.snepilatch.app.logic.shared.ThemeController
import ch.snepilatch.app.logic.shared.spfyId
import ch.snepilatch.app.ui.shared.SpfyImage
import ch.snepilatch.app.ui.theme.SnepilatchBlack
import ch.snepilatch.app.ui.theme.SnepilatchLightGray
import ch.snepilatch.app.ui.theme.SnepilatchWhite
import ch.snepilatch.app.viewmodel.DetailRoutes
import ch.snepilatch.app.viewmodel.PlaybackViewModel
import ch.snepilatch.app.viewmodel.WhatsNewViewModel
import kotify.api.whatsnew.WhatsNewContent
import kotify.api.whatsnew.WhatsNewContentType
import kotify.api.whatsnew.WhatsNewItem
import kotify.api.whatsnew.WhatsNewItemState
import java.time.Instant

/**
 * The web player's What's New panel (#884): its title and subtitle, the Music and Podcast & Shows
 * chips, and the feed split into "New" and "Earlier". A release opens its album; an episode plays.
 */
@Composable
fun WhatsNewScreen(vm: PlaybackViewModel, whatsNewVm: WhatsNewViewModel = viewModel()) {
    val items by whatsNewVm.items.collectAsState()
    val loading by whatsNewVm.loading.collectAsState()
    val failed by whatsNewVm.failed.collectAsState()
    val filter by whatsNewVm.filter.collectAsState()
    val loadingMore by whatsNewVm.loadingMore.collectAsState()
    LaunchedEffect(Unit) { whatsNewVm.open() }

    // The spinner, error and empty messages sit in the middle of the screen, not of the space under the chips.
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(top = 12.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.goBack() }) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back), tint = SnepilatchWhite)
                }
                Text(stringResource(R.string.whats_new_title), color = SnepilatchWhite, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                stringResource(R.string.whats_new_subtitle),
                color = SnepilatchLightGray,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FeedChip(stringResource(R.string.whats_new_filter_music), filter == WhatsNewContentType.MUSIC) {
                    whatsNewVm.toggleFilter(WhatsNewContentType.MUSIC)
                }
                FeedChip(stringResource(R.string.whats_new_filter_podcasts), filter == WhatsNewContentType.EPISODES) {
                    whatsNewVm.toggleFilter(WhatsNewContentType.EPISODES)
                }
            }

            if (items.isNotEmpty()) FeedList(items, vm, loadingMore, loading) { whatsNewVm.loadMore() }
        }
        when {
            items.isNotEmpty() -> Unit
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = SnepilatchWhite)
            }
            failed -> Message(stringResource(R.string.whats_new_error), null) {
                TextButton(onClick = { whatsNewVm.load() }) { Text(stringResource(R.string.retry), color = SnepilatchWhite) }
            }
            else -> EmptyFeed(filter)
        }
    }
}

@Composable
private fun FeedList(items: List<WhatsNewItem>, vm: PlaybackViewModel, loadingMore: Boolean, busy: Boolean, onNearEnd: () -> Unit) {
    val now = System.currentTimeMillis()
    // As the web player splits it: what is new (or was seen within the hour) first, the rest under "Earlier".
    val firstEarlier = items.indexOfFirst { !isFresh(it, now) }.let { if (it == -1) items.size else it }
    val listState = rememberLazyListState()
    // Infinite scroll: the next page is asked for a few rows before the end comes into view.
    val nearEnd by remember(items.size) {
        derivedStateOf { (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= items.size - LOAD_AHEAD }
    }
    // Asked again once a load finishes, so reaching the end while one was running is not lost.
    LaunchedEffect(nearEnd, busy, loadingMore) { if (nearEnd && !busy && !loadingMore) onNearEnd() }
    LazyColumn(state = listState, contentPadding = PaddingValues(bottom = LocalBottomOverlayHeight.current.value + 16.dp)) {
        itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
            if (index == 0 && firstEarlier > 0) SectionTitle(stringResource(R.string.whats_new_section_new))
            if (index == firstEarlier) SectionTitle(stringResource(R.string.whats_new_section_earlier))
            when (val content = item.content) {
                is WhatsNewContent.Release -> ReleaseRow(content) { DetailRoutes.openAlbum(spfyId(content.uri)) }
                is WhatsNewContent.Episode -> EpisodeRow(content) { vm.playTrack(content.uri) }
                null -> Unit
            }
        }
        if (loadingMore) {
            item(key = "loading-more") {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = SnepilatchWhite, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }
        }
    }
}

/** How many rows before the end the next page is asked for. */
private const val LOAD_AHEAD = 5

private const val FRESH_MS = 60 * 60 * 1000L

private fun isFresh(item: WhatsNewItem, now: Long): Boolean = when (item.state) {
    WhatsNewItemState.NEW -> true
    WhatsNewItemState.SEEN -> {
        val seenAt = item.stateChangedAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
        seenAt != null && now - seenAt <= FRESH_MS
    }
    else -> false
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        color = SnepilatchWhite,
        fontSize = 17.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun ReleaseRow(release: WhatsNewContent.Release, onClick: () -> Unit) {
    val details = listOfNotNull(release.albumType?.let(::typeLabel), release.releaseDate?.let { relativeDate(it) })
    val artists = release.artists.joinToString(", ") { it.name }
    FeedRow(release.coverUrl, release.name, artists, details.joinToString(" · "), onClick)
}

@Composable
private fun EpisodeRow(episode: WhatsNewContent.Episode, onClick: () -> Unit) {
    val minutes = (episode.durationMs / 60_000).takeIf { it > 0 }?.let { "$it min" }
    val details = listOfNotNull(episode.releaseDate?.let { relativeDate(it) }, minutes)
    FeedRow(episode.coverUrl, episode.name, episode.podcastName.orEmpty(), details.joinToString(" · "), onClick)
}

@Composable
private fun FeedRow(image: String?, title: String, subtitle: String, details: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SpfyImage(image, Modifier.size(64.dp), shape = RoundedCornerShape(4.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = SnepilatchWhite, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) {
                Text(subtitle, color = SnepilatchLightGray, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (details.isNotBlank()) Text(details, color = SnepilatchLightGray, fontSize = 12.sp, maxLines = 1)
        }
    }
}

/** "Single", "Album", "EP": the service's type as the app shows it elsewhere, short ones kept upper case. */
private fun typeLabel(type: String): String =
    if (type.length <= 2) type else type.lowercase().replaceFirstChar { it.uppercase() }

/** "2 days ago" for the recent past, the date after that, like the web player's relative date. */
private fun relativeDate(iso: String): String? = runCatching {
    val time = Instant.parse(iso).toEpochMilli()
    DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), DateUtils.DAY_IN_MILLIS).toString()
}.getOrNull()

@Composable
private fun EmptyFeed(filter: WhatsNewContentType?) {
    val (title, message) = when (filter) {
        WhatsNewContentType.MUSIC -> R.string.whats_new_empty_music_title to R.string.whats_new_empty_music_message
        WhatsNewContentType.EPISODES -> R.string.whats_new_empty_podcasts_title to R.string.whats_new_empty_podcasts_message
        null -> R.string.whats_new_empty_all_title to R.string.whats_new_empty_all_message
    }
    Message(stringResource(title), stringResource(message))
}

@Composable
private fun Message(title: String, message: String?, action: (@Composable () -> Unit)? = null) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, color = SnepilatchWhite, fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        if (message != null) {
            Text(message, color = SnepilatchLightGray, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
        }
        action?.invoke()
    }
}

/** Same look as the search filter chips: the accent when on. */
@Composable
private fun FeedChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val theme by ThemeController.themeColors.collectAsState()
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) theme.primary else SnepilatchLightGray.copy(alpha = 0.18f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(label, color = if (selected) SnepilatchBlack else SnepilatchWhite, fontSize = 13.sp)
    }
}
