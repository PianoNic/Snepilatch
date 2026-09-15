package ch.snepilatch.app.ui.shared

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.snepilatch.app.R
import ch.snepilatch.app.logic.shared.JamHolder
import ch.snepilatch.app.ui.theme.SnepilatchElevated
import ch.snepilatch.app.ui.theme.SnepilatchLightGray
import ch.snepilatch.app.ui.theme.SnepilatchWhite
import ch.snepilatch.app.viewmodel.JamViewModel

/**
 * Joining a jam by link. Once the account is in one, the jam lives at the top of the queue sheet
 * (see [JamHeader]), so this closes itself as soon as a join lands.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JamSheet(onDismiss: () -> Unit, jamVm: JamViewModel = viewModel()) {
    val jam by jamVm.jam.collectAsState()
    val joining by jamVm.joining.collectAsState()
    val starting by jamVm.starting.collectAsState()
    val error by jamVm.error.collectAsState()
    var link by remember { mutableStateOf("") }
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    LaunchedEffect(jam?.sessionId) { if (jam != null) onDismiss() }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SnepilatchElevated,
        dragHandle = { SheetDragHandle() },
    ) {
        SheetNavBarFix()
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Groups, null, tint = SnepilatchWhite, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.jam_start_title), color = SnepilatchWhite, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.jam_start_hint), color = SnepilatchLightGray, fontSize = 13.sp)
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { jamVm.start() },
                enabled = !starting && !joining,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(if (starting) R.string.jam_starting else R.string.jam_start))
            }
            if (error == JamViewModel.START_FAILED) {
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.jam_start_failed), color = SnepilatchLightGray, fontSize = 12.sp)
            }
            Spacer(Modifier.height(20.dp))
            Text(stringResource(R.string.join_jam), color = SnepilatchWhite, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.jam_paste_hint), color = SnepilatchLightGray, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = link,
                onValueChange = { link = it },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.jam_link_placeholder), color = SnepilatchLightGray) },
                modifier = Modifier.fillMaxWidth()
            )
            if (error != null && error != JamViewModel.START_FAILED) {
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(if (error == JamHolder.NOT_READY) R.string.jam_not_ready else R.string.jam_join_failed),
                    color = SnepilatchLightGray,
                    fontSize = 12.sp
                )
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { jamVm.join(link) },
                enabled = !joining && link.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(if (joining) R.string.jam_joining else R.string.jam_join))
            }
            Spacer(Modifier.navigationBarsPadding().height(12.dp))
        }
    }
}
