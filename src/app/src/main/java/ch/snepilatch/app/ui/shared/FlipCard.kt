package ch.snepilatch.app.ui.shared

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.min

/**
 * A card that turns over around its vertical axis: [front] while it faces the viewer, [back] once
 * it has turned past the edge, drawn mirrored back so it reads the right way round. The turn has
 * momentum (#830): every tap adds spin, eased in over a moment, friction bleeds it off, and only
 * once the spin has nearly gone does the card settle onto the next side that matches [flipped].
 * One tap is a clean half turn; taps in quick succession stack up and the card spins faster, always
 * the same way round. At rest only the facing side is composed, so the hidden one takes no gestures;
 * while turning, the side about to show is composed invisibly ahead of the edge. A tap anywhere on
 * the card that nothing inside it claims calls [onTap]; a tap on a button or a drag on a list inside
 * keeps working as before.
 */
@Composable
fun FlipCard(
    flipped: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    back: @Composable BoxScope.() -> Unit,
    front: @Composable BoxScope.() -> Unit,
) {
    var angle by remember { mutableFloatStateOf(if (flipped) HALF_TURN else 0f) }
    var velocity by remember { mutableFloatStateOf(0f) }
    var pendingKick by remember { mutableFloatStateOf(0f) }
    var turning by remember { mutableStateOf(false) }
    var kicks by remember { mutableIntStateOf(0) }
    var seenFlipped by remember { mutableStateOf(flipped) }
    LaunchedEffect(flipped) {
        if (flipped != seenFlipped) {
            seenFlipped = flipped
            kicks++
        }
    }
    // Every kick restarts the loop with the spin the card already has plus the new push, so a tap
    // mid-turn never stops it. The push is fed in over a short ramp for a soft start, the card
    // coasts under friction for a soft end, then eases onto the resting angle.
    LaunchedEffect(kicks) {
        if (kicks == 0) return@LaunchedEffect
        pendingKick += KICK_DEG_PER_S
        turning = true
        try {
            var last = withFrameNanos { it }
            while (pendingKick > 0f || velocity > SETTLE_DEG_PER_S) {
                val now = withFrameNanos { it }
                val dt = ((now - last) / 1e9f).coerceAtMost(MAX_FRAME_S)
                last = now
                val push = min(pendingKick, KICK_DEG_PER_S * dt / KICK_RAMP_S)
                pendingKick -= push
                velocity += push
                angle += velocity * dt
                velocity *= exp(-FRICTION_PER_S * dt)
            }
            val rest = if (flipped) HALF_TURN else 0f
            // The next resting angle ahead; when the coast has only just overshot one, ease back
            // those few degrees rather than crawl a whole extra turn to the one after.
            var target = ceil((angle - rest) / FULL_TURN) * FULL_TURN + rest
            if (target - angle > FULL_TURN - MAX_BACKTRACK) target -= FULL_TURN
            // Underdamped on purpose: the card overshoots its side a little and springs back to lock in.
            animate(angle, target, velocity, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMediumLow)) { value, _ -> angle = value }
            velocity = 0f
        } finally {
            turning = false
        }
    }
    val facing = angle % FULL_TURN
    val showingBack = facing > HALF_TURN / 2 && facing < HALF_TURN * 1.5f
    // While turning, the side about to appear is composed early and kept invisible, so its first
    // layout and image decode are paid before the edge, not in the frame it shows.
    val composeFront = !showingBack || turning
    val composeBack = showingBack || turning
    Box(
        modifier
            .pointerInput(Unit) { detectTapGestures { onTap() } }
            .graphicsLayer {
                rotationY = angle
                cameraDistance = CAMERA_DISTANCE_DP * density
                // The front may draw its neighbours outside the frame (the cover strip's swipe peek);
                // those must not turn with the card, so the card clips itself except when at rest face up.
                clip = facing != 0f
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

private const val HALF_TURN = 180f
private const val FULL_TURN = 360f

/** The spin one tap adds, in degrees per second; with the friction below one tap coasts about 150 degrees. */
private const val KICK_DEG_PER_S = 560f

/** How far past a resting angle a coast may end and still ease back to it instead of going on to the next. */
private const val MAX_BACKTRACK = 60f

/** The push is fed in over this long, so a turn starts softly instead of jumping. */
private const val KICK_RAMP_S = 0.12f

/** How fast the spin bleeds off: the velocity falls to a third about every third of a second. */
private const val FRICTION_PER_S = 3f

/** Below this the card stops coasting and eases onto its resting side. */
private const val SETTLE_DEG_PER_S = 90f

/** A frame after a long stall is treated as this long, so the card does not jump a whole turn. */
private const val MAX_FRAME_S = 0.05f

/** How far the camera sits from the card in dp; further means less perspective distortion mid-turn. */
private const val CAMERA_DISTANCE_DP = 12f
