@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package ch.snepilatch.app.ui.screens

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import ch.snepilatch.app.BuildConfig
import ch.snepilatch.app.R
import ch.snepilatch.app.ui.components.ProfileInfoItem
import ch.snepilatch.app.ui.components.TightAlertDialog
import ch.snepilatch.app.ui.components.UpdateDialog
import ch.snepilatch.app.ui.theme.*
import ch.snepilatch.app.util.UpdateInfo
import ch.snepilatch.app.util.UpdateService
import ch.snepilatch.app.util.clearCookies
import ch.snepilatch.app.viewmodel.SpotifyViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AccountScreen(vm: SpotifyViewModel) {
    val account by vm.account.collectAsState()
    val theme by vm.themeColors.collectAsState()
    val animatedPrimary by animateColorAsState(theme.primary, tween(800), label = "accPrimary")

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = LocalBottomOverlayHeight.current.value + 16.dp)
    ) {
        // Header
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Profile image
            Box(
                Modifier
                    .size(120.dp)
                    .clip(CircleShape)
                    .background(SpotifyGray),
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
                    Icon(Icons.Rounded.Person, null, tint = SpotifyLightGray, modifier = Modifier.size(64.dp))
                }
            }

            Spacer(Modifier.height(16.dp))

            Text(
                account.displayName.ifEmpty { account.username.ifEmpty { stringResource(R.string.loading_dots) } },
                color = SpotifyWhite,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.followers_playlists, account.followers, account.playlistCount),
                color = SpotifyLightGray,
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

        AccountSectionHeader(stringResource(R.string.account_section_profile))

        val dots = stringResource(R.string.placeholder_dots)
        val premiumLabel = stringResource(R.string.premium)
        val freeLabel = stringResource(R.string.plan_free)
        ProfileInfoItem(
            stringResource(R.string.username),
            account.displayName.ifEmpty { account.username.ifEmpty { dots } },
            Icons.Rounded.Person
        )
        ProfileInfoItem(
            stringResource(R.string.user_id),
            account.username.ifEmpty { dots },
            Icons.Rounded.Badge
        )
        ProfileInfoItem(
            stringResource(R.string.plan),
            if (account.isPremium) premiumLabel else freeLabel,
            Icons.Rounded.CreditCard
        )

        Spacer(Modifier.height(24.dp))
        AccountSectionHeader(stringResource(R.string.account_section_appearance))

        val audioContext = androidx.compose.ui.platform.LocalContext.current

        // Language picker
        val appLanguage by vm.appLanguage.collectAsState()
        var showLanguagePicker by remember { mutableStateOf(false) }
        val systemDefaultLabel = stringResource(R.string.language_system_default)
        val languages = listOf(
            "system" to systemDefaultLabel,
            "en" to "English",
            "de" to "Deutsch",
            "ru" to "Русский",
            "gsw" to "Schwiizerdütsch"
        )
        val currentLanguageLabel = languages.find { it.first == appLanguage }?.second ?: systemDefaultLabel
        ListItem(
            headlineContent = { Text(stringResource(R.string.language), color = SpotifyWhite) },
            supportingContent = { Text(currentLanguageLabel, color = SpotifyLightGray) },
            leadingContent = { Icon(Icons.Rounded.Language, null, tint = SpotifyLightGray) },
            trailingContent = { Icon(Icons.Rounded.ChevronRight, null, tint = SpotifyLightGray) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable { showLanguagePicker = true }
        )
        if (showLanguagePicker) {
            TightAlertDialog(
                onDismissRequest = { showLanguagePicker = false },
                title = { Text(stringResource(R.string.language), color = SpotifyWhite) },
                text = {
                    Column {
                        languages.forEach { (code, label) ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    vm.setAppLanguage(code, audioContext)
                                    showLanguagePicker = false
                                }.padding(vertical = 12.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = appLanguage == code, onClick = {
                                    vm.setAppLanguage(code, audioContext)
                                    showLanguagePicker = false
                                }, colors = RadioButtonDefaults.colors(selectedColor = animatedPrimary, unselectedColor = SpotifyLightGray))
                                Spacer(Modifier.width(8.dp))
                                Text(label, color = SpotifyWhite, fontSize = 15.sp)
                            }
                        }
                    }
                },
                containerColor = SpotifyGray, confirmButton = {},
                dismissButton = { TextButton(onClick = { showLanguagePicker = false }) { Text(stringResource(R.string.cancel), color = SpotifyLightGray) } }
            )
        }

        // Lyrics animation direction (Appearance)
        val lyricsAnim by vm.lyricsAnimDirection.collectAsState()
        var showLyricsPicker by remember { mutableStateOf(false) }
        val lyricsLabel = if (lyricsAnim == "horizontal") stringResource(R.string.lyrics_horizontal) else stringResource(R.string.lyrics_vertical)
        ListItem(
            headlineContent = { Text(stringResource(R.string.lyrics_animation), color = SpotifyWhite) },
            supportingContent = { Text(lyricsLabel, color = SpotifyLightGray) },
            leadingContent = { Icon(Icons.Rounded.MusicNote, null, tint = SpotifyLightGray) },
            trailingContent = { Icon(Icons.Rounded.ChevronRight, null, tint = SpotifyLightGray) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable { showLyricsPicker = true }
        )
        if (showLyricsPicker) {
            TightAlertDialog(
                onDismissRequest = { showLyricsPicker = false },
                title = { Text(stringResource(R.string.lyrics_animation), color = SpotifyWhite) },
                text = {
                    Column {
                        Text(stringResource(R.string.lyrics_anim_desc),
                            color = SpotifyLightGray, fontSize = 13.sp)
                        Spacer(Modifier.height(12.dp))
                        listOf(
                            "vertical" to stringResource(R.string.lyrics_vertical),
                            "horizontal" to stringResource(R.string.lyrics_horizontal)
                        ).forEach { (value, label) ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        vm.setLyricsAnimDirection(value, audioContext)
                                        showLyricsPicker = false
                                    }
                                    .padding(vertical = 12.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = lyricsAnim == value,
                                    onClick = {
                                        vm.setLyricsAnimDirection(value, audioContext)
                                        showLyricsPicker = false
                                    },
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = animatedPrimary,
                                        unselectedColor = SpotifyLightGray
                                    )
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(label, color = SpotifyWhite, fontSize = 15.sp)
                            }
                        }
                    }
                },
                containerColor = SpotifyGray,
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { showLyricsPicker = false }) {
                        Text(stringResource(R.string.cancel), color = SpotifyLightGray)
                    }
                }
            )
        }

        Spacer(Modifier.height(24.dp))
        AccountSectionHeader(stringResource(R.string.account_section_playback))

        // Audio settings
        val audioSource by vm.preferredAudioSource.collectAsState()
        val isLossless = audioSource != null
        val losslessSubtitle = if (isLossless) {
            stringResource(R.string.lossless_on_flac)
        } else {
            stringResource(R.string.lossless_off_spotify)
        }

        // Lossless toggle — when on, the resolver picks the best source
        // (Qobuz, then Deezer) autonomously; no provider choice.
        ListItem(
            headlineContent = { Text(stringResource(R.string.lossless_audio), color = SpotifyWhite) },
            supportingContent = { Text(losslessSubtitle, color = SpotifyLightGray) },
            leadingContent = { Icon(Icons.Rounded.MusicNote, null, tint = SpotifyLightGray) },
            trailingContent = {
                Switch(
                    checked = isLossless,
                    onCheckedChange = { enabled ->
                        vm.setPreferredAudioSource(if (enabled) "lossless" else null, audioContext)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = animatedPrimary,
                        checkedTrackColor = animatedPrimary.copy(alpha = 0.5f),
                        uncheckedThumbColor = SpotifyLightGray,
                        uncheckedTrackColor = SpotifyLightGray.copy(alpha = 0.3f)
                    )
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )

        // Canvas background
        val canvasOn by vm.canvasEnabled.collectAsState()
        ListItem(
            headlineContent = { Text(stringResource(R.string.canvas_background), color = SpotifyWhite) },
            supportingContent = { Text(
                if (canvasOn) stringResource(R.string.canvas_on) else stringResource(R.string.canvas_off),
                color = SpotifyLightGray
            ) },
            leadingContent = { Icon(Icons.Rounded.PlayCircle, null, tint = SpotifyLightGray) },
            trailingContent = {
                Switch(
                    checked = canvasOn,
                    onCheckedChange = { vm.setCanvasEnabled(it, audioContext) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = animatedPrimary,
                        checkedTrackColor = animatedPrimary.copy(alpha = 0.5f),
                        uncheckedThumbColor = SpotifyLightGray,
                        uncheckedTrackColor = SpotifyLightGray.copy(alpha = 0.3f)
                    )
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )

        // Player background style: album-colour gradient vs. the fluid Kawarp album-art warp.
        val gradientBg by vm.playerGradientBg.collectAsState()
        ListItem(
            headlineContent = { Text("Gradient background", color = SpotifyWhite) },
            supportingContent = {
                Text(
                    if (gradientBg) "Album colour gradient" else "Flowing album art",
                    color = SpotifyLightGray
                )
            },
            leadingContent = { Icon(Icons.Rounded.Gradient, null, tint = SpotifyLightGray) },
            trailingContent = {
                Switch(
                    checked = gradientBg,
                    onCheckedChange = { vm.setPlayerGradientBg(it, audioContext) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = animatedPrimary,
                        checkedTrackColor = animatedPrimary.copy(alpha = 0.5f),
                        uncheckedThumbColor = SpotifyLightGray,
                        uncheckedTrackColor = SpotifyLightGray.copy(alpha = 0.3f)
                    )
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )

        // Content region picker
        val currentRegion by vm.contentRegion.collectAsState()
        var showRegionPicker by remember { mutableStateOf(false) }
        val regionLabel = if (currentRegion == "nearest") {
            stringResource(R.string.region_nearest)
        } else {
            currentRegion
        }
        ListItem(
            headlineContent = { Text(stringResource(R.string.content_region), color = SpotifyWhite) },
            supportingContent = { Text(regionLabel, color = SpotifyLightGray) },
            leadingContent = { Icon(Icons.Rounded.Language, null, tint = SpotifyLightGray) },
            trailingContent = { Icon(Icons.Rounded.ChevronRight, null, tint = SpotifyLightGray) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable { showRegionPicker = true }
        )
        if (showRegionPicker) {
            TightAlertDialog(
                onDismissRequest = { showRegionPicker = false },
                title = { Text(stringResource(R.string.content_region), color = SpotifyWhite) },
                text = {
                    Column {
                        listOf(
                            "nearest" to stringResource(R.string.region_nearest),
                            "US" to stringResource(R.string.region_us),
                            "GB" to stringResource(R.string.region_gb),
                            "DE" to stringResource(R.string.region_de),
                            "CH" to stringResource(R.string.region_ch),
                            "FR" to stringResource(R.string.region_fr),
                            "JP" to stringResource(R.string.region_jp),
                            "KR" to stringResource(R.string.region_kr),
                            "AU" to stringResource(R.string.region_au),
                            "BR" to stringResource(R.string.region_br),
                            "CA" to stringResource(R.string.region_ca),
                            "SE" to stringResource(R.string.region_se)
                        ).forEach { (code, label) ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        vm.setContentRegion(code, audioContext)
                                        showRegionPicker = false
                                    }
                                    .padding(vertical = 12.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = currentRegion == code,
                                    onClick = {
                                        vm.setContentRegion(code, audioContext)
                                        showRegionPicker = false
                                    },
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = animatedPrimary,
                                        unselectedColor = SpotifyLightGray
                                    )
                                )
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(label, color = SpotifyWhite, fontSize = 15.sp)
                                    Text(code, color = SpotifyLightGray, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                },
                containerColor = SpotifyGray,
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { showRegionPicker = false }) {
                        Text(stringResource(R.string.cancel), color = SpotifyLightGray)
                    }
                }
            )
        }

        // Connect to device (Playback)
        ListItem(
            headlineContent = { Text(stringResource(R.string.connect_to_device), color = SpotifyWhite) },
            leadingContent = { Icon(Icons.Rounded.Devices, null, tint = SpotifyLightGray) },
            trailingContent = { Icon(Icons.Rounded.ChevronRight, null, tint = SpotifyLightGray) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable { vm.loadDevices(); vm.showDevices.value = true }
        )

        Spacer(Modifier.height(24.dp))
        AccountSectionHeader(stringResource(R.string.account_section_notifications))

        // Notification button options
        val notifLikeLabel = stringResource(R.string.notif_like)
        val notifShuffleLabel = stringResource(R.string.notif_shuffle)
        val notifRepeatLabel = stringResource(R.string.notif_repeat)
        val buttonOptions = listOf(
            "like" to notifLikeLabel to stringResource(R.string.notif_like_short_desc),
            "shuffle" to notifShuffleLabel to stringResource(R.string.notif_shuffle_desc),
            "repeat" to notifRepeatLabel to stringResource(R.string.notif_repeat_desc)
        )
        fun buttonLabel(type: String) = when (type) {
            "like" -> notifLikeLabel
            "shuffle" -> notifShuffleLabel
            "repeat" -> notifRepeatLabel
            else -> type
        }

        // Left notification button
        val leftButton by vm.notificationLeftButton.collectAsState()
        var showLeftPicker by remember { mutableStateOf(false) }
        ListItem(
            headlineContent = { Text(stringResource(R.string.notification_left_button), color = SpotifyWhite) },
            supportingContent = { Text(buttonLabel(leftButton), color = SpotifyLightGray) },
            leadingContent = { Icon(Icons.Rounded.Notifications, null, tint = SpotifyLightGray) },
            trailingContent = { Icon(Icons.Rounded.ChevronRight, null, tint = SpotifyLightGray) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable { showLeftPicker = true }
        )
        if (showLeftPicker) {
            TightAlertDialog(
                onDismissRequest = { showLeftPicker = false },
                title = { Text(stringResource(R.string.notification_button_left), color = SpotifyWhite) },
                text = {
                    Column {
                        buttonOptions.forEach { (pair, desc) ->
                            val (value, label) = pair
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    vm.setNotificationLeftButton(value, audioContext)
                                    showLeftPicker = false
                                }.padding(vertical = 12.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = leftButton == value, onClick = {
                                    vm.setNotificationLeftButton(value, audioContext)
                                    showLeftPicker = false
                                }, colors = RadioButtonDefaults.colors(selectedColor = animatedPrimary, unselectedColor = SpotifyLightGray))
                                Spacer(Modifier.width(8.dp))
                                Column { Text(label, color = SpotifyWhite, fontSize = 15.sp); Text(desc, color = SpotifyLightGray, fontSize = 12.sp) }
                            }
                        }
                    }
                },
                containerColor = SpotifyGray, confirmButton = {},
                dismissButton = { TextButton(onClick = { showLeftPicker = false }) { Text(stringResource(R.string.cancel), color = SpotifyLightGray) } }
            )
        }

        // Right notification button
        val rightButton by vm.notificationRightButton.collectAsState()
        var showRightPicker by remember { mutableStateOf(false) }
        ListItem(
            headlineContent = { Text(stringResource(R.string.notification_right_button), color = SpotifyWhite) },
            supportingContent = { Text(buttonLabel(rightButton), color = SpotifyLightGray) },
            leadingContent = { Icon(Icons.Rounded.Notifications, null, tint = SpotifyLightGray) },
            trailingContent = { Icon(Icons.Rounded.ChevronRight, null, tint = SpotifyLightGray) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable { showRightPicker = true }
        )
        if (showRightPicker) {
            TightAlertDialog(
                onDismissRequest = { showRightPicker = false },
                title = { Text(stringResource(R.string.notification_button_right), color = SpotifyWhite) },
                text = {
                    Column {
                        buttonOptions.forEach { (pair, desc) ->
                            val (value, label) = pair
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    vm.setNotificationRightButton(value, audioContext)
                                    showRightPicker = false
                                }.padding(vertical = 12.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = rightButton == value, onClick = {
                                    vm.setNotificationRightButton(value, audioContext)
                                    showRightPicker = false
                                }, colors = RadioButtonDefaults.colors(selectedColor = animatedPrimary, unselectedColor = SpotifyLightGray))
                                Spacer(Modifier.width(8.dp))
                                Column { Text(label, color = SpotifyWhite, fontSize = 15.sp); Text(desc, color = SpotifyLightGray, fontSize = 12.sp) }
                            }
                        }
                    }
                },
                containerColor = SpotifyGray, confirmButton = {},
                dismissButton = { TextButton(onClick = { showRightPicker = false }) { Text(stringResource(R.string.cancel), color = SpotifyLightGray) } }
            )
        }

        Spacer(Modifier.height(24.dp))
        AccountSectionHeader(stringResource(R.string.about))

        ListItem(
            headlineContent = { Text(stringResource(R.string.app_version), color = SpotifyWhite) },
            supportingContent = { Text(BuildConfig.VERSION_NAME, color = SpotifyLightGray) },
            leadingContent = { Icon(Icons.Rounded.Info, null, tint = SpotifyLightGray) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )

        // Check for Updates
        val scope = rememberCoroutineScope()
        val updateContext = androidx.compose.ui.platform.LocalContext.current
        var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
        var isChecking by remember { mutableStateOf(false) }
        var upToDate by remember { mutableStateOf(false) }

        ListItem(
            headlineContent = { Text(stringResource(R.string.check_for_updates), color = SpotifyWhite) },
            supportingContent = { Text(
                when {
                    isChecking -> stringResource(R.string.checking)
                    upToDate -> stringResource(R.string.up_to_date)
                    else -> stringResource(R.string.tap_to_check)
                },
                color = if (upToDate) animatedPrimary else SpotifyLightGray
            ) },
            leadingContent = {
                if (isChecking) {
                    LoadingIndicator(
                        modifier = Modifier.size(24.dp),
                        color = animatedPrimary
                    )
                } else {
                    Icon(Icons.Rounded.SystemUpdate, null, tint = SpotifyLightGray)
                }
            },
            trailingContent = { if (!isChecking) Icon(Icons.Rounded.ChevronRight, null, tint = SpotifyLightGray) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable(enabled = !isChecking) {
                isChecking = true
                upToDate = false
                scope.launch {
                    val info = withContext(Dispatchers.IO) {
                        UpdateService.checkForUpdates(updateContext)
                    }
                    isChecking = false
                    if (info != null) {
                        updateInfo = info
                    } else {
                        upToDate = true
                    }
                }
            }
        )

        if (updateInfo != null) {
            UpdateDialog(
                updateInfo = updateInfo!!,
                onDismiss = { updateInfo = null }
            )
        }

        // Release Notes
        var showReleaseNotes by remember { mutableStateOf(false) }

        ListItem(
            headlineContent = { Text(stringResource(R.string.release_notes), color = SpotifyWhite) },
            supportingContent = { Text(stringResource(R.string.view_changelog), color = SpotifyLightGray) },
            leadingContent = { Icon(Icons.Rounded.Description, null, tint = SpotifyLightGray) },
            trailingContent = { Icon(Icons.Rounded.ChevronRight, null, tint = SpotifyLightGray) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable { showReleaseNotes = true }
        )

        if (showReleaseNotes) {
            ReleaseNotesDialog(onDismiss = { showReleaseNotes = false })
        }

        Spacer(Modifier.height(24.dp))
        AccountSectionHeader(stringResource(R.string.special_thanks))

        ListItem(
            headlineContent = { Text("Cinnabar 🧼", color = SpotifyWhite) },
            leadingContent = {
                AsyncImage(
                    model = "https://cdn.discordapp.com/avatars/823656705350565898/0167b0e2080d52dfa1f0a964a17828bb.webp?size=1024",
                    contentDescription = "Cinnabar",
                    modifier = Modifier.size(40.dp).clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )

        ListItem(
            headlineContent = { Text("MyDrift", color = SpotifyWhite) },
            leadingContent = {
                AsyncImage(
                    model = "https://cdn.discordapp.com/avatars/679006161554505729/2a9c7c72d662df626e9e740cf427c15e.webp?size=1024",
                    contentDescription = "MyDrift",
                    modifier = Modifier.size(40.dp).clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )

        Spacer(Modifier.height(16.dp))

        val context = androidx.compose.ui.platform.LocalContext.current
        ListItem(
            headlineContent = { Text(stringResource(R.string.log_out), color = Color(0xFFE57373)) },
            leadingContent = { Icon(Icons.AutoMirrored.Rounded.ExitToApp, null, tint = Color(0xFFE57373)) },
            trailingContent = { Icon(Icons.Rounded.ChevronRight, null, tint = SpotifyLightGray) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable {
                clearCookies(context)
                vm.showLogin()
            }
        )
    }
}

@Composable
private fun AccountSectionHeader(title: String) {
    HorizontalDivider(color = SpotifyGray, modifier = Modifier.padding(horizontal = 16.dp))
    Text(
        title,
        color = SpotifyWhite,
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp)
    )
}
