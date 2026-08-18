package ch.snepilatch.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.snepilatch.app.R
import ch.snepilatch.app.ui.components.SheetNavBarFix
import ch.snepilatch.app.ui.components.SpfyImage
import ch.snepilatch.app.ui.theme.*
import ch.snepilatch.app.viewmodel.PlaybackViewModel

/** The queue as a bottom drawer over whatever is showing; the full player stays open underneath. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(vm: PlaybackViewModel) {
    val queue by vm.queue.collectAsState()
    val queuedCount by vm.queuedCount.collectAsState()
    val playback by vm.playback.collectAsState()
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )

    ModalBottomSheet(
        onDismissRequest = { vm.closeQueue() },
        sheetState = sheetState,
        containerColor = SpfyElevated,
        dragHandle = {
            Box(
                Modifier
                    .padding(vertical = 12.dp)
                    .width(40.dp)
                    .height(4.dp)
                    .background(SpfyLightGray.copy(alpha = 0.4f), RoundedCornerShape(2.dp))
            )
        }
    ) {
        SheetNavBarFix()
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f)) {
            Text(
                stringResource(R.string.queue),
                color = SpfyWhite,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )

            playback.track?.let { NowPlayingRow(it) }

            if (queue.isEmpty() && playback.track == null) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.queue_empty), color = SpfyLightGray, fontSize = 16.sp)
                }
                return@Column
            }
            // What you queued and what simply plays next are different things, so they get their own
            // headers rather than one flat list that reads as if you had queued the whole album.
            val upNext = queue.drop(queuedCount)
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
                if (queuedCount > 0) {
                    item(key = "header-queued") { SectionHeader(stringResource(R.string.queue_next_in_queue)) }
                    itemsIndexed(queue.take(queuedCount), key = { i, t -> "q-$i-${t.uri}" }) { i, track ->
                        SwipeableQueueRow(
                            track,
                            onClick = { vm.skipToQueueIndex(i) },
                            onRemove = { vm.removeFromQueue(track) },
                        )
                    }
                }
                if (upNext.isNotEmpty()) {
                    item(key = "header-upnext") { SectionHeader(stringResource(R.string.queue_next_up)) }
                    itemsIndexed(upNext, key = { i, t -> "n-$i-${t.uri}" }) { i, track ->
                        SwipeableQueueRow(
                            track,
                            onClick = { vm.skipToQueueIndex(queuedCount + i) },
                            onRemove = { vm.removeFromQueue(track) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * A queue row you can swipe away, which is the primary way out of a track added by mistake.
 *
 * One direction only: a row also responds to a tap, and a two way swipe on top of that turns an
 * imprecise gesture into a coin flip between playing something and deleting it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableQueueRow(
    track: ch.snepilatch.app.data.TrackInfo,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    val state = rememberSwipeToDismissBoxState()
    SwipeToDismissBox(
        state = state,
        onDismiss = { value -> if (value == SwipeToDismissBoxValue.EndToStart) onRemove() },
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(SpfyError)
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(Icons.Rounded.Delete, stringResource(R.string.queue_remove), tint = SpfyWhite)
            }
        }
    ) {
        QueueRow(track, onClick)
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        color = SpfyLightGray,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
private fun QueueRow(track: ch.snepilatch.app.data.TrackInfo, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SpfyImage(track.albumArt, Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)))
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(track.name, color = SpfyWhite, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.artist, color = SpfyLightGray, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun NowPlayingRow(track: ch.snepilatch.app.data.TrackInfo) {
    Text(
        stringResource(R.string.now_playing),
        color = SpfyLightGray,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
    Row(
        Modifier
            .fillMaxWidth()
            .background(SpfyGray.copy(alpha = 0.5f))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SpfyImage(track.albumArt, Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)))
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(track.name, color = SpfyWhite, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.artist, color = SpfyLightGray, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
