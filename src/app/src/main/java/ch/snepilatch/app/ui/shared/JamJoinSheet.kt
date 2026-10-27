package ch.snepilatch.app.ui.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.snepilatch.app.R
import ch.snepilatch.app.logic.shared.JamHolder
import ch.snepilatch.app.logic.shared.ThemeController
import ch.snepilatch.app.ui.theme.SnepilatchElevated
import ch.snepilatch.app.ui.theme.SnepilatchGray
import ch.snepilatch.app.ui.theme.SnepilatchLightGray
import ch.snepilatch.app.ui.theme.SnepilatchWhite
import ch.snepilatch.app.viewmodel.JamViewModel
import coil.compose.AsyncImage

/**
 * How to take part in the jam a link opened, asked before joining as the official app does: play on
 * this phone (listen along) or on the host's speaker (control only). The server lets only Premium
 * accounts listen along ("User not allowed to join session" otherwise, verified live), so for a Free
 * account that row is shown with its badge but cannot be picked.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JamJoinSheet(pending: JamHolder.PendingJam, isPremium: Boolean, myImageUrl: String?, jamVm: JamViewModel = viewModel()) {
    val joining by jamVm.joining.collectAsState()
    val error by jamVm.error.collectAsState()
    val theme by ThemeController.themeColors.collectAsState()
    var choice by remember { mutableStateOf<JamViewModel.JoinChoice?>(null) }
    val host = pending.jam.host
    val hostName = host?.displayName.orEmpty()
    // A Snepilatch relay jam: listening is ours to allow, not Spfy's, so no Premium gate and no YouTube copy.
    val relay = ch.snepilatch.app.logic.relay.RelayJamMapper.isRelay(pending.jam)
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    ModalBottomSheet(
        onDismissRequest = { jamVm.cancelJoin() },
        sheetState = sheetState,
        containerColor = SnepilatchElevated,
        dragHandle = { SheetDragHandle() },
    ) {
        SheetNavBarFix()
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Centred: the host's ring makes its avatar taller than this account's.
            Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Avatar(myImageUrl)
                // The host's picture sits over this account's, cut out by a ring in the sheet's colour,
                // the way the jam banner stacks its members.
                Avatar(host?.imageUrl, Modifier.offset(x = (-AVATAR_OVERLAP)).zIndex(1f).background(SnepilatchElevated, CircleShape).padding(AVATAR_RING))
            }
            Spacer(Modifier.height(20.dp))
            Text(
                stringResource(R.string.jam_join_title, hostName),
                color = SnepilatchWhite, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.jam_join_subtitle), color = SnepilatchLightGray, fontSize = 14.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(20.dp))
            ChoiceRow(
                icon = Icons.Rounded.Smartphone,
                title = stringResource(R.string.jam_play_here, android.os.Build.MODEL),
                sub = if (relay) null else stringResource(R.string.premium),
                subColor = theme.primary,
                selected = choice == JamViewModel.JoinChoice.LISTEN,
                enabled = relay || isPremium,
            ) { choice = JamViewModel.JoinChoice.LISTEN }
            // What a Free account gets instead of listening on Spfy: the same jam on this phone from
            // YouTube Music, while it only controls the host's playback on Spfy (#1026).
            if (!isPremium && !relay) {
                ChoiceRow(
                    icon = Icons.Rounded.Smartphone,
                    title = stringResource(R.string.jam_listen_along_ytm),
                    sub = stringResource(R.string.jam_listen_along_ytm_desc),
                    subColor = SnepilatchLightGray,
                    selected = choice == JamViewModel.JoinChoice.LISTEN_VIA_YOUTUBE,
                    enabled = true,
                ) { choice = JamViewModel.JoinChoice.LISTEN_VIA_YOUTUBE }
            }
            ChoiceRow(
                icon = Icons.Rounded.Speaker,
                title = stringResource(R.string.jam_play_on_host, hostName),
                sub = null,
                subColor = SnepilatchLightGray,
                selected = choice == JamViewModel.JoinChoice.CONTROL,
                enabled = true,
            ) { choice = JamViewModel.JoinChoice.CONTROL }
            if (error == JamHolder.FAILED) {
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.jam_join_failed), color = SnepilatchLightGray, fontSize = 12.sp)
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = { choice?.let(jamVm::confirmJoin) }, enabled = choice != null && !joining) {
                Text(stringResource(if (joining) R.string.jam_joining else R.string.jam_join))
            }
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.jam_join_privacy), color = SnepilatchLightGray, fontSize = 12.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.navigationBarsPadding().height(8.dp))
        }
    }
}

@Composable
private fun Avatar(url: String?, modifier: Modifier = Modifier) {
    Box(modifier.size(AVATAR_SIZE).clip(CircleShape).background(SnepilatchGray), contentAlignment = Alignment.Center) {
        if (url != null) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(AVATAR_SIZE))
        } else {
            Icon(Icons.Rounded.Person, null, tint = SnepilatchLightGray)
        }
    }
}

/** One way to take part: an icon, its name, a line under it (the Premium badge, or what it uses), and the radio. */
@Composable
private fun ChoiceRow(
    icon: ImageVector,
    title: String,
    sub: String?,
    subColor: androidx.compose.ui.graphics.Color,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            .alpha(if (enabled) 1f else DISABLED_ALPHA),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = SnepilatchWhite, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = SnepilatchWhite, fontSize = 16.sp)
            if (sub != null) {
                Text(sub, color = subColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        RadioButton(selected = selected, onClick = null, enabled = enabled)
    }
}

private const val DISABLED_ALPHA = 0.45f
private val AVATAR_SIZE = 64.dp
private val AVATAR_RING = 4.dp
private val AVATAR_OVERLAP = 16.dp
