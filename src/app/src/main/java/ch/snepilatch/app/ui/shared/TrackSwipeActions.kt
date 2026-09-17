package ch.snepilatch.app.ui.shared

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.TargetedFlingBehavior
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.onSizeChanged
import ch.snepilatch.app.data.TrackInfo
import ch.snepilatch.app.logic.shared.AppSettings
import ch.snepilatch.app.logic.shared.ThemeController
import ch.snepilatch.app.ui.theme.SnepilatchBlack
import ch.snepilatch.app.ui.theme.SnepilatchWhite
import ch.snepilatch.app.viewmodel.PlaybackViewModel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Adds the configured start-to-end and end-to-start actions to a track row without changing its tap
 * behavior. The actions are the player's own [PlayerAction]s for the row's track. Offline rows are
 * left alone, every swipe action needs a session.
 */
@Composable
fun SwipeableTrackRow(
    track: TrackInfo,
    vm: PlaybackViewModel,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val offline by vm.isOffline.collectAsState()
    if (offline) {
        Box(modifier) { content() }
        return
    }
    val theme by ThemeController.themeColors.collectAsState()
    val endToStart by AppSettings.swipeLeftAction.collectAsState()
    val startToEnd by AppSettings.swipeRightAction.collectAsState()
    var showJam by remember { mutableStateOf(false) }
    var showCode by remember { mutableStateOf(false) }
    val actions = rememberPlayerActions(vm, track) { overlay ->
        when (overlay) {
            PlayerOverlay.PlaylistPicker -> vm.showPlaylistPickerForTrack(track.uri)
            PlayerOverlay.Jam -> showJam = true
            PlayerOverlay.Code -> showCode = true
        }
    }
    SwipeableActionRow(
        modifier = modifier,
        startToEndAction = actions.firstOrNull { it.shortcut == startToEnd }?.let {
            SwipeAction(it.icon, it.label, theme.primary, it.run)
        },
        endToStartAction = actions.firstOrNull { it.shortcut == endToStart }?.let {
            SwipeAction(it.icon, it.label, theme.primary, it.run)
        },
        content = content,
    )

    if (showJam) JamSheet(onDismiss = { showJam = false })
    if (showCode) ScannableCodeSheet(track, theme.primary) { showCode = false }
}

internal data class SwipeAction(
    val icon: ImageVector,
    val label: String,
    val tint: Color,
    val run: () -> Unit,
    val resetAfterRun: Boolean = true,
)

/**
 * Adds the shared swipe gesture and action treatment to a row. The action content stays composed at
 * its full size and is clipped to the part uncovered by the moving row.
 */
@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod")
internal fun SwipeableActionRow(
    modifier: Modifier = Modifier,
    startToEndAction: SwipeAction? = null,
    endToStartAction: SwipeAction? = null,
    enableDismissFromStartToEnd: Boolean = startToEndAction != null,
    enableDismissFromEndToStart: Boolean = endToStartAction != null,
    contentBackground: Color = Color.Transparent,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val commitThresholdPx = with(density) { SWIPE_ACTIVATION_DISTANCE.toPx() }
    val state = remember {
        AnchoredDraggableState(
            initialValue = SwipeAnchor.Settled,
            anchors = DraggableAnchors { SwipeAnchor.Settled at 0f },
        )
    }
    val flingBehavior = remember(state, commitThresholdPx) {
        thresholdFlingBehavior(state, commitThresholdPx)
    }
    val flashAlpha = remember { Animatable(0f) }
    var completedDirection by remember { mutableStateOf<SwipeAnchor?>(null) }
    var rowWidthPx by remember { mutableIntStateOf(0) }
    var startRevealWidthPx by remember(startToEndAction?.label) { mutableFloatStateOf(commitThresholdPx) }
    var endRevealWidthPx by remember(endToStartAction?.label) { mutableFloatStateOf(commitThresholdPx) }
    val startEnabled = startToEndAction != null && enableDismissFromStartToEnd
    val endEnabled = endToStartAction != null && enableDismissFromEndToStart
    val anchors = remember(rowWidthPx, startEnabled, endEnabled) {
        DraggableAnchors {
            if (rowWidthPx > 0 && endEnabled) SwipeAnchor.EndToStart at -rowWidthPx.toFloat()
            SwipeAnchor.Settled at 0f
            if (rowWidthPx > 0 && startEnabled) SwipeAnchor.StartToEnd at rowWidthPx.toFloat()
        }
    }
    SideEffect {
        state.updateAnchors(
            anchors,
            newTarget = if (anchors.hasPositionFor(state.settledValue)) {
                state.settledValue
            } else {
                SwipeAnchor.Settled
            },
        )
    }
    val rawOffset = state.requireOffset()
    val offset = abs(rawOffset)
    val maxRevealDistancePx = if (rawOffset >= 0f) startRevealWidthPx else endRevealWidthPx
    val boundedOffset = boundedRevealOffset(rawOffset, maxRevealDistancePx)
    val physicalDirection = if (layoutDirection == LayoutDirection.Ltr) 1f else -1f
    val cornerRadiusPx = with(density) {
        CORNER_RADIUS.toPx() * swipeProgress(offset, CORNER_RADIUS_DISTANCE.toPx())
    }
    val foregroundBackground = if (offset > 0f && contentBackground.alpha == 0f) {
        SnepilatchBlack
    } else {
        contentBackground
    }

    val settledDirection = state.settledValue.takeUnless { it == SwipeAnchor.Settled }
    val settledAction = when (settledDirection) {
        SwipeAnchor.StartToEnd -> startToEndAction
        SwipeAnchor.EndToStart -> endToStartAction
        SwipeAnchor.Settled, null -> null
    }

    LaunchedEffect(settledDirection) {
        val action = settledAction ?: return@LaunchedEffect
        completedDirection = settledDirection
        flashAlpha.snapTo(FLASH_ALPHA)
        if (action.resetAfterRun) {
            action.run.invoke()
            coroutineScope {
                launch { flashAlpha.animateTo(0f, tween(FLASH_MS, easing = FastOutSlowInEasing)) }
                launch { state.animateTo(SwipeAnchor.Settled) }
            }
        } else {
            flashAlpha.animateTo(0f, tween(FLASH_MS, easing = FastOutSlowInEasing))
            action.run.invoke()
        }
        completedDirection = null
    }

    Box(
        modifier
            .onSizeChanged { rowWidthPx = it.width }
            .anchoredDraggable(
                state = state,
                orientation = Orientation.Horizontal,
                enabled = startEnabled || endEnabled,
                flingBehavior = flingBehavior,
            ),
    ) {
        val direction = completedDirection ?: when {
            rawOffset > 0f -> SwipeAnchor.StartToEnd
            rawOffset < 0f -> SwipeAnchor.EndToStart
            else -> SwipeAnchor.Settled
        }
        val action = when (direction) {
            SwipeAnchor.StartToEnd -> startToEndAction
            SwipeAnchor.EndToStart -> endToStartAction
            SwipeAnchor.Settled -> null
        }
        if (action != null) {
            SwipeActionBackground(
                modifier = Modifier.matchParentSize(),
                offset = offset,
                action = action,
                fromStart = direction == SwipeAnchor.StartToEnd,
                flashAlpha = flashAlpha.value,
                onMeasured = { measuredWidth ->
                    if (direction == SwipeAnchor.StartToEnd) {
                        startRevealWidthPx = maxOf(commitThresholdPx, measuredWidth)
                    } else {
                        endRevealWidthPx = maxOf(commitThresholdPx, measuredWidth)
                    }
                },
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .offset {
                    IntOffset((boundedOffset * physicalDirection).roundToInt(), 0)
                }
                .clip(RoundedCornerShape(with(density) { cornerRadiusPx.toDp() }))
                .background(foregroundBackground)
        ) { content() }
    }
}

/**
 * The action stays anchored behind the row. Its color fills the background while swiping, while
 * its icon and text are clipped to the revealed width.
 */
@Composable
private fun SwipeActionBackground(
    modifier: Modifier,
    offset: Float,
    action: SwipeAction,
    fromStart: Boolean,
    flashAlpha: Float = 0f,
    onMeasured: (Float) -> Unit,
) {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val iconProgress = with(density) {
        swipeProgress(offset, SWIPE_ACTIVATION_DISTANCE.toPx())
    }
    val iconScale = 0.5f + iconProgress * 0.5f
    val fromLeft = revealFromLeft(fromStart, layoutDirection == LayoutDirection.Ltr)
    val iconTranslationX = with(density) {
        ICON_START_TRANSLATION.toPx() * (1f - iconProgress) * if (fromLeft) -1f else 1f
    }
    Box(
        modifier
            .drawBehind {
                if (offset > 0f) {
                    drawRect(action.tint)
                    if (flashAlpha > 0f) {
                        drawRect(SnepilatchWhite.copy(alpha = flashAlpha))
                    }
                }
            }
            .drawWithContent {
                val revealWidth = offset.coerceAtMost(size.width)
                val left = if (fromLeft) 0f else size.width - revealWidth
                clipRect(left = left, right = left + revealWidth) { this@drawWithContent.drawContent() }
            }
    ) {
        val iconModifier = Modifier.graphicsLayer {
            scaleX = iconScale
            scaleY = iconScale
            translationX = iconTranslationX
        }
        Row(
            Modifier
                .align(if (fromStart) Alignment.CenterStart else Alignment.CenterEnd)
                .fillMaxHeight()
                .onSizeChanged { onMeasured(it.width.toFloat()) }
                .padding(horizontal = ACTION_HORIZONTAL_PADDING),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (fromStart) Arrangement.Start else Arrangement.End,
        ) {
            if (fromStart) {
                Icon(
                    action.icon,
                    action.label,
                    tint = SnepilatchWhite,
                    modifier = iconModifier,
                )
                Text(action.label, color = SnepilatchWhite, maxLines = 1, modifier = Modifier.padding(start = 8.dp))
            } else {
                Text(action.label, color = SnepilatchWhite, maxLines = 1, modifier = Modifier.padding(end = 8.dp))
                Icon(
                    action.icon,
                    action.label,
                    tint = SnepilatchWhite,
                    modifier = iconModifier,
                )
            }
        }
    }
}

private fun thresholdFlingBehavior(
    state: AnchoredDraggableState<SwipeAnchor>,
    threshold: Float,
): TargetedFlingBehavior = object : TargetedFlingBehavior {
    override suspend fun ScrollScope.performFling(
        initialVelocity: Float,
        onRemainingDistanceUpdated: (Float) -> Unit,
    ): Float {
        val start = state.requireOffset()
        val target = swipeTarget(start, threshold, state.anchors)
        val targetOffset = state.anchors.positionOf(target)
        var previous = start
        onRemainingDistanceUpdated(targetOffset - start)
        animate(start, targetOffset, animationSpec = tween()) { value, _ ->
            previous += scrollBy(value - previous)
            onRemainingDistanceUpdated(targetOffset - previous)
        }
        return 0f
    }
}

internal enum class SwipeAnchor {
    EndToStart,
    Settled,
    StartToEnd,
}

internal fun swipeTarget(
    offset: Float,
    threshold: Float,
    anchors: DraggableAnchors<SwipeAnchor>,
): SwipeAnchor = when {
    offset >= threshold && anchors.hasPositionFor(SwipeAnchor.StartToEnd) -> SwipeAnchor.StartToEnd
    offset <= -threshold && anchors.hasPositionFor(SwipeAnchor.EndToStart) -> SwipeAnchor.EndToStart
    else -> SwipeAnchor.Settled
}

internal fun revealFromLeft(fromStart: Boolean, isLtr: Boolean): Boolean = fromStart == isLtr

internal fun swipeProgress(offset: Float, distance: Float): Float =
    (abs(offset) / distance).coerceIn(0f, 1f)

internal fun boundedRevealOffset(offset: Float, maxDistance: Float): Float =
    offset.coerceIn(-maxDistance, maxDistance)

private val CORNER_RADIUS = 12.dp
private val CORNER_RADIUS_DISTANCE = 48.dp
private val SWIPE_ACTIVATION_DISTANCE = 96.dp
private val ACTION_HORIZONTAL_PADDING = 16.dp
private val ICON_START_TRANSLATION = 16.dp
private const val FLASH_ALPHA = 0.24f
private const val FLASH_MS = 200
