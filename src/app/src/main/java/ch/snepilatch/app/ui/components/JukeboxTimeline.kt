package ch.snepilatch.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import ch.snepilatch.app.playback.JukeboxViz

/**
 * The Eternal Jukebox "remix map" that replaces the seek bar while the jukebox is on. It is NOT
 * draggable — it's a live picture of the remix:
 *
 *  - each pillar is a slice of the track; its height/brightness shows how many self-similar matches
 *    ("jump points") that slice has, so you can see where the song can fold back on itself;
 *  - slices past the buffered edge are dim — you watch them fill in as more of the track loads;
 *  - a bright marker rides the current playhead, jumping around once it passes the centre and starts
 *    remixing.
 */
@Composable
fun JukeboxTimeline(
    viz: JukeboxViz?,
    primary: Color,
    modifier: Modifier = Modifier
) {
    val buckets = viz?.buckets ?: FloatArray(0)
    val buffered = viz?.bufferedFraction ?: 0f
    val targetPlayhead = viz?.playheadFraction ?: 0f
    // Ease the marker between the 120ms viz ticks so linear play glides and jumps still read as jumps.
    val playhead by animateFloatAsState(targetPlayhead, tween(120), label = "jukeboxPlayhead")

    Canvas(modifier.fillMaxWidth().height(44.dp)) {
        val n = buckets.size
        if (n == 0) return@Canvas
        val gap = 2.5.dp.toPx()
        val barW = ((size.width - gap * (n - 1)) / n).coerceAtLeast(1f)
        val maxH = size.height
        val playedColor = primary
        val aheadColor = primary.copy(alpha = 0.5f)
        val unbufferedColor = Color.White.copy(alpha = 0.10f)

        for (i in 0 until n) {
            val frac = (i + 0.5f) / n
            val v = buckets[i].coerceIn(0f, 1f)
            val h = (0.16f + 0.84f * v) * maxH
            val x = i * (barW + gap)
            val loaded = frac <= buffered
            val color = when {
                !loaded -> unbufferedColor
                frac <= playhead -> playedColor.copy(alpha = 0.45f + 0.55f * v)
                else -> aheadColor.copy(alpha = 0.35f + 0.5f * v)
            }
            drawRoundRect(
                color = color,
                topLeft = Offset(x, (maxH - h) / 2f),
                size = Size(barW, h),
                cornerRadius = CornerRadius(barW / 2f, barW / 2f)
            )
        }

        // Buffered edge: a faint line showing how far the track has loaded.
        val bx = (buffered.coerceIn(0f, 1f) * size.width).coerceIn(0f, size.width)
        drawRoundRect(
            color = Color.White.copy(alpha = 0.25f),
            topLeft = Offset((bx - 1f).coerceIn(0f, size.width), maxH * 0.15f),
            size = Size(2f, maxH * 0.7f),
            cornerRadius = CornerRadius(1f, 1f)
        )

        // Playhead marker — thin and translucent so it hints at position without dominating the map.
        val px = (playhead.coerceIn(0f, 1f) * size.width)
        val markerW = 1.5.dp.toPx()
        drawRoundRect(
            color = Color.White.copy(alpha = 0.4f),
            topLeft = Offset((px - markerW / 2f).coerceIn(0f, size.width - markerW), 0f),
            size = Size(markerW, maxH),
            cornerRadius = CornerRadius(markerW / 2f, markerW / 2f)
        )
    }
}
