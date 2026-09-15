package ch.snepilatch.app.ui.shared

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import ch.snepilatch.app.data.PlayerShortcut
import ch.snepilatch.app.data.TrackInfo
import ch.snepilatch.app.logic.shared.AppSettings
import ch.snepilatch.app.logic.shared.ThemeController
import ch.snepilatch.app.ui.theme.SnepilatchWhite
import ch.snepilatch.app.viewmodel.PlaybackViewModel
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
    val state = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
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
    val flashAlpha = remember { Animatable(0f) }
    var completedDirection by remember { mutableStateOf<SwipeToDismissBoxValue?>(null) }

    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        onDismiss = { direction ->
            val shortcut = swipeActionFor(direction, endToStart, startToEnd)
            actions.firstOrNull { it.shortcut == shortcut }?.run?.invoke()
            completedDirection = direction.takeUnless { it == SwipeToDismissBoxValue.Settled }
            scope.launch {
                flashAlpha.snapTo(0f)
                flashAlpha.animateTo(FLASH_ALPHA, tween(FLASH_MS, easing = FastOutSlowInEasing))
                flashAlpha.animateTo(0f, tween(FLASH_MS, easing = FastOutSlowInEasing))
                completedDirection = null
                state.reset()
            }
        },
        backgroundContent = {
            val direction = completedDirection ?: state.dismissDirection
            val shortcut = swipeActionFor(direction, endToStart, startToEnd)
            val action = actions.firstOrNull { it.shortcut == shortcut }
            if (action != null) {
                SwipeActionBackground(state, action, direction == SwipeToDismissBoxValue.StartToEnd, theme.primary, flashAlpha.value)
            }
        },
        content = { Box(Modifier.fillMaxWidth()) { content() } },
    )

    if (showJam) JamSheet(onDismiss = { showJam = false })
    if (showCode) ScannableCodeSheet(track, theme.primary) { showCode = false }
}

/** Which configured shortcut a swipe in [direction] runs; none while the row sits still. */
internal fun swipeActionFor(
    direction: SwipeToDismissBoxValue,
    endToStart: PlayerShortcut,
    startToEnd: PlayerShortcut,
): PlayerShortcut? = when (direction) {
    SwipeToDismissBoxValue.EndToStart -> endToStart
    SwipeToDismissBoxValue.StartToEnd -> startToEnd
    SwipeToDismissBoxValue.Settled -> null
}

/**
 * The label under the row, sized to the strip the row has uncovered so nothing shows through the
 * row itself, which keeps whatever background the screen paints. Flashes [tint] once the action ran.
 */
@Composable
private fun SwipeActionBackground(
    state: SwipeToDismissBoxState,
    action: PlayerAction,
    fromStart: Boolean,
    tint: Color,
    flashAlpha: Float,
) {
    Box(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .align(if (fromStart) Alignment.CenterStart else Alignment.CenterEnd)
                .fillMaxHeight()
                .layout { measurable, constraints ->
                    val exposed = runCatching { abs(state.requireOffset()).roundToInt() }.getOrDefault(0).coerceIn(0, constraints.maxWidth)
                    val placeable = measurable.measure(constraints.copy(minWidth = exposed, maxWidth = exposed))
                    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                }
                .clipToBounds()
                .drawBehind { drawRect(tint.copy(alpha = flashAlpha)) }
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (fromStart) Arrangement.Start else Arrangement.End,
        ) {
            if (fromStart) {
                Icon(action.icon, action.label, tint = SnepilatchWhite)
                Text(action.label, color = SnepilatchWhite, maxLines = 1, modifier = Modifier.padding(start = 8.dp))
            } else {
                Text(action.label, color = SnepilatchWhite, maxLines = 1, modifier = Modifier.padding(end = 8.dp))
                Icon(action.icon, action.label, tint = SnepilatchWhite)
            }
        }
    }
}

private const val FLASH_ALPHA = 0.24f
private const val FLASH_MS = 200
