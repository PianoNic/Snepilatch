package ch.snepilatch.app.ui.shared

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import ch.snepilatch.app.R
import ch.snepilatch.app.ui.theme.SnepilatchWhite
import ch.snepilatch.app.viewmodel.PlaybackViewModel

/** The heart that likes or unlikes [trackUri]; [accent] colours it while liked, [buttonBg] is its disc. */
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
