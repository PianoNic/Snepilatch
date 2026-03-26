package ch.snepilatch.app.ui.components

import ch.snepilatch.app.ui.theme.SpotifyWhite
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.snepilatch.app.ui.theme.SpotifyElevated
import ch.snepilatch.app.ui.theme.SpotifyGray
import ch.snepilatch.app.ui.theme.SpotifyLightGray
import ch.snepilatch.app.viewmodel.SpotifyViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesDialog(vm: SpotifyViewModel) {
    val devices by vm.devices.collectAsState()
    val playback by vm.playback.collectAsState()
    val theme by vm.themeColors.collectAsState()
    val accentColor by androidx.compose.animation.animateColorAsState(theme.primary, androidx.compose.animation.core.tween(800), label = "devAccent")
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val ourId = vm.ourDeviceId

    // Sort: active device first
    val sortedDevices = devices.sortedByDescending { it.is_active }
    val activeDevice = sortedDevices.firstOrNull { it.is_active }
    val otherDevices = sortedDevices.filter { !it.is_active }

    ModalBottomSheet(
        onDismissRequest = { vm.showDevices.value = false },
        sheetState = sheetState,
        containerColor = SpotifyElevated,
        dragHandle = {
            Box(
                Modifier
                    .padding(vertical = 12.dp)
                    .width(40.dp)
                    .height(4.dp)
                    .background(SpotifyLightGray.copy(alpha = 0.4f), RoundedCornerShape(2.dp))
            )
        }
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
        ) {
            // Title
            Text(
                "Connect",
                color = SpotifyWhite,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )

            if (devices.isEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(color = SpotifyWhite, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Searching for devices...", color = SpotifyLightGray, fontSize = 14.sp)
                }
            }

            // Active device section
            if (activeDevice != null) {
                val isOurDevice = ourId != null && (activeDevice.id == ourId || activeDevice.id == "hobs_$ourId" || "hobs_${activeDevice.id}" == ourId)

                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        when (activeDevice.type.lowercase()) {
                            "smartphone" -> Icons.Default.Smartphone
                            "speaker" -> Icons.Default.Speaker
                            "tv" -> Icons.Default.Tv
                            else -> Icons.Default.Computer
                        },
                        null,
                        tint = accentColor,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (isOurDevice) "This Smartphone" else activeDevice.name,
                                color = accentColor,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Volume slider — only show for remote devices, not this phone
                if (!isOurDevice) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.AutoMirrored.Filled.VolumeDown, null, tint = SpotifyLightGray, modifier = Modifier.size(20.dp))
                        var volumeValue by remember { mutableFloatStateOf(playback.volume.toFloat()) }
                        Slider(
                            value = volumeValue,
                            onValueChange = { volumeValue = it },
                            onValueChangeFinished = { vm.setSpotifyVolume(volumeValue.toDouble()) },
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                            colors = SliderDefaults.colors(
                                thumbColor = accentColor,
                                activeTrackColor = accentColor,
                                inactiveTrackColor = SpotifyGray
                            )
                        )
                        Icon(Icons.AutoMirrored.Filled.VolumeUp, null, tint = SpotifyLightGray, modifier = Modifier.size(20.dp))
                    }
                }

                Spacer(Modifier.height(8.dp))
            }

            // Other devices
            otherDevices.forEach { device ->
                val isOurDevice = ourId != null && (device.id == ourId || device.id == "hobs_$ourId" || "hobs_${device.id}" == ourId)

                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { vm.transferPlayback(device.id) }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        when (device.type.lowercase()) {
                            "smartphone" -> Icons.Default.Smartphone
                            "speaker" -> Icons.Default.Speaker
                            "tv" -> Icons.Default.Tv
                            else -> Icons.Default.Computer
                        },
                        null,
                        tint = SpotifyLightGray,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(
                        if (isOurDevice) "This Smartphone" else device.name,
                        color = SpotifyWhite,
                        fontSize = 16.sp,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(Modifier.navigationBarsPadding().height(12.dp))
        }
    }
}
