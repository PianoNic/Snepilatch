package ch.snepilatch.app.ui.shared

import ch.snepilatch.app.R
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.snepilatch.app.ui.theme.SnepilatchLightGray
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * A 3-dots button that opens the [EntityMenuSheet], driven by a
 * caller-supplied list of [actions] with a header from [title]/[subtitle]/[imageUrl]. Used by the
 * search-result cards so songs/artists/albums/playlists all get a menu (go to artist/album, add to
 * playlist, share, …). Renders nothing when [actions] is empty.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverflowMenu(
    title: String,
    subtitle: String,
    imageUrl: String?,
    circular: Boolean,
    actions: List<MenuAction>,
    modifier: Modifier = Modifier
) {
    if (actions.isEmpty()) return
    var showMenu by remember { mutableStateOf(false) }
    IconButton(onClick = { showMenu = true }, modifier = modifier.size(36.dp)) {
        Icon(Icons.Rounded.MoreVert, stringResource(R.string.more), tint = SnepilatchLightGray, modifier = Modifier.size(20.dp))
    }
    if (showMenu) {
        EntityMenuSheet(
            imageUrl = imageUrl,
            title = title,
            subtitle = subtitle,
            actions = actions.map { action ->
                MenuAction(action.icon, action.label) {
                    action.onClick()
                    showMenu = false
                }
            },
            onDismiss = { showMenu = false },
            circular = circular,
        )
    }
}
