package ch.snepilatch.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.lifecycle.viewmodel.compose.viewModel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import ch.snepilatch.app.R
import ch.snepilatch.app.ui.theme.*
import ch.snepilatch.app.viewmodel.JamViewModel
import kotify.api.jam.JamSession

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JamSheet(onDismiss: () -> Unit, jamVm: JamViewModel = viewModel()) {
    val jam by jamVm.jam.collectAsState()
    val joining by jamVm.joining.collectAsState()
    val error by jamVm.error.collectAsState()
    var link by remember { mutableStateOf("") }
    val context = androidx.compose.ui.platform.LocalContext.current

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { jamVm.join(it) }
    }
    fun launchScanner() {
        scanLauncher.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setBeepEnabled(false)
                .setOrientationLocked(false)
                .setPrompt(context.getString(R.string.jam_scan_prompt))
        )
    }
    // ZXing's capture activity asks for the camera itself, but asking here means the prompt comes
    // from a tap on our button rather than from a screen that has already opened black.
    val cameraPermission = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) launchScanner() }
    val onScanQr: () -> Unit = {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.CAMERA
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) launchScanner() else cameraPermission.launch(android.Manifest.permission.CAMERA)
    }

    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )

    LaunchedEffect(jam?.sessionId) { if (jam != null) jamVm.refresh() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = SpfyElevated) {
        SheetNavBarFix()
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Groups, null, tint = SpfyWhite, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.jam), color = SpfyWhite, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(12.dp))

            val current = jam
            if (current == null) {
                JamJoinForm(
                    link = link,
                    onLinkChange = { link = it },
                    joining = joining,
                    error = error,
                    onJoin = { jamVm.join(link) },
                    onScanQr = onScanQr
                )
            } else {
                JamMembers(
                    session = current,
                    joining = joining,
                    onLeave = {
                        jamVm.leave()
                        onDismiss()
                    }
                )
            }
            Spacer(Modifier.navigationBarsPadding().height(12.dp))
        }
    }
}

@Composable
private fun JamJoinForm(
    link: String,
    onLinkChange: (String) -> Unit,
    joining: Boolean,
    error: String?,
    onJoin: () -> Unit,
    onScanQr: () -> Unit
) {
    Text(stringResource(R.string.jam_paste_hint), color = SpfyLightGray, fontSize = 13.sp)
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = link,
        onValueChange = onLinkChange,
        singleLine = true,
        placeholder = { Text(stringResource(R.string.jam_link_placeholder), color = SpfyLightGray) },
        modifier = Modifier.fillMaxWidth()
    )
    if (error != null) {
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(if (error == "not_ready") R.string.jam_not_ready else R.string.jam_join_failed),
            color = SpfyLightGray,
            fontSize = 12.sp
        )
    }
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onJoin, enabled = !joining && link.isNotBlank(), modifier = Modifier.weight(1f)) {
            Text(stringResource(if (joining) R.string.jam_joining else R.string.jam_join))
        }
        OutlinedButton(onClick = onScanQr, enabled = !joining) {
            Icon(Icons.Rounded.QrCodeScanner, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.jam_scan))
        }
    }
}

@Composable
private fun JamMembers(session: JamSession, joining: Boolean, onLeave: () -> Unit) {
    Text(stringResource(R.string.jam_members, session.members.size), color = SpfyLightGray, fontSize = 13.sp)
    Spacer(Modifier.height(8.dp))
    session.members.forEach { m ->
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            SpfyImage(url = m.imageUrl, modifier = Modifier.size(36.dp), shape = CircleShape)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    m.displayName.ifBlank { m.username },
                    color = SpfyWhite,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (m.id == session.sessionOwnerId) {
                    Text(stringResource(R.string.jam_host), color = SpfyLightGray, fontSize = 12.sp)
                }
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    OutlinedButton(
        onClick = onLeave,
        enabled = !joining,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(stringResource(R.string.jam_leave), color = SpfyWhite)
    }
}
