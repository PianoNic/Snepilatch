@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package ch.snepilatch.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.snepilatch.app.viewmodel.ProfileViewModel
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ExitToApp
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import ch.snepilatch.app.R
import ch.snepilatch.app.ui.shared.TightAlertDialog
import ch.snepilatch.app.ui.theme.*
import ch.snepilatch.app.logic.shared.AccountStore
import ch.snepilatch.app.logic.shared.ThemeController
import ch.snepilatch.app.viewmodel.PlaybackViewModel
import ch.snepilatch.app.viewmodel.addAccount
import ch.snepilatch.app.viewmodel.logout
import ch.snepilatch.app.viewmodel.switchAccount
import kotlinx.coroutines.launch
import ch.snepilatch.app.ui.shared.SettingRow
import ch.snepilatch.app.ui.shared.SettingsSectionHeader

@Composable
fun AccountScreen(vm: PlaybackViewModel) {
    val account by vm.account.collectAsState()
    val theme by ThemeController.themeColors.collectAsState()
    val animatedPrimary by animateColorAsState(theme.primary, tween(800), label = "accPrimary")

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = 12.dp, bottom = LocalBottomOverlayHeight.current.value + 16.dp)
    ) {
        // A page opened from the drawer now, not a tab, so it gets the way back the other pages have.
        IconButton(onClick = { vm.goBack() }, modifier = Modifier.padding(horizontal = 8.dp)) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back), tint = SnepilatchWhite)
        }
        // Header
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 32.dp, end = 32.dp, bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Profile image: a tap offers to change or remove it (#840).
            val profileVm: ProfileViewModel = viewModel()
            val profileBusy by profileVm.busy.collectAsState()
            val context = LocalContext.current
            var showPictureMenu by remember { mutableStateOf(false) }
            val picturePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { picked ->
                if (picked != null) profileVm.setPicture(context, picked) { vm.refreshAccount() }
            }
            Box {
                Box(
                    Modifier
                        .size(120.dp)
                        .clip(CircleShape)
                        .background(SnepilatchGray)
                        .clickable(enabled = !profileBusy) { showPictureMenu = true },
                    contentAlignment = Alignment.Center
                ) {
                    if (account.profileImageUrl != null) {
                        AsyncImage(
                            model = account.profileImageUrl,
                            contentDescription = stringResource(R.string.profile_image),
                            modifier = Modifier.size(120.dp).clip(CircleShape),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(Icons.Rounded.Person, null, tint = SnepilatchLightGray, modifier = Modifier.size(64.dp))
                    }
                    if (profileBusy) CircularProgressIndicator(color = SnepilatchWhite, modifier = Modifier.size(32.dp))
                }
                DropdownMenu(expanded = showPictureMenu, onDismissRequest = { showPictureMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.change_profile_picture)) },
                        onClick = {
                            showPictureMenu = false
                            picturePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                    )
                    if (account.profileImageUrl != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.remove_profile_picture)) },
                            onClick = {
                                showPictureMenu = false
                                profileVm.removePicture { vm.refreshAccount() }
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Text(
                account.displayName.ifEmpty { account.username.ifEmpty { stringResource(R.string.loading_dots) } },
                color = SnepilatchWhite,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(6.dp))
            Text(
                listOf(
                    pluralStringResource(R.plurals.profile_playlists, account.playlistCount, account.playlistCount),
                    pluralStringResource(R.plurals.profile_followers, account.followers, account.followers),
                    pluralStringResource(R.plurals.profile_following, account.following, account.following),
                ).joinToString(" · "),
                color = SnepilatchLightGray,
                fontSize = 13.sp
            )

            Spacer(Modifier.height(12.dp))

            if (account.isPremium) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = animatedPrimary.copy(alpha = 0.15f)),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Star, null, tint = animatedPrimary, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.premium), color = animatedPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        SettingsSectionHeader(stringResource(R.string.account_section_profile))

        val dots = stringResource(R.string.placeholder_dots)
        val premiumLabel = stringResource(R.string.premium)
        val freeLabel = stringResource(R.string.plan_free)
        SettingRow(
            title = stringResource(R.string.username),
            subtitle = account.displayName.ifEmpty { account.username.ifEmpty { dots } },
            icon = Icons.Rounded.Person,
        )
        SettingRow(
            title = stringResource(R.string.user_id),
            subtitle = account.username.ifEmpty { dots },
            icon = Icons.Rounded.Badge,
        )
        SettingRow(
            title = stringResource(R.string.plan),
            subtitle = if (account.isPremium) premiumLabel else freeLabel,
            icon = Icons.Rounded.CreditCard,
        )

        AccountsSection(vm, account.username)

        Spacer(Modifier.height(16.dp))

        val context = androidx.compose.ui.platform.LocalContext.current
        ListItem(
            headlineContent = { Text(stringResource(R.string.log_out), color = Color(0xFFE57373)) },
            leadingContent = { Icon(Icons.AutoMirrored.Rounded.ExitToApp, null, tint = Color(0xFFE57373)) },
            trailingContent = { Icon(Icons.Rounded.ChevronRight, null, tint = SnepilatchLightGray) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable { vm.logout(context) }
        )
    }
}

/**
 * The signed-in accounts (#847). The active one is marked and does nothing on a tap; any other one
 * switches, which tears the session down and rebuilds it from that account's cookies. The last row
 * signs a further account in, leaving the listed ones alone.
 */
@Composable
private fun AccountsSection(vm: PlaybackViewModel, activeUsername: String) {
    val accounts by AccountStore.accounts.collectAsState()
    val theme by ThemeController.themeColors.collectAsState()
    val context = LocalContext.current

    Spacer(Modifier.height(24.dp))
    SettingsSectionHeader(stringResource(R.string.account_section_accounts))

    accounts.forEach { saved ->
        val active = saved.username == activeUsername
        ListItem(
            headlineContent = { Text(saved.displayName.ifEmpty { saved.username }, color = SnepilatchWhite) },
            supportingContent = { Text(saved.username, color = SnepilatchLightGray, fontSize = 12.sp) },
            leadingContent = {
                if (saved.imageUrl != null) {
                    AsyncImage(
                        model = saved.imageUrl,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp).clip(CircleShape),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(SnepilatchGray), Alignment.Center) {
                        Icon(Icons.Rounded.Person, null, tint = SnepilatchLightGray)
                    }
                }
            },
            trailingContent = {
                // Both states occupy an icon button's width, so the trailing column lines up.
                Box(Modifier.size(TRAILING_SLOT), Alignment.Center) {
                    if (active) {
                        Icon(Icons.Rounded.CheckCircle, stringResource(R.string.active), tint = theme.primary)
                    } else {
                        IconButton(onClick = { AccountStore.forget(saved.username) }) {
                            Icon(Icons.Rounded.PersonRemove, stringResource(R.string.remove_account), tint = SnepilatchLightGray)
                        }
                    }
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable(enabled = !active) { vm.switchAccount(context, saved) },
        )
    }

    // Same 40dp leading circle as an account row, so the rows line up down the section.
    ListItem(
        headlineContent = { Text(stringResource(R.string.add_account), color = SnepilatchWhite) },
        leadingContent = {
            Box(Modifier.size(40.dp).clip(CircleShape).background(SnepilatchGray), Alignment.Center) {
                Icon(Icons.Rounded.PersonAdd, null, tint = SnepilatchLightGray)
            }
        },
        trailingContent = {
            Box(Modifier.size(TRAILING_SLOT), Alignment.Center) {
                Icon(Icons.Rounded.ChevronRight, null, tint = SnepilatchLightGray)
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable { vm.addAccount() },
    )
}

private val TRAILING_SLOT = 48.dp

/** Free-text settings dialog, e.g. the Loki debug-logging endpoint. Empty input clears the setting. */
@Composable
internal fun TextInputDialog(
    title: String,
    description: String? = null,
    initialValue: String,
    placeholder: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var value by remember { mutableStateOf(initialValue) }
    TightAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, color = SnepilatchWhite) },
        text = {
            Column {
                if (description != null) {
                    Text(description, color = SnepilatchLightGray, fontSize = 13.sp)
                    Spacer(Modifier.height(12.dp))
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    placeholder = { Text(placeholder, color = SnepilatchLightGray.copy(alpha = 0.7f)) },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = keyboardType),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = SnepilatchWhite,
                        unfocusedTextColor = SnepilatchWhite,
                        unfocusedBorderColor = SnepilatchLightGray
                    )
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

/** Stands in for Spfy in the audio-source dialog, which selects on a String while the setting is null. */

/** Turns a SAF tree uri into something recognisable, e.g. "primary:Music/Snepilatch" -> "Music/Snepilatch". */
internal fun readableFolder(uri: android.net.Uri): String {
    val id = runCatching { android.provider.DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
    return id?.substringAfter(':')?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment.orEmpty()
}
