@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package ch.snepilatch.app.ui.components

import ch.snepilatch.app.R
import ch.snepilatch.app.ui.theme.SpotifyWhite
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.runtime.collectAsState
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.*
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import android.os.Build
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.snepilatch.app.data.TrackInfo
import ch.snepilatch.app.ui.theme.SpotifyElevated
import ch.snepilatch.app.ui.theme.SpotifyGray
import ch.snepilatch.app.ui.theme.SpotifyLightGray
import ch.snepilatch.app.util.formatTime
import ch.snepilatch.app.viewmodel.SpotifyViewModel
import coil.compose.AsyncImage

/**
 * Match the app's transparent, edge-to-edge nav bar inside a ModalBottomSheet. The sheet
 * has its own window that re-enables the nav-bar contrast scrim the Activity turned off,
 * which shows as a static white backdrop behind the system buttons. Call at the top of the
 * sheet's content.
 */
@Composable
fun SheetNavBarFix() {
    val view = LocalView.current
    SideEffect {
        val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
    }
}

// --- Shimmer effect ---

@Composable
fun shimmerBrush(): Brush {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val translateAnim by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)),
        label = "shimmer"
    )
    return Brush.linearGradient(
        colors = listOf(SpotifyGray, SpotifyElevated, SpotifyGray),
        start = Offset(translateAnim - 500f, 0f),
        end = Offset(translateAnim, 0f)
    )
}

@Composable
fun ShimmerBox(modifier: Modifier) {
    Box(modifier.background(shimmerBrush()))
}

// --- Image with placeholder ---

@Composable
fun SpotifyImage(
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
            .background(SpotifyGray),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = SpotifyLightGray.copy(alpha = 0.5f), modifier = Modifier.size(32.dp))
        if (!url.isNullOrEmpty()) {
            AsyncImage(
                model = coil.request.ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                    .data(url)
                    .crossfade(300)
                    .build(),
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
    }
}

// --- Track Row ---

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackRow(track: TrackInfo, vm: SpotifyViewModel, contextUri: String? = null, trackIndex: Int? = null) {
    var showMenu by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    // Subscribe only to the two projections that affect a row (which track is current +
    // whether it's playing), not the whole PlaybackUiState — otherwise every position tick
    // recomposes every visible row and scrolling janks.
    val currentUri by vm.currentTrackUri.collectAsState()
    val playing by vm.isPlayingFlow.collectAsState()
    val isPlaying = currentUri == track.uri && playing
    val theme by vm.themeColors.collectAsState()
    val accent = theme.primary

    Row(
        Modifier
            .fillMaxWidth()
            .clickable { vm.playTrack(track, contextUri, trackIndex) }
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SpotifyImage(
            url = track.albumArt,
            modifier = Modifier.size(48.dp),
            shape = RoundedCornerShape(4.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(track.name, color = if (isPlaying) accent else SpotifyWhite, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.artist, color = SpotifyLightGray, fontSize = 13.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (track.durationMs > 0) {
            Text(formatTime(track.durationMs), color = SpotifyLightGray, fontSize = 12.sp)
            Spacer(Modifier.width(4.dp))
        }
        IconButton(onClick = { showMenu = true }, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Rounded.MoreVert, stringResource(R.string.more), tint = SpotifyLightGray, modifier = Modifier.size(20.dp))
        }
    }

    // Bottom sheet menu
    if (showMenu) {
        val sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        )
        ModalBottomSheet(
            onDismissRequest = { showMenu = false },
            sheetState = sheetState,
            containerColor = SpotifyElevated,
            dragHandle = {
                Box(
                    Modifier
                        .padding(vertical = 12.dp)
                        .width(40.dp)
                        .height(4.dp)
                        .background(SpotifyLightGray.copy(alpha = 0.4f), RoundedCornerShape(2.dp))
                )
            }
        ) {
            SheetNavBarFix()
            // Track header
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SpotifyImage(url = track.albumArt, modifier = Modifier.size(48.dp), shape = RoundedCornerShape(8.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(track.name, color = SpotifyWhite, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(track.artist, color = SpotifyLightGray, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = SpotifyLightGray.copy(alpha = 0.15f))

            val shareLabel = stringResource(R.string.share)
            val addToQueueLabel = stringResource(R.string.add_to_queue)
            val addToPlaylistLabel = stringResource(R.string.add_to_playlist)
            val likeLabel = stringResource(R.string.like)
            val visitAlbumLabel = stringResource(R.string.visit_album)
            val visitArtistLabel = stringResource(R.string.visit_artist)
            val items = listOf(
                Triple(Icons.AutoMirrored.Rounded.QueueMusic, addToQueueLabel) {
                    vm.addToQueue(track.uri); showMenu = false
                },
                Triple(Icons.AutoMirrored.Rounded.PlaylistAdd, addToPlaylistLabel) {
                    showMenu = false; vm.showPlaylistPickerForTrack(track.uri)
                },
                Triple(Icons.Rounded.Favorite, likeLabel) {
                    vm.likeSong(track.uri.removePrefix("spotify:track:")); showMenu = false
                },
                Triple(Icons.Rounded.Album, visitAlbumLabel) {
                    showMenu = false; vm.openAlbumForTrack(track.uri)
                },
                Triple(Icons.Rounded.Person, visitArtistLabel) {
                    showMenu = false; vm.openArtistForTrack(track.uri)
                },
                Triple(Icons.Rounded.Share, shareLabel) {
                    showMenu = false
                    val id = track.uri.removePrefix("spotify:track:")
                    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_TEXT, "https://open.spotify.com/track/$id")
                    }
                    context.startActivity(android.content.Intent.createChooser(intent, shareLabel))
                }
            )

            items.forEach { (icon, label, onClick) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onClick() }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(icon, null, tint = SpotifyWhite, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(16.dp))
                    Text(label, color = SpotifyWhite, fontSize = 15.sp)
                }
            }
            Spacer(Modifier.navigationBarsPadding().height(12.dp))
        }
    }
}

// --- Profile Info Item ---

@Composable
fun ProfileInfoItem(label: String, value: String, icon: ImageVector) {
    ListItem(
        headlineContent = { Text(label, color = SpotifyWhite) },
        supportingContent = { Text(value, color = SpotifyLightGray) },
        leadingContent = { Icon(icon, null, tint = SpotifyLightGray) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}
