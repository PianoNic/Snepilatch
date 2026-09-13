package ch.snepilatch.app.ui.shared

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import ch.snepilatch.app.ui.theme.SnepilatchElevated
import ch.snepilatch.app.ui.theme.SnepilatchGray

/**
 * A shimmer placeholder driven by a shared [phase] transition (see HomeShimmer). Reading phase.value
 * inside onDrawBehind registers a DRAW-phase snapshot read, so animation frames invalidate only the
 * draw, not composition — the box never recomposes as the sweep animates.
 */
@Composable
fun ShimmerBox(phase: State<Float>, modifier: Modifier) {
    Box(
        modifier.drawWithCache {
            onDrawBehind {
                val x = phase.value
                drawRect(
                    Brush.linearGradient(
                        colors = listOf(SnepilatchGray, SnepilatchElevated, SnepilatchGray),
                        start = Offset(x - 500f, 0f),
                        end = Offset(x, 0f)
                    )
                )
            }
        }
    )
}

// --- Image with placeholder ---
