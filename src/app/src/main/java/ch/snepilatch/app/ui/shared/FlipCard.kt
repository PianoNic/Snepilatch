package ch.snepilatch.app.ui.shared

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput

/**
 * A card that turns over around its vertical axis: [front] while it faces the viewer, [back] once
 * it has turned past the edge, drawn mirrored back so it reads the right way round. Each tap turns
 * the card on in the same direction. At rest only the facing side is composed, so the hidden one
 * takes no gestures; while turning, the side about to show is composed invisibly ahead of the edge.
 * A tap anywhere on the card that nothing inside it claims calls [onTap]; a tap on a button or a
 * drag on a list inside keeps working as before.
 */
@Composable
fun FlipCard(
    flipped: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    back: @Composable BoxScope.() -> Unit,
    front: @Composable BoxScope.() -> Unit,
) {
    // Every change of side adds a half turn, so the card keeps turning the same way round instead
    // of swinging back; an odd count of half turns is the back.
    var halfTurns by remember { mutableIntStateOf(if (flipped) 1 else 0) }
    LaunchedEffect(flipped) {
        if ((halfTurns % 2 == 1) != flipped) halfTurns++
    }
    // A spring rather than a tween: a tap mid-turn re-targets it without dropping the velocity it
    // has, so quick taps stack up half turns and the card spins faster the further it falls behind
    // its target, instead of stalling at every tap (#830).
    val spin = remember { Animatable(halfTurns * HALF_TURN) }
    LaunchedEffect(halfTurns) {
        spin.animateTo(halfTurns * HALF_TURN, spring(Spring.DampingRatioNoBouncy, FLIP_STIFFNESS))
    }
    val rotation = spin.value
    val angle = rotation % FULL_TURN
    val showingBack = angle > HALF_TURN / 2 && angle < HALF_TURN * 1.5f
    // While turning, the side about to appear is composed early and kept invisible, so its first
    // layout and image decode are paid before the edge, not in the frame it shows.
    val turning = spin.isRunning
    val turningToBack = halfTurns % 2 == 1
    val composeFront = !showingBack || (turning && !turningToBack)
    val composeBack = showingBack || (turning && turningToBack)
    Box(
        modifier
            .pointerInput(Unit) { detectTapGestures { onTap() } }
            .graphicsLayer {
                rotationY = rotation
                cameraDistance = CAMERA_DISTANCE_DP * density
                // The front may draw its neighbours outside the frame (the cover strip's swipe peek);
                // those must not turn with the card, so the card clips itself except when at rest face up.
                clip = angle != 0f
            }
    ) {
        if (composeFront) {
            Box(Modifier.matchParentSize().graphicsLayer { alpha = if (showingBack) 0f else 1f }) { front() }
        }
        if (composeBack) {
            Box(
                Modifier.matchParentSize().graphicsLayer {
                    rotationY = HALF_TURN
                    alpha = if (showingBack) 1f else 0f
                }
            ) { back() }
        }
    }
}

private const val FULL_TURN = 360f

private const val HALF_TURN = 180f
/** Firm enough to finish a single turn in well under a second, soft enough to keep momentum across taps. */
private const val FLIP_STIFFNESS = Spring.StiffnessMediumLow

/** How far the camera sits from the card in dp; further means less perspective distortion mid-turn. */
private const val CAMERA_DISTANCE_DP = 12f
