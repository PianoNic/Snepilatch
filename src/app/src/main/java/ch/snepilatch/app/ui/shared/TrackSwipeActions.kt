package ch.snepilatch.app.ui.shared

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.unit.dp
import ch.snepilatch.app.data.TrackInfo
import ch.snepilatch.app.logic.shared.AppSettings
import ch.snepilatch.app.logic.shared.ThemeController
import ch.snepilatch.app.ui.theme.SnepilatchBlack
import ch.snepilatch.app.ui.theme.SnepilatchWhite
import ch.snepilatch.app.viewmodel.PlaybackViewModel
import kotlinx.coroutines.launch
import kotlin.math.abs

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
internal fun SwipeableActionRow(
    startToEndAction: SwipeAction? = null,
    endToStartAction: SwipeAction? = null,
    modifier: Modifier = Modifier,
    enableDismissFromStartToEnd: Boolean = startToEndAction != null,
    enableDismissFromEndToStart: Boolean = endToStartAction != null,
    contentBackground: Color = Color.Transparent,
    content: @Composable () -> Unit,
) {
    val state = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    val flashAlpha = remember { Animatable(0f) }
    var completedDirection by remember { mutableStateOf<SwipeToDismissBoxValue?>(null) }
    val density = LocalDensity.current
    val offset = runCatching { abs(state.requireOffset()) }.getOrDefault(0f)
    val cornerRadiusPx = with(density) {
        CORNER_RADIUS.toPx() * swipeProgress(offset, CORNER_RADIUS_DISTANCE.toPx())
    }
    val foregroundBackground = if (offset > 0f && contentBackground.alpha == 0f) {
        SnepilatchBlack
    } else {
        contentBackground
    }

    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        enableDismissFromStartToEnd = startToEndAction != null && enableDismissFromStartToEnd,
        enableDismissFromEndToStart = endToStartAction != null && enableDismissFromEndToStart,
        onDismiss = { direction ->
            val action = when (direction) {
                SwipeToDismissBoxValue.StartToEnd -> startToEndAction
                SwipeToDismissBoxValue.EndToStart -> endToStartAction
                SwipeToDismissBoxValue.Settled -> null
            }
            action?.run?.invoke()
            completedDirection = direction.takeUnless { it == SwipeToDismissBoxValue.Settled }
            scope.launch {
                flashAlpha.snapTo(0f)
                flashAlpha.animateTo(FLASH_ALPHA, tween(FLASH_MS, easing = FastOutSlowInEasing))
                flashAlpha.animateTo(0f, tween(FLASH_MS, easing = FastOutSlowInEasing))
                completedDirection = null
                if (action?.resetAfterRun == true) state.reset()
            }
        },
        backgroundContent = {
            val direction = completedDirection ?: state.dismissDirection
            val action = when (direction) {
                SwipeToDismissBoxValue.StartToEnd -> startToEndAction
                SwipeToDismissBoxValue.EndToStart -> endToStartAction
                SwipeToDismissBoxValue.Settled -> null
            }
            if (action != null) {
                SwipeActionBackground(
                    state = state,
                    action = action,
                    fromStart = direction == SwipeToDismissBoxValue.StartToEnd,
                    flashAlpha = flashAlpha.value,
                )
            }
        },
        content = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(with(density) { cornerRadiusPx.toDp() }))
                    .background(foregroundBackground)
            ) { content() }
        },
    )
}

/**
 * The action stays anchored behind the row. Its color fills the background while swiping, while
 * its icon and text are clipped to the revealed width.
 */
@Composable
private fun SwipeActionBackground(
    state: SwipeToDismissBoxState,
    action: SwipeAction,
    fromStart: Boolean,
    flashAlpha: Float = 0f,
) {
    val offset = runCatching { abs(state.requireOffset()) }.getOrDefault(0f)
    val density = LocalDensity.current
    val iconProgress = with(density) {
        swipeProgress(offset, ICON_DISTANCE.toPx())
    }
    val iconScale = 0.5f + iconProgress * 0.5f
    val iconTranslationX = with(density) {
        ICON_START_TRANSLATION.toPx() * (1f - iconProgress) * if (fromStart) -1f else 1f
    }
    Box(
        Modifier
            .fillMaxSize()
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
                val left = if (fromStart) 0f else size.width - revealWidth
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

internal fun swipeProgress(offset: Float, distance: Float): Float =
    (abs(offset) / distance).coerceIn(0f, 1f)

private val CORNER_RADIUS = 12.dp
private val CORNER_RADIUS_DISTANCE = 48.dp
private val ICON_DISTANCE = 96.dp
private val ACTION_HORIZONTAL_PADDING = 16.dp
private val ICON_START_TRANSLATION = 16.dp
private const val FLASH_ALPHA = 0.24f
private const val FLASH_MS = 200
