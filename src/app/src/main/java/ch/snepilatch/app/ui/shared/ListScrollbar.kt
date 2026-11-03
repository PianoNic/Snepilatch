package ch.snepilatch.app.ui.shared

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private const val ROW_KEY = "row-"
private const val IDLE_ALPHA = 0.35f
private const val ACTIVE_ALPHA = 0.85f
private val TOP_INSET = 96.dp
private val THUMB_HEIGHT = 56.dp
private val THUMB_WIDTH = 5.dp
private val THUMB_WIDTH_DRAGGING = 9.dp
private val THUMB_GAP = 3.dp
private val GRAB_WIDTH = 28.dp
private val GRAB_SLOP = 16.dp

/** The key of a list row the scrollbar counts; other items, such as a page's header, are not rows. */
fun scrollbarRowKey(row: Int) = "$ROW_KEY$row"

private fun visibleRow(key: Any): Int? {
    val text = key as? String ?: return null
    return if (text.startsWith(ROW_KEY)) text.removePrefix(ROW_KEY).toIntOrNull() else null
}

/** The rows on screen, or null while none are. */
fun LazyListLayoutInfo.visibleRows(): IntRange? {
    val rows = visibleItemsInfo.mapNotNull { visibleRow(it.key) }
    return if (rows.isEmpty()) null else rows.first()..rows.last()
}

/** The item index of row 0: read from a row on screen, or else the first item below what is showing. */
private fun LazyListLayoutInfo.firstRowItem(): Int {
    val shown = visibleItemsInfo.firstNotNullOfOrNull { item -> visibleRow(item.key)?.let { item.index - it } }
    return shown ?: ((visibleItemsInfo.lastOrNull()?.index ?: -1) + 1)
}

/**
 * A scrollbar on a list of [rows] rows. Dragging the thumb jumps straight to that place, and only a touch on
 * the thumb itself is taken, so the row menus at the same edge keep working.
 */
@Composable
fun Modifier.listScrollbar(state: LazyListState, rows: Int, bottomInset: Dp): Modifier {
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val top = with(density) { TOP_INSET.toPx() }
    val bottom = with(density) { bottomInset.toPx() }
    val thumb = with(density) { THUMB_HEIGHT.toPx() }
    val grab = with(density) { GRAB_WIDTH.toPx() }
    val slop = with(density) { GRAB_SLOP.toPx() }
    val gap = with(density) { THUMB_GAP.toPx() }
    val width = with(density) { (if (dragging) THUMB_WIDTH_DRAGGING else THUMB_WIDTH).toPx() }

    fun thumbTop(height: Float): Float {
        val first = state.layoutInfo.visibleRows()?.first ?: 0
        return top + (height - top - bottom - thumb).coerceAtLeast(0f) * first / (rows - 1).coerceAtLeast(1)
    }

    return this
        .drawWithContent {
            drawContent()
            drawRoundRect(
                color = Color.White.copy(alpha = if (dragging || state.isScrollInProgress) ACTIVE_ALPHA else IDLE_ALPHA),
                topLeft = Offset(size.width - width - gap, thumbTop(size.height)),
                size = Size(width, thumb),
                cornerRadius = CornerRadius(width / 2),
            )
        }
        .pointerInput(rows, top, bottom) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val height = size.height.toFloat()
                val onThumb = down.position.x >= size.width - grab &&
                    abs(down.position.y - (thumbTop(height) + thumb / 2)) <= thumb / 2 + slop
                if (!onThumb) return@awaitEachGesture
                down.consume()
                dragging = true
                val firstRow = state.layoutInfo.firstRowItem()
                try {
                    while (true) {
                        val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
                        if (change == null || !change.pressed) break
                        val track = (height - top - bottom - thumb).coerceAtLeast(1f)
                        val fraction = ((change.position.y - top - thumb / 2) / track).coerceIn(0f, 1f)
                        val row = (fraction * (rows - 1)).roundToInt()
                        // The top of the bar is the top of the page, with whatever sits above the rows.
                        scope.launch { state.scrollToItem(if (row == 0) 0 else firstRow + row) }
                        change.consume()
                    }
                } finally {
                    dragging = false
                }
            }
        }
}
