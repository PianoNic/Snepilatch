package ch.snepilatch.app.ui.shared

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.snepilatch.app.ui.theme.SnepilatchElevated
import ch.snepilatch.app.ui.theme.SnepilatchLightGray
import ch.snepilatch.app.ui.theme.SnepilatchWhite

/**
 * The action sheet of the app, whatever it is opened for: a track row, the playing track, a search
 * result, an entity header or a home card. A header of cover, [title] and [subtitle] when there is a
 * [title], then one row per [MenuAction]. Closing after a row is the action's own business.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntityMenuSheet(
    imageUrl: String?,
    title: String?,
    subtitle: String?,
    actions: List<MenuAction>,
    onDismiss: () -> Unit,
    circular: Boolean = false,
) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SnepilatchElevated,
        dragHandle = { SheetDragHandle() },
    ) {
        SheetNavBarFix()
        if (title != null) MenuHeader(imageUrl, title, subtitle, circular)
        actions.forEach { action ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { action.onClick() }
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

@Composable
private fun MenuHeader(imageUrl: String?, title: String, subtitle: String?, circular: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SpfyImage(url = imageUrl, modifier = Modifier.size(48.dp), shape = if (circular) CircleShape else RoundedCornerShape(8.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, color = SnepilatchWhite, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, color = SnepilatchLightGray, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    HorizontalDivider(color = SnepilatchLightGray.copy(alpha = 0.15f))
}

/** One row of an [EntityMenuSheet]: what it looks like, what it says, what it does. */
data class MenuAction(val icon: ImageVector, val label: String, val onClick: () -> Unit)
