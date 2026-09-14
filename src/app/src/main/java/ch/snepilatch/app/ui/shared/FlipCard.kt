package ch.snepilatch.app.ui.shared

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput

/**
 * A card that turns over around its vertical axis: [front] while it faces the viewer, [back] once
 * it has turned past the edge, drawn mirrored back so it reads the right way round. Only the
 * facing side is composed, so the hidden one takes no gestures. A tap anywhere on the card that
 * nothing inside it claims calls [onTap]; a tap on a button or a drag on a list inside keeps
 * working as before.
 */
@Composable
fun FlipCard(
    flipped: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    back: @Composable BoxScope.() -> Unit,
    front: @Composable BoxScope.() -> Unit,
) {
    val rotation by animateFloatAsState(
        targetValue = if (flipped) HALF_TURN else 0f,
        animationSpec = tween(FLIP_MS, easing = FastOutSlowInEasing),
        label = "flip",
    )
    Box(
        modifier
            .pointerInput(Unit) { detectTapGestures { onTap() } }
            .graphicsLayer {
                rotationY = rotation
                cameraDistance = CAMERA_DISTANCE_DP * density
            }
    ) {
        if (rotation <= HALF_TURN / 2) {
            front()
        } else {
            Box(Modifier.matchParentSize().graphicsLayer { rotationY = HALF_TURN }) { back() }
        }
    }
}

private const val HALF_TURN = 180f
private const val FLIP_MS = 600

/** How far the camera sits from the card in dp; further means less perspective distortion mid-turn. */
private const val CAMERA_DISTANCE_DP = 12f
