package ch.snepilatch.app.ui.shared

import ch.snepilatch.app.R
import ch.snepilatch.app.ui.theme.SnepilatchWhite
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.*
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.snepilatch.app.ui.theme.SnepilatchElevated
import ch.snepilatch.app.ui.theme.SnepilatchLightGray
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * A 3-dots button that opens a bottom-sheet context menu (same styling as [TrackRow]'s), driven by a
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
    actions: List<OverflowAction>,
    modifier: Modifier = Modifier
) {
    if (actions.isEmpty()) return
    var showMenu by remember { mutableStateOf(false) }
    IconButton(onClick = { showMenu = true }, modifier = modifier.size(36.dp)) {
        Icon(Icons.Rounded.MoreVert, stringResource(R.string.more), tint = SnepilatchLightGray, modifier = Modifier.size(20.dp))
    }
    if (showMenu) {
        val sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        )
        ModalBottomSheet(
            onDismissRequest = { showMenu = false },
            sheetState = sheetState,
            containerColor = SnepilatchElevated,
            dragHandle = {
                Box(
                    Modifier
                        .padding(vertical = 12.dp)
                        .width(40.dp)
                        .height(4.dp)
                        .background(SnepilatchLightGray.copy(alpha = 0.4f), RoundedCornerShape(2.dp))
                )
            }
        ) {
            SheetNavBarFix()
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SpfyImage(
                    url = imageUrl,
                    modifier = Modifier.size(48.dp),
                    shape = if (circular) androidx.compose.foundation.shape.CircleShape else RoundedCornerShape(8.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(title, color = SnepilatchWhite, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (subtitle.isNotBlank()) {
                        Text(subtitle, color = SnepilatchLightGray, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = SnepilatchLightGray.copy(alpha = 0.15f))
            actions.forEach { action ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            action.onClick()
                            showMenu = false
                        }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(action.icon, null, tint = SnepilatchWhite, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(16.dp))
                    Text(action.label, color = SnepilatchWhite, fontSize = 15.sp)
                }
            }
            Spacer(Modifier.navigationBarsPadding().height(12.dp))
        }
    }
}

// --- Profile Info Item ---
