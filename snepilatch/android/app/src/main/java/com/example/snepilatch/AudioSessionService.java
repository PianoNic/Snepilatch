package com.example.snepilatch;

import android.app.Service;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

public class AudioSessionService extends Service {
    private static final String TAG = "AudioSessionService";
    private AudioTrack audioTrack;
    private boolean isPlaying = false;
    private final IBinder binder = new LocalBinder();

    public class LocalBinder extends Binder {
        AudioSessionService getService() {
            return AudioSessionService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        initializeAudioTrack();
    }

    private void initializeAudioTrack() {
        try {
            int sampleRate = 44100;
            int channelConfig = AudioFormat.CHANNEL_OUT_MONO;
            int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
            int bufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat);

            // Create AudioTrack with MUSIC stream type (this is what Wavelet detects)
            audioTrack = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(audioFormat)
                            .setSampleRate(sampleRate)
                            .setChannelMask(channelConfig)
                            .build())
                    .setBufferSizeInBytes(bufferSize * 2)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();

            // Request audio focus to ensure we're registered with the system
            AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
            audioManager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN
            );

            Log.d(TAG, "AudioTrack initialized successfully - Wavelet should detect this app");
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize AudioTrack: " + e.getMessage());
        }
    }

    public void playAudioData(byte[] audioData) {
        if (audioTrack != null && audioTrack.getState() == AudioTrack.STATE_INITIALIZED) {
            if (!isPlaying) {
                audioTrack.play();
                isPlaying = true;
                Log.d(TAG, "AudioTrack started playing - App visible to Wavelet");
            }
            audioTrack.write(audioData, 0, audioData.length);
        }
    }

    public void stopAudio() {
        if (audioTrack != null && isPlaying) {
            audioTrack.stop();
            isPlaying = false;
        }
    }

    @Override
    public void onDestroy() {
        if (audioTrack != null) {
            audioTrack.release();
        }

        // Release audio focus
        AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        audioManager.abandonAudioFocus(null);

        super.onDestroy();
    }
}