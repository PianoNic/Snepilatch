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
import ch.snepilatch.app.viewmodel.ThemeController
import ch.snepilatch.app.viewmodel.AppSettings
import ch.snepilatch.app.viewmodel.PlaybackViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AccountScreen(vm: PlaybackViewModel) {
    val account by vm.account.collectAsState()
    val theme by ThemeController.themeColors.collectAsState()
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
        val appLanguage by AppSettings.appLanguage.collectAsState()
        var showLanguagePicker by remember { mutableStateOf(false) }
        val systemDefaultLabel = stringResource(R.string.language_system_default)
        val languages = remember(systemDefaultLabel) {
            listOf(
                "system" to systemDefaultLabel,
                "en" to "English",
                "de" to "Deutsch",
                "ru" to "Русский",
                "gsw" to "Schwiizerdütsch"
            )
        }
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
            RadioPickerDialog(
                title = stringResource(R.string.language),
                options = languages.map { RadioOption(it.first, it.second) },
                selected = appLanguage,
                selectedColor = animatedPrimary,
                onSelect = {
                    AppSettings.setAppLanguage(it, audioContext)
                    showLanguagePicker = false
                },
                onDismiss = { showLanguagePicker = false }
            )
        }

        // Lyrics animation direction (Appearance)
        val lyricsAnim by AppSettings.lyricsAnimDirection.collectAsState()
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
            RadioPickerDialog(
                title = stringResource(R.string.lyrics_animation),
                description = stringResource(R.string.lyrics_anim_desc),
                options = listOf(
                    RadioOption("vertical", stringResource(R.string.lyrics_vertical)),
                    RadioOption("horizontal", stringResource(R.string.lyrics_horizontal))
                ),
                selected = lyricsAnim,
                selectedColor = animatedPrimary,
                onSelect = {
                    AppSettings.setLyricsAnimDirection(it, audioContext)
                    showLyricsPicker = false
                },
                onDismiss = { showLyricsPicker = false }
            )
        }

        Spacer(Modifier.height(24.dp))
        AccountSectionHeader(stringResource(R.string.account_section_playback))

        // Audio settings
        val audioSource by AppSettings.preferredAudioSource.collectAsState()
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
                        AppSettings.setPreferredAudioSource(if (enabled) "lossless" else null, audioContext)
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
        val canvasOn by AppSettings.canvasEnabled.collectAsState()
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
        val gradientBg by AppSettings.playerGradientBg.collectAsState()
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
                    onCheckedChange = { AppSettings.setPlayerGradientBg(it, audioContext) },
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
        val currentRegion by AppSettings.contentRegion.collectAsState()
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
            val regionOptions = listOf(
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
            )
            RadioPickerDialog(
                title = stringResource(R.string.content_region),
                options = regionOptions.map { RadioOption(it.first, it.second, it.first) },
                selected = currentRegion,
                selectedColor = animatedPrimary,
                onSelect = {
                    AppSettings.setContentRegion(it, audioContext)
                    showRegionPicker = false
                },
                onDismiss = { showRegionPicker = false }
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
        val notifLikeDesc = stringResource(R.string.notif_like_short_desc)
        val notifShuffleDesc = stringResource(R.string.notif_shuffle_desc)
        val notifRepeatDesc = stringResource(R.string.notif_repeat_desc)
        val buttonOptions = remember(
            notifLikeLabel, notifShuffleLabel, notifRepeatLabel,
            notifLikeDesc, notifShuffleDesc, notifRepeatDesc
        ) {
            listOf(
                "like" to notifLikeLabel to notifLikeDesc,
                "shuffle" to notifShuffleLabel to notifShuffleDesc,
                "repeat" to notifRepeatLabel to notifRepeatDesc
            )
        }
        fun buttonLabel(type: String) = when (type) {
            "like" -> notifLikeLabel
            "shuffle" -> notifShuffleLabel
            "repeat" -> notifRepeatLabel
            else -> type
        }
        val notifRadioOptions = buttonOptions.map { (pair, desc) ->
            RadioOption(pair.first, pair.second, desc)
        }

        // Left notification button
        val leftButton by AppSettings.notificationLeftButton.collectAsState()
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
            RadioPickerDialog(
                title = stringResource(R.string.notification_button_left),
                options = notifRadioOptions,
                selected = leftButton,
                selectedColor = animatedPrimary,
                onSelect = {
                    AppSettings.setNotificationLeftButton(it, audioContext)
                    showLeftPicker = false
                },
                onDismiss = { showLeftPicker = false }
            )
        }

        // Right notification button
        val rightButton by AppSettings.notificationRightButton.collectAsState()
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
            RadioPickerDialog(
                title = stringResource(R.string.notification_button_right),
                options = notifRadioOptions,
                selected = rightButton,
                selectedColor = animatedPrimary,
                onSelect = {
                    AppSettings.setNotificationRightButton(it, audioContext)
                    showRightPicker = false
                },
                onDismiss = { showRightPicker = false }
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

private data class RadioOption(val value: String, val label: String, val supportingText: String? = null)

/** Single radio-select settings dialog shared by the Language, Lyrics, Region and notification pickers. */
@Composable
private fun RadioPickerDialog(
    title: String,
    description: String? = null,
    options: List<RadioOption>,
    selected: String,
    selectedColor: Color,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    TightAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, color = SpotifyWhite) },
        text = {
            Column {
                if (description != null) {
                    Text(description, color = SpotifyLightGray, fontSize = 13.sp)
                    Spacer(Modifier.height(12.dp))
                }
                options.forEach { opt ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(opt.value) }
                            .padding(vertical = 12.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selected == opt.value,
                            onClick = { onSelect(opt.value) },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = selectedColor,
                                unselectedColor = SpotifyLightGray
                            )
                        )
                        Spacer(Modifier.width(8.dp))
                        if (opt.supportingText != null) {
                            Column {
                                Text(opt.label, color = SpotifyWhite, fontSize = 15.sp)
                                Text(opt.supportingText, color = SpotifyLightGray, fontSize = 12.sp)
                            }
                        } else {
                            Text(opt.label, color = SpotifyWhite, fontSize = 15.sp)
                        }
                    }
                }
            }
        },
        containerColor = SpotifyGray,
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel), color = SpotifyLightGray)
            }
        }
    )
}
