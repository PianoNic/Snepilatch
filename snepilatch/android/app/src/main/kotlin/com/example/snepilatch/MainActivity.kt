package com.example.snepilatch

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private val CHANNEL = "com.example.snepilatch/audio"
    private var audioTrack: AudioTrack? = null
    private var isPlaying = false

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "initializeAudio" -> {
                    initializeAudioTrack()
                    result.success(true)
                }
                "playAudio" -> {
                    val audioData = call.arguments as ByteArray
                    playAudioData(audioData)
                    result.success(true)
                }
                "stopAudio" -> {
                    stopAudio()
                    result.success(true)
                }
                else -> {
                    result.notImplemented()
                }
            }
        }
    }

    private fun initializeAudioTrack() {
        try {
            val sampleRate = 44100
            val channelConfig = AudioFormat.CHANNEL_OUT_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val bufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)

            // Create AudioTrack with MUSIC stream type (this is what Wavelet detects)
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(audioFormat)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            // Request audio focus to ensure we're registered with the system
            val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )

            android.util.Log.d("MainActivity", "AudioTrack initialized - Wavelet should detect this app")
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Failed to initialize AudioTrack: ${e.message}")
        }
    }

    private fun playAudioData(audioData: ByteArray) {
        audioTrack?.let { track ->
            if (track.state == AudioTrack.STATE_INITIALIZED) {
                if (!isPlaying) {
                    track.play()
                    isPlaying = true
                    android.util.Log.d("MainActivity", "AudioTrack playing - App visible to Wavelet")
                }
                track.write(audioData, 0, audioData.size)
            }
        }
    }

    private fun stopAudio() {
        audioTrack?.let { track ->
            if (isPlaying) {
                track.stop()
                isPlaying = false
            }
        }
    }

    override fun onDestroy() {
        audioTrack?.release()
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        audioManager.abandonAudioFocus(null)
        super.onDestroy()
    }
}