package ch.snepilatch.app.ui.shared

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.snepilatch.app.R
import ch.snepilatch.app.logic.podcast.YourEpisodes
import ch.snepilatch.app.ui.theme.SnepilatchWhite
import ch.snepilatch.app.viewmodel.DetailViewModel
import ch.snepilatch.app.viewmodel.PlaybackViewModel

/**
 * The heart that likes or unlikes [trackUri]; [accent] colours it while liked, [buttonBg] is its disc.
 * An episode gets the web player's + for Your Episodes instead, since only songs can be liked.
 */
@Composable
fun LikeToggleButton(
    isLiked: Boolean,
    trackUri: String?,
    vm: PlaybackViewModel,
    buttonBg: Color,
    accent: Color,
    size: Dp,
    iconSize: Dp,
) {
    if (trackUri?.startsWith("spotify:episode:") == true) {
        YourEpisodesToggleButton(trackUri, buttonBg, accent, size, iconSize)
        return
    }
    FilledIconToggleButton(
        checked = isLiked,
        onCheckedChange = { _ ->
            val id = trackUri?.removePrefix("spotify:track:") ?: return@FilledIconToggleButton
            if (isLiked) vm.unlikeSong(id) else vm.likeSong(id)
        },
        modifier = Modifier.size(size),
        colors = IconButtonDefaults.filledIconToggleButtonColors(
            containerColor = buttonBg,
            contentColor = SnepilatchWhite.copy(alpha = 0.7f),
            checkedContainerColor = buttonBg,
            checkedContentColor = accent,
        ),
    ) {
        Icon(
            if (isLiked) Icons.Rounded.Favorite else Icons.Filled.FavoriteBorder,
            stringResource(R.string.like),
            modifier = Modifier.size(iconSize),
        )
    }
}

@Composable
private fun YourEpisodesToggleButton(episodeUri: String, buttonBg: Color, accent: Color, size: Dp, iconSize: Dp) {
    val detailVm: DetailViewModel = viewModel()
    val savedEpisodes by YourEpisodes.saved.collectAsState()
    val saved = episodeUri in savedEpisodes
    LaunchedEffect(episodeUri) { detailVm.refreshSavedEpisodes(listOf(episodeUri)) }
    FilledIconToggleButton(
        checked = saved,
        onCheckedChange = { detailVm.toggleEpisodeSaved(episodeUri) },
        modifier = Modifier.size(size),
        colors = IconButtonDefaults.filledIconToggleButtonColors(
            containerColor = buttonBg,
            contentColor = SnepilatchWhite.copy(alpha = 0.7f),
            checkedContainerColor = buttonBg,
            checkedContentColor = accent,
        ),
    ) {
        Icon(
            if (saved) Icons.Rounded.CheckCircle else Icons.Rounded.AddCircleOutline,
            stringResource(if (saved) R.string.remove_from_your_episodes else R.string.save_to_your_episodes),
            modifier = Modifier.size(iconSize),
        )
    }
}
