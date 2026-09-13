package ch.snepilatch.app.ui.shared

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.snepilatch.app.R
import ch.snepilatch.app.logic.shared.shareLink
import ch.snepilatch.app.ui.theme.SnepilatchElevated
import ch.snepilatch.app.ui.theme.SnepilatchLightGray
import ch.snepilatch.app.ui.theme.SnepilatchWhite

/**
 * The official app's invite sheet: a share button and a QR code. [shareLink] and [qrLink] are the
 * two `spotify.link` variants once known, the long link before that.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JamInviteSheet(shareLink: String, qrLink: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val shareLabel = stringResource(R.string.jam_share_link)
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
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.jam_invite), color = SnepilatchWhite, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(16.dp))
            Button(onClick = { shareLink(context, shareLink, shareLabel) }) {
                Icon(Icons.Rounded.Share, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(shareLabel)
            }
            Spacer(Modifier.height(24.dp))
            HorizontalDivider(color = SnepilatchLightGray.copy(alpha = 0.15f))
            Spacer(Modifier.height(24.dp))
            QrCode(qrLink, Modifier.size(200.dp).clip(RoundedCornerShape(8.dp)))
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.jam_qr_hint), color = SnepilatchLightGray, fontSize = 13.sp)
            Spacer(Modifier.navigationBarsPadding().height(20.dp))
        }
    }
}
