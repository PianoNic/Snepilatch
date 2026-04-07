package ch.snepilatch.app.util

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

/**
 * Snapshot of the currently-active audio output route, resolved via AudioManager.
 */
data class AudioOutput(
    /** Human-readable name, e.g. "Bluetooth", "USB Audio", or the device's own productName. */
    val name: String,
    /** Coarse category — one of "bluetooth", "usb", "wired", "speaker". */
    val type: String
) {
    companion object {
        val SPEAKER = AudioOutput(name = "Speaker", type = "speaker")
    }
}

/**
 * Query AudioManager for the currently-active output route, preferring Bluetooth >
 * USB > Wired > Speaker when multiple outputs are connected.
 */
fun detectActiveAudioOutput(context: Context): AudioOutput {
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

    val active = devices.firstOrNull {
        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
        it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
        it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER
    } ?: devices.firstOrNull {
        it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
        it.type == AudioDeviceInfo.TYPE_USB_DEVICE
    } ?: devices.firstOrNull {
        it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
        it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
    } ?: return AudioOutput.SPEAKER

    val name = active.productName?.toString()?.takeIf { it.isNotBlank() && it != "null" }
        ?: when (active.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER -> "Bluetooth"
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB Audio"
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired"
            else -> "External"
        }
    val type = when (active.type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> "bluetooth"
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE -> "usb"
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired"
        else -> "speaker"
    }
    return AudioOutput(name = name, type = type)
}
