package ch.snepilatch.app.ui.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.snepilatch.app.R
import ch.snepilatch.app.data.Screen
import ch.snepilatch.app.logic.shared.AccountStore
import ch.snepilatch.app.logic.shared.ThemeController
import ch.snepilatch.app.ui.theme.SnepilatchGray
import ch.snepilatch.app.ui.theme.SnepilatchLightGray
import ch.snepilatch.app.ui.theme.SnepilatchWhite
import ch.snepilatch.app.viewmodel.FriendActivityViewModel
import ch.snepilatch.app.viewmodel.PlaybackViewModel
import ch.snepilatch.app.viewmodel.WhatsNewViewModel
import ch.snepilatch.app.viewmodel.addAccount
import ch.snepilatch.app.viewmodel.switchAccount
import coil.compose.AsyncImage

/** Opens the profile menu; the app shell provides it, the home header's picture calls it. */
val LocalOpenProfileMenu = compositionLocalOf<() -> Unit> { {} }

/**
 * The menu the profile picture opens, laid out like the official app's side sheet (#976): a card with
 * the account and the others signed in, the places the home header's icons used to lead to, and what
 * friends are playing as a row. Every destination closes the menu first.
 */
@Composable
fun ProfileMenu(vm: PlaybackViewModel, onClose: () -> Unit) {
    val whatsNewVm: WhatsNewViewModel = viewModel()
    val hasNew by whatsNewVm.hasNew.collectAsState()
    val theme by ThemeController.themeColors.collectAsState()
    fun go(action: () -> Unit) {
        onClose()
        action()
    }

    ModalDrawerSheet(drawerContainerColor = MenuBackground, modifier = Modifier.width(MenuWidth).fillMaxHeight()) {
        Column(Modifier.statusBarsPadding().verticalScroll(rememberScrollState()).padding(vertical = 16.dp)) {
            AccountCard(vm, ::go)
            Spacer(Modifier.height(8.dp))
            MenuEntry(
                Icons.Rounded.Campaign, stringResource(R.string.whats_new_title),
                trailing = { if (hasNew) Box(Modifier.size(8.dp).clip(CircleShape).background(theme.primary)) },
            ) { go { vm.navigateTo(Screen.WHATS_NEW) } }
            MenuEntry(Icons.Rounded.DownloadForOffline, stringResource(R.string.downloads)) {
                go { vm.navigateTo(Screen.DOWNLOADS) }
            }
            MenuEntry(Icons.Rounded.Settings, stringResource(R.string.settings)) {
                go { vm.navigateToTab(Screen.ACCOUNT) }
            }
            FriendsRow { go { vm.navigateTo(Screen.FRIEND_ACTIVITY) } }
        }
    }
}

/** The signed-in account, which leads to the account tab, with the others to switch to under it. */
@Composable
private fun AccountCard(vm: PlaybackViewModel, go: (() -> Unit) -> Unit) {
    val account by vm.account.collectAsState()
    val accounts by AccountStore.accounts.collectAsState()
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(true) }
    val others = accounts.filter { it.username != account.username }

    Column(
        Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CardBackground).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).clickable { go { vm.navigateToTab(Screen.ACCOUNT) } },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Avatar(account.profileImageUrl, 52.dp)
                Column {
                    Text(
                        account.displayName.ifEmpty { account.username },
                        color = SnepilatchWhite, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(stringResource(R.string.profile_menu_view_account), color = SnepilatchLightGray, fontSize = 13.sp)
                }
            }
            IconButton(onClick = { expanded = !expanded }) {
                Icon(
                    if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    stringResource(R.string.profile_menu_switch_account), tint = SnepilatchWhite,
                )
            }
        }
        if (expanded) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                others.forEach { saved ->
                    AccountTile(saved.displayName.ifEmpty { saved.username }, { Avatar(saved.imageUrl, 40.dp) }) {
                        go { vm.switchAccount(context, saved) }
                    }
                }
                AccountTile(stringResource(R.string.add_account), {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(SnepilatchGray), Alignment.Center) {
                        Icon(Icons.Rounded.Add, null, tint = SnepilatchWhite)
                    }
                }) { go { vm.addAccount() } }
            }
        }
    }
}

@Composable
private fun AccountTile(label: String, picture: @Composable () -> Unit, onClick: () -> Unit) {
    Column(
        Modifier.width(64.dp).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        picture()
        Text(label, color = SnepilatchWhite, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

/** What friends are playing, newest first, as the official sheet shows it under its menu. */
@Composable
private fun FriendsRow(onOpen: () -> Unit) {
    val friendsVm: FriendActivityViewModel = viewModel()
    val feed by friendsVm.feed.collectAsState()
    // The same live feed the friend screen starts; a second call keeps the one already running.
    LaunchedEffect(Unit) { friendsVm.load() }
    val theme by ThemeController.themeColors.collectAsState()
    // With nobody to show the row would be gone, and with it the way to the friend screen from here.
    if (feed.isEmpty()) {
        MenuEntry(Icons.Rounded.Groups, stringResource(R.string.friend_activity), onClick = onOpen)
        return
    }
    // Straight under the menu without a heading, as in the official sheet; any tile opens the full list.
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(feed, key = { it.userUri }) { friend ->
            Column(
                Modifier.width(76.dp).clickable(onClick = onOpen),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(Modifier.size(64.dp)) {
                    SpfyImage(friend.userImageUrl, Modifier.size(60.dp), shape = CircleShape, icon = Icons.Rounded.Person)
                    SpfyImage(
                        friend.imageUrl,
                        Modifier.size(26.dp).align(Alignment.BottomEnd).offset(x = 2.dp, y = 2.dp),
                    )
                }
                Text(friend.userName, color = SnepilatchWhite, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                // Playing right now: the track in the accent with the equalizer glyph, as the official tile marks it.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (friend.isPlaying) Icon(Icons.Rounded.GraphicEq, null, tint = theme.primary, modifier = Modifier.size(12.dp))
                    Text(
                        friend.trackName,
                        color = if (friend.isPlaying) theme.primary else SnepilatchLightGray,
                        fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun MenuEntry(icon: ImageVector, label: String, trailing: @Composable () -> Unit = {}, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).clickable(onClick = onClick).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = SnepilatchWhite, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, color = SnepilatchWhite, fontSize = 17.sp, modifier = Modifier.weight(1f))
        trailing()
    }
}

/** The round profile picture, or a person glyph when there is none. */
@Composable
fun Avatar(imageUrl: String?, size: Dp, modifier: Modifier = Modifier) {
    if (imageUrl != null) {
        AsyncImage(
            model = imageUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.size(size).clip(CircleShape),
        )
    } else {
        Box(modifier.size(size).clip(CircleShape).background(SnepilatchGray), Alignment.Center) {
            Icon(Icons.Rounded.Person, null, tint = SnepilatchLightGray, modifier = Modifier.size(size * 0.6f))
        }
    }
}

private val MenuWidth = 330.dp
private val MenuBackground = Color(0xFF121212)
private val CardBackground = Color(0xFF232323)
