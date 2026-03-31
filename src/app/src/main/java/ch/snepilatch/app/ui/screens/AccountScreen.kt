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
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import android.content.Intent
import android.net.Uri
import ch.snepilatch.app.BuildConfig
import ch.snepilatch.app.ui.components.ProfileInfoItem
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
                        contentDescription = "Profile",
                        modifier = Modifier.size(120.dp).clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(Icons.Default.Person, null, tint = SpotifyLightGray, modifier = Modifier.size(64.dp))
                }
            }

            Spacer(Modifier.height(16.dp))

            Text(
                account.displayName.ifEmpty { account.username.ifEmpty { "Loading..." } },
                color = SpotifyWhite,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(6.dp))
            Text(
                "${account.followers} Followers · ${account.playlistCount} Playlists",
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
                        Icon(Icons.Default.Star, null, tint = animatedPrimary, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Premium", color = animatedPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        HorizontalDivider(color = SpotifyGray, modifier = Modifier.padding(horizontal = 16.dp))

        // Account details
        Text(
            "Account",
            color = SpotifyWhite,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp)
        )

        ProfileInfoItem("Username", account.displayName.ifEmpty { account.username.ifEmpty { "..." } }, Icons.Default.Person)
        ProfileInfoItem("User ID", account.username.ifEmpty { "..." }, Icons.Default.Badge)
        ProfileInfoItem("Plan", if (account.isPremium) "Premium" else "Free", Icons.Default.CreditCard)

        Spacer(Modifier.height(24.dp))

        HorizontalDivider(color = SpotifyGray, modifier = Modifier.padding(horizontal = 16.dp))

        Text(
            "Settings",
            color = SpotifyWhite,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp)
        )

        // Audio settings
        val audioSource by vm.preferredAudioSource.collectAsState()
        val audioContext = androidx.compose.ui.platform.LocalContext.current
        val isLossless = audioSource == "tidal" || audioSource == "qobuz"

        // Lossless toggle
        ListItem(
            headlineContent = { Text("Lossless Audio", color = SpotifyWhite) },
            supportingContent = { Text(
                if (isLossless) "FLAC via ${if (audioSource == "tidal") "Tidal" else "Qobuz"}"
                else "Standard quality (Spotify)",
                color = SpotifyLightGray
            ) },
            leadingContent = { Icon(Icons.Default.MusicNote, null, tint = SpotifyLightGray) },
            trailingContent = {
                Switch(
                    checked = isLossless,
                    onCheckedChange = { enabled ->
                        vm.setPreferredAudioSource(
                            if (enabled) "tidal" else null,
                            audioContext
                        )
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

        // Lossless provider picker (only when lossless enabled)
        if (isLossless) {
            var showProviderPicker by remember { mutableStateOf(false) }
            ListItem(
                headlineContent = { Text("Lossless Provider", color = SpotifyWhite) },
                supportingContent = { Text(
                    if (audioSource == "tidal") "Tidal — FLAC up to 1411kbps"
                    else "Qobuz — FLAC up to 9216kbps",
                    color = SpotifyLightGray
                ) },
                leadingContent = { Spacer(Modifier.width(24.dp)) },
                trailingContent = { Icon(Icons.Default.ChevronRight, null, tint = SpotifyLightGray) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable { showProviderPicker = true }
            )
            if (showProviderPicker) {
                AlertDialog(
                    onDismissRequest = { showProviderPicker = false },
                    title = { Text("Lossless Provider", color = SpotifyWhite) },
                    text = {
                        Column {
                            listOf(
                                "tidal" to "Tidal" to "FLAC up to 1411kbps",
                                "qobuz" to "Qobuz" to "FLAC up to 9216kbps"
                            ).forEach { (pair, desc) ->
                                val (value, label) = pair
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            vm.setPreferredAudioSource(value, audioContext)
                                            showProviderPicker = false
                                        }
                                        .padding(vertical = 12.dp, horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = audioSource == value,
                                        onClick = {
                                            vm.setPreferredAudioSource(value, audioContext)
                                            showProviderPicker = false
                                        },
                                        colors = RadioButtonDefaults.colors(
                                            selectedColor = animatedPrimary,
                                            unselectedColor = SpotifyLightGray
                                        )
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Column {
                                        Text(label, color = SpotifyWhite, fontSize = 15.sp)
                                        Text(desc, color = SpotifyLightGray, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    },
                    containerColor = SpotifyGray,
                    confirmButton = {},
                    dismissButton = {
                        TextButton(onClick = { showProviderPicker = false }) {
                            Text("Cancel", color = SpotifyLightGray)
                        }
                    }
                )
            }
        }

        // Canvas background
        val canvasOn by vm.canvasEnabled.collectAsState()
        ListItem(
            headlineContent = { Text("Canvas Background", color = SpotifyWhite) },
            supportingContent = { Text(
                if (canvasOn) "Looping video behind album art" else "Off",
                color = SpotifyLightGray
            ) },
            leadingContent = { Icon(Icons.Default.PlayCircle, null, tint = SpotifyLightGray) },
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

        // Lyrics animation direction
        val lyricsAnim by vm.lyricsAnimDirection.collectAsState()
        var showLyricsPicker by remember { mutableStateOf(false) }
        val lyricsLabel = if (lyricsAnim == "horizontal") "Left to Right" else "Top to Bottom"
        ListItem(
            headlineContent = { Text("Lyrics Animation", color = SpotifyWhite) },
            supportingContent = { Text(lyricsLabel, color = SpotifyLightGray) },
            leadingContent = { Icon(Icons.Default.MusicNote, null, tint = SpotifyLightGray) },
            trailingContent = { Icon(Icons.Default.ChevronRight, null, tint = SpotifyLightGray) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable { showLyricsPicker = true }
        )
        if (showLyricsPicker) {
            AlertDialog(
                onDismissRequest = { showLyricsPicker = false },
                title = { Text("Lyrics Animation", color = SpotifyWhite) },
                text = {
                    Column {
                        Text("Choose how line-synced lyrics animate.",
                            color = SpotifyLightGray, fontSize = 13.sp)
                        Spacer(Modifier.height(12.dp))
                        listOf(
                            "vertical" to "Top to Bottom",
                            "horizontal" to "Left to Right"
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
                        Text("Cancel", color = SpotifyLightGray)
                    }
                }
            )
        }

        // Notification button options
        val buttonOptions = listOf(
            "like" to "Like / Unlike" to "Toggle liked state",
            "shuffle" to "Shuffle" to "Toggle shuffle mode",
            "repeat" to "Repeat" to "Cycle repeat (off → all → one)"
        )
        fun buttonLabel(type: String) = when (type) {
            "like" -> "Like / Unlike"
            "shuffle" -> "Shuffle"
            "repeat" -> "Repeat"
            else -> type
        }

        // Left notification button
        val leftButton by vm.notificationLeftButton.collectAsState()
        var showLeftPicker by remember { mutableStateOf(false) }
        ListItem(
            headlineContent = { Text("Notification Left Button", color = SpotifyWhite) },
            supportingContent = { Text(buttonLabel(leftButton), color = SpotifyLightGray) },
            leadingContent = { Icon(Icons.Default.Notifications, null, tint = SpotifyLightGray) },
            trailingContent = { Icon(Icons.Default.ChevronRight, null, tint = SpotifyLightGray) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable { showLeftPicker = true }
        )
        if (showLeftPicker) {
            AlertDialog(
                onDismissRequest = { showLeftPicker = false },
                title = { Text("Left Button", color = SpotifyWhite) },
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
                dismissButton = { TextButton(onClick = { showLeftPicker = false }) { Text("Cancel", color = SpotifyLightGray) } }
            )
        }

        // Right notification button
        val rightButton by vm.notificationRightButton.collectAsState()
        var showRightPicker by remember { mutableStateOf(false) }
        ListItem(
            headlineContent = { Text("Notification Right Button", color = SpotifyWhite) },
            supportingContent = { Text(buttonLabel(rightButton), color = SpotifyLightGray) },
            leadingContent = { Icon(Icons.Default.Notifications, null, tint = SpotifyLightGray) },
            trailingContent = { Icon(Icons.Default.ChevronRight, null, tint = SpotifyLightGray) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable { showRightPicker = true }
        )
        if (showRightPicker) {
            AlertDialog(
                onDismissRequest = { showRightPicker = false },
                title = { Text("Right Button", color = SpotifyWhite) },
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
                dismissButton = { TextButton(onClick = { showRightPicker = false }) { Text("Cancel", color = SpotifyLightGray) } }
            )
        }

        ListItem(
            headlineContent = { Text("Connect to a device", color = SpotifyWhite) },
            leadingContent = { Icon(Icons.Default.Devices, null, tint = SpotifyLightGray) },
            trailingContent = { Icon(Icons.Default.ChevronRight, null, tint = SpotifyLightGray) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable { vm.loadDevices(); vm.showDevices.value = true }
        )

        Spacer(Modifier.height(24.dp))

        HorizontalDivider(color = SpotifyGray, modifier = Modifier.padding(horizontal = 16.dp))

        // About section
        Text(
            "About",
            color = SpotifyWhite,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp)
        )

        ListItem(
            headlineContent = { Text("App Version", color = SpotifyWhite) },
            supportingContent = { Text(BuildConfig.VERSION_NAME, color = SpotifyLightGray) },
            leadingContent = { Icon(Icons.Default.Info, null, tint = SpotifyLightGray) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )

        // Check for Updates
        val scope = rememberCoroutineScope()
        val updateContext = androidx.compose.ui.platform.LocalContext.current
        var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
        var isChecking by remember { mutableStateOf(false) }
        var upToDate by remember { mutableStateOf(false) }

        ListItem(
            headlineContent = { Text("Check for Updates", color = SpotifyWhite) },
            supportingContent = { Text(
                when {
                    isChecking -> "Checking..."
                    upToDate -> "You're on the latest version"
                    else -> "Tap to check for new versions"
                },
                color = if (upToDate) animatedPrimary else SpotifyLightGray
            ) },
            leadingContent = {
                if (isChecking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = animatedPrimary
                    )
                } else {
                    Icon(Icons.Default.SystemUpdate, null, tint = SpotifyLightGray)
                }
            },
            trailingContent = { if (!isChecking) Icon(Icons.Default.ChevronRight, null, tint = SpotifyLightGray) },
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
            headlineContent = { Text("Release Notes", color = SpotifyWhite) },
            supportingContent = { Text("View changelog", color = SpotifyLightGray) },
            leadingContent = { Icon(Icons.Default.Description, null, tint = SpotifyLightGray) },
            trailingContent = { Icon(Icons.Default.ChevronRight, null, tint = SpotifyLightGray) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable { showReleaseNotes = true }
        )

        if (showReleaseNotes) {
            ReleaseNotesDialog(onDismiss = { showReleaseNotes = false })
        }

        Spacer(Modifier.height(24.dp))

        HorizontalDivider(color = SpotifyGray, modifier = Modifier.padding(horizontal = 16.dp))

        Text(
            "Special Thanks",
            color = SpotifyWhite,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp)
        )

        ListItem(
            headlineContent = { Text("Cinnabar \uD83E\uDDFC", color = SpotifyWhite) },
            leadingContent = {
                AsyncImage(
                    model = "https://cdn.discordapp.com/avatars/823656705350565898/ac6e931b67dfe978c899af4eea5ed051.webp?size=1024",
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
            headlineContent = { Text("Log out", color = Color(0xFFE57373)) },
            leadingContent = { Icon(Icons.AutoMirrored.Filled.ExitToApp, null, tint = Color(0xFFE57373)) },
            trailingContent = { Icon(Icons.Default.ChevronRight, null, tint = SpotifyLightGray) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable {
                clearCookies(context)
                vm.showLogin()
            }
        )
    }
}
