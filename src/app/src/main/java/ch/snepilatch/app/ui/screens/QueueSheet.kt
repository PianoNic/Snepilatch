package ch.snepilatch.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
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

            if (queue.isNotEmpty()) {
                Text(
                    stringResource(R.string.queue_next_up),
                    color = SpfyLightGray,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            if (queue.isEmpty() && playback.track == null) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.queue_empty), color = SpfyLightGray, fontSize = 16.sp)
                }
                return@Column
            }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
                itemsIndexed(queue, key = { index, track -> "$index-${track.uri}" }) { index, track ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { vm.skipToQueueIndex(index) }
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
            }
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
