package ch.snepilatch.app.ui.shared

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import ch.snepilatch.app.R

/** A vinyl record turning at a steady pace, the now-playing glyph. */
@Composable
fun SpinningDisc(color: Color, modifier: Modifier = Modifier) {
    val angle by rememberInfiniteTransition(label = "disc").animateFloat(
        initialValue = 0f, targetValue = 360f, label = "angle",
        animationSpec = infiniteRepeatable(tween(DISC_TURN_MS, easing = LinearEasing)),
    )
    Icon(painterResource(R.drawable.ic_vinyl_record), null, tint = color, modifier = modifier.rotate(angle))
}

private const val DISC_TURN_MS = 1800
