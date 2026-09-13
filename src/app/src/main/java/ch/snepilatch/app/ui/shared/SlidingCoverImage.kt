package ch.snepilatch.app.ui.shared

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import ch.snepilatch.app.ui.theme.SnepilatchGray
import coil.Coil
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The covers around the playing one, so a swipe reveals the real neighbour rather than a placeholder. */
data class CoverNeighbours(
    val previous: String? = null,
    val next: String? = null,
    val secondNext: String? = null,
)

/**
 * The playing track as the strip sees it: what identifies it, whether it was reached going forward, and
 * the last button skip as (press count, direction), where the direction is +1 for previous and -1 for
 * next and a new count slides the strip once.
 */
data class CoverTrack(
    val key: Any?,
    val forward: Boolean = true,
    val buttonSkip: Pair<Int, Int> = 0 to 0,
)

/**
 * Three covers share one offset, keeping their spacing fixed throughout a drag and track change.
 * [onSwipe] gets +1 when the user swiped to the previous track and -1 for the next one.
 */
@Composable
fun SlidingCoverImage(
    url: String?,
    track: CoverTrack,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(8.dp),
    neighbours: CoverNeighbours = CoverNeighbours(),
    onSwipe: ((direction: Int) -> Unit)? = null,
    clipToFrame: Boolean = true,
) {
    val scope = rememberCoroutineScope()
    val gap = with(LocalDensity.current) { 32.dp.toPx() }
    val strip = remember { CoverStrip(scope, gap, track.key, url, neighbours) }
    strip.neighbours = neighbours
    strip.onSwipe = onSwipe
    val painters = rememberCoverPainters(
        listOfNotNull(url, neighbours.previous, neighbours.next, neighbours.secondNext, strip.leftUrl, strip.currentUrl, strip.rightUrl)
            .filter { it.isNotBlank() }
            .distinct(),
        strip.width,
    )

    LaunchedEffect(track.buttonSkip) {
        if (track.buttonSkip.first != 0) strip.skipByButton(track.buttonSkip.second)
    }
    LaunchedEffect(track.key) {
        if (track.key != strip.currentKey) strip.trackChanged(track, url, painters.keys)
    }
    LaunchedEffect(url, painters[url]) {
        strip.showOnceLoaded(track.key, url, painters.keys)
    }
    LaunchedEffect(neighbours, strip.currentKey, strip.dragging, strip.swipeDirection) {
        strip.settleNeighbours(neighbours)
    }

    Box(
        modifier
            .onSizeChanged { strip.width = it.width.toFloat() }
            .then(if (clipToFrame) Modifier.clip(shape) else Modifier)
            .coverDrag(strip, gap),
    ) {
        strip.covers.forEachIndexed { index, cover ->
            Image(
                painter = painters[cover] ?: ColorPainter(SnepilatchGray),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer { translationX = strip.translation + (index - 1) * (size.width + gap) }
                    .clip(shape)
                    .background(SnepilatchGray),
            )
        }
    }
}

/** Where the strip sits and which three covers it shows; every gesture and track change goes through here. */
private class CoverStrip(
    private val scope: CoroutineScope,
    private val gap: Float,
    key: Any?,
    url: String?,
    neighbours: CoverNeighbours,
) {
    private val offset = Animatable(0f)
    private var dragOffset by mutableFloatStateOf(0f)
    private var settleJob: Job? = null
    var width by mutableFloatStateOf(0f)
    var dragging by mutableStateOf(false)
        private set
    var swipeDirection by mutableIntStateOf(0)
        private set
    var currentKey by mutableStateOf(key)
        private set
    var currentUrl by mutableStateOf(url)
        private set
    var leftUrl by mutableStateOf(neighbours.previous)
        private set
    var rightUrl by mutableStateOf(neighbours.next)
        private set

    /** The caller's latest neighbours and swipe handler, refreshed on every composition. */
    var neighbours = neighbours
    var onSwipe: ((Int) -> Unit)? = null

    val covers: List<String?> get() = listOf(leftUrl, currentUrl, rightUrl)
    val translation: Float get() = if (dragging) dragOffset else offset.value
    private val stride: Float get() = width + gap

    fun skipByButton(direction: Int) {
        settleJob?.cancel()
        leftUrl = neighbours.previous
        rightUrl = neighbours.next
        dragging = false
        swipeDirection = direction
        settleJob = scope.launch {
            offset.animateTo(direction * stride, tween(220))
            delay(1000)
            swipeDirection = 0
            offset.animateTo(0f, tween(160))
        }
    }

    /** Moves the strip and replaces its contents together, before the next frame. */
    suspend fun trackChanged(track: CoverTrack, url: String?, loaded: Set<String>) {
        val direction = swipeDirection
        val oldUrl = currentUrl
        val oldOffset = translation
        settleJob?.cancel()
        val movement = if (direction != 0) direction else if (track.forward) -1 else 1
        val incomingUrl = if (movement < 0) rightUrl else leftUrl
        offset.snapTo((if (direction != 0) oldOffset else 0f) - movement * stride)
        dragging = false
        swipeDirection = 0
        currentKey = track.key
        currentUrl = url?.takeIf { it in loaded } ?: incomingUrl?.takeIf { it in loaded } ?: url
        leftUrl = if (movement < 0) oldUrl else neighbours.previous
        rightUrl = if (movement > 0) oldUrl else neighbours.next
        settleJob = scope.launch { offset.animateTo(0f, tween(if (direction != 0) 160 else 220)) }
    }

    /** A cover that finished loading after its track change replaces the neighbour shown meanwhile. */
    fun showOnceLoaded(key: Any?, url: String?, loaded: Set<String>) {
        if (key == currentKey && url in loaded) currentUrl = url
    }

    /** Once the strip has settled, the neighbours follow what the caller says they are. */
    suspend fun settleNeighbours(neighbours: CoverNeighbours) {
        if (dragging || swipeDirection != 0) return
        settleJob?.join()
        leftUrl = neighbours.previous
        rightUrl = neighbours.next
    }

    fun beginDrag() {
        settleJob?.cancel()
        swipeDirection = 0
        if (offset.value == 0f) {
            leftUrl = neighbours.previous
            rightUrl = neighbours.next
        }
        dragOffset = offset.value
        dragging = true
    }

    fun drag(amount: Float) {
        dragOffset = (dragOffset + amount).coerceIn(-stride, stride)
    }

    fun endDrag(velocity: Float) {
        val release = dragOffset
        val direction = when {
            velocity > 700f -> 1
            velocity < -700f -> -1
            release > width * 0.15f -> 1
            release < -width * 0.15f -> -1
            else -> 0
        }
        settleJob = scope.launch {
            offset.snapTo(release)
            dragging = false
            if (direction == 0) {
                offset.animateTo(0f, spring(stiffness = 600f))
                return@launch
            }
            swipeDirection = direction
            onSwipe?.invoke(direction)
            offset.animateTo(direction * stride, tween(160))
            delay(250)
            if (swipeDirection == direction) {
                swipeDirection = 0
                offset.animateTo(0f, spring(stiffness = 600f))
            }
        }
    }

    fun cancelDrag() {
        val release = dragOffset
        settleJob = scope.launch {
            offset.snapTo(release)
            dragging = false
            offset.animateTo(0f, spring(stiffness = 600f))
        }
    }
}

private fun Modifier.coverDrag(strip: CoverStrip, gap: Float): Modifier = pointerInput(gap) {
    val tracker = VelocityTracker()
    detectHorizontalDragGestures(
        onDragStart = {
            tracker.resetTracking()
            strip.beginDrag()
        },
        onHorizontalDrag = { change, amount ->
            tracker.addPosition(change.uptimeMillis, change.position)
            change.consume()
            strip.drag(amount)
        },
        onDragEnd = { strip.endDrag(tracker.calculateVelocity().x) },
        onDragCancel = { strip.cancelDrag() },
    )
}

/** Bitmaps for [urls] at [width] px, decoded off the main thread and dropped once a url leaves the list. */
@Composable
private fun rememberCoverPainters(urls: List<String>, width: Float): Map<String, BitmapPainter> {
    val context = LocalContext.current
    var painters by remember { mutableStateOf(emptyMap<String, BitmapPainter>()) }
    LaunchedEffect(urls, width) {
        if (width <= 0f) return@LaunchedEffect
        painters = painters.filterKeys { it in urls }
        urls.filterNot { it in painters }.forEach { cover ->
            launch {
                val painter = withContext(Dispatchers.IO) {
                    val result = Coil.imageLoader(context).execute(
                        ImageRequest.Builder(context).data(cover).size(width.toInt()).allowHardware(false).build()
                    )
                    (result as? SuccessResult)?.drawable?.let { BitmapPainter(it.toBitmap().asImageBitmap()) }
                }
                if (painter != null) painters = painters + (cover to painter)
            }
        }
    }
    return painters
}
