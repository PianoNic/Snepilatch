package ch.snepilatch.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
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
import kotlin.math.roundToInt

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
            QueueList(vm, queue, queuedCount, Modifier.weight(1f))
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
/**
 * A stable, unique key per queue row.
 *
 * Stable because an index in the key renames every row below a removed one, which rebuilds the list
 * and lets a swipe offset land on a different track. Unique because a repeated key throws inside a
 * LazyColumn, and qid is not guaranteed unique: an autoplay queue can hold two entries with the same
 * uid and iteration. The first occurrence keeps the plain key, later ones carry their count.
 */
internal fun queueRowKeys(queue: List<ch.snepilatch.app.data.TrackInfo>): List<String> {
    val seen = mutableMapOf<String, Int>()
    return queue.map { track ->
        val base = track.qid ?: track.uri
        val nth = seen.merge(base, 1, Int::plus) ?: 1
        if (nth == 1) base else "$base#$nth"
    }
}

/** One row's drag wiring, bundled so a row takes a drag contract rather than six loose callbacks. */
private class RowDrag(
    val dragging: Boolean,
    val offsetY: Float,
    val onMeasured: (Int) -> Unit,
    val onStart: () -> Unit,
    val onDrag: (Float) -> Unit,
    val onEnd: () -> Unit,
)

/**
 * The queue itself: what you queued, then what simply plays next, each under its own header.
 *
 * Rows are keyed by the entry rather than its position. An index in the key renames every row below
 * a removed one, which rebuilds the list and can recycle a swipe offset onto a different track.
 * Duplicate qids do occur in an autoplay queue and a repeated key throws, so the nth copy is suffixed.
 */
@Composable
private fun QueueList(
    vm: PlaybackViewModel,
    queue: List<ch.snepilatch.app.data.TrackInfo>,
    queuedCount: Int,
    modifier: Modifier = Modifier,
) {
    val rowKeys = remember(queue) { queueRowKeys(queue) }
    // Row height is measured rather than assumed, so the drop lands on the row under the finger
    // even if the row's padding changes later.
    var dragKey by remember { mutableStateOf<String?>(null) }
    var dragDy by remember { mutableFloatStateOf(0f) }
    var rowHeight by remember { mutableIntStateOf(0) }

    fun dragFor(key: String, track: ch.snepilatch.app.data.TrackInfo, displayIndex: Int) = RowDrag(
        dragging = key == dragKey,
        offsetY = if (key == dragKey) dragDy else 0f,
        onMeasured = { rowHeight = it },
        onStart = {
            dragKey = key
            dragDy = 0f
        },
        onDrag = { dragDy += it },
        onEnd = {
            val rows = if (rowHeight > 0) (dragDy / rowHeight).roundToInt() else 0
            dragKey = null
            dragDy = 0f
            if (rows != 0) vm.moveQueueEntry(track, displayIndex + rows)
        },
    )

    val upNext = queue.drop(queuedCount)
    LazyColumn(modifier, contentPadding = PaddingValues(bottom = 16.dp)) {
        if (queuedCount > 0) {
            item(key = "header-queued") { SectionHeader(stringResource(R.string.queue_next_in_queue)) }
            itemsIndexed(queue.take(queuedCount), key = { i, _ -> rowKeys[i] }) { i, track ->
                SwipeableQueueRow(
                    track,
                    dragFor(rowKeys[i], track, i),
                    onClick = { vm.skipToQueueIndex(i) },
                    onRemove = { vm.removeFromQueue(track) },
                )
            }
        }
        if (upNext.isNotEmpty()) {
            item(key = "header-upnext") { SectionHeader(stringResource(R.string.queue_next_up)) }
            itemsIndexed(upNext, key = { i, _ -> rowKeys[queuedCount + i] }) { i, track ->
                SwipeableQueueRow(
                    track,
                    dragFor(rowKeys[queuedCount + i], track, queuedCount + i),
                    onClick = { vm.skipToQueueIndex(queuedCount + i) },
                    onRemove = { vm.removeFromQueue(track) },
                )
            }
        }
    }
}

@Composable
private fun SwipeableQueueRow(
    track: ch.snepilatch.app.data.TrackInfo,
    drag: RowDrag,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    val state = rememberSwipeToDismissBoxState()
    SwipeToDismissBox(
        state = state,
        modifier = Modifier
            // The dragged row rides above the rest and follows the finger; everything else
            // holds still until the drop, so the list cannot reflow under the gesture.
            .zIndex(if (drag.dragging) 1f else 0f)
            .offset { IntOffset(0, drag.offsetY.roundToInt()) },
        onDismiss = { value -> if (value == SwipeToDismissBoxValue.EndToStart) onRemove() },
        // A vertical drag on the grip must not also read as a swipe to delete.
        enableDismissFromEndToStart = !drag.dragging,
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
        QueueRow(track, onClick, drag.onMeasured, drag.onStart, drag.onDrag, drag.onEnd)
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
private fun QueueRow(
    track: ch.snepilatch.app.data.TrackInfo,
    onClick: () -> Unit,
    onMeasured: (Int) -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .onSizeChanged { onMeasured(it.height) }
            // Opaque on purpose: the delete panel sits behind every row, so a transparent row shows
            // it through and the whole list reads as though it were mid-swipe.
            .background(SpfyElevated)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SpfyImage(track.albumArt, Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)))
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(track.name, color = SpfyWhite, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.artist, color = SpfyLightGray, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(
            Icons.Rounded.DragHandle,
            stringResource(R.string.queue_reorder),
            tint = SpfyLightGray,
            modifier = Modifier
                .padding(start = 8.dp)
                .size(24.dp)
                // The grip starts the drag, not the row: a row-wide vertical drag would fight
                // the list's own scrolling, which is why a touch queue needs a handle at all.
                .pointerInput(track.qid) {
                    detectDragGestures(
                        onDragStart = { onDragStart() },
                        onDrag = { change, amount ->
                            change.consume()
                            onDrag(amount.y)
                        },
                        onDragEnd = { onDragEnd() },
                        onDragCancel = { onDragEnd() },
                    )
                }
        )
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
