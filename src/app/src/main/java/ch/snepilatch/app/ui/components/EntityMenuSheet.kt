package ch.snepilatch.app.ui.components

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
import ch.snepilatch.app.ui.theme.SpfyElevated
import ch.snepilatch.app.ui.theme.SpfyLightGray
import ch.snepilatch.app.ui.theme.SpfyWhite

/**
 * The action sheet for a whole entity (playlist, album, artist, show), wherever it is reached from:
 * a detail header or a home card. The chrome and row layout live here so both open the same sheet
 * rather than two that drift apart.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntityMenuSheet(
    imageUrl: String?,
    title: String,
    subtitle: String?,
    actions: List<MenuAction>,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SpfyElevated,
    ) {
        SheetNavBarFix()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SpfyImage(url = imageUrl, modifier = Modifier.size(48.dp), shape = RoundedCornerShape(8.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    title,
                    color = SpfyWhite,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        color = SpfyLightGray,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = SpfyLightGray.copy(alpha = 0.15f))
        actions.forEach { action ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { action.onClick() }
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(action.icon, null, tint = SpfyWhite, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(16.dp))
                Text(action.label, color = SpfyWhite, fontSize = 15.sp)
            }
        }
        Spacer(Modifier.navigationBarsPadding().height(12.dp))
    }
}

/** One row of an [EntityMenuSheet]: what it looks like, what it says, what it does. */
data class MenuAction(val icon: ImageVector, val label: String, val onClick: () -> Unit)
