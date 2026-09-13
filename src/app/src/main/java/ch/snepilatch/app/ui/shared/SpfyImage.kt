@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package ch.snepilatch.app.ui.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import ch.snepilatch.app.ui.theme.SnepilatchGray
import ch.snepilatch.app.ui.theme.SnepilatchLightGray
import coil.compose.AsyncImage

@Composable
fun SpfyImage(
    url: String?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(8.dp),
    icon: ImageVector = Icons.Rounded.MusicNote
) {
    // AsyncImage instead of SubcomposeAsyncImage: subcomposition per row is expensive in a
    // scrolling list, and the old `loading` slot ran an infinite LoadingIndicator animation
    // in *every* not-yet-loaded row. A static placeholder box with a faint icon sits behind
    // the image; the opaque cropped artwork covers it once loaded (crossfade), and it stays
    // visible while loading or on error — same look, none of the per-row cost.
    Box(
        modifier
            .clip(shape)
            .background(SnepilatchGray),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = SnepilatchLightGray.copy(alpha = 0.5f), modifier = Modifier.size(32.dp))
        if (!url.isNullOrEmpty()) {
            val ctx = androidx.compose.ui.platform.LocalContext.current
            AsyncImage(
                model = remember(url) { coil.request.ImageRequest.Builder(ctx).data(url).crossfade(300).build() },
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
    }
}

// --- Smooth playback position ---
