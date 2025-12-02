import 'package:audio_service/audio_service.dart';
import 'package:flutter/foundation.dart';

/// Manages audio session for Wavelet detection
/// Separate from audio playback to avoid conflicts
class AudioSessionManager {
  static AudioSessionManager? _instance;
  static BaseAudioHandler? _handler;

  static AudioSessionManager get instance {
    _instance ??= AudioSessionManager._();
    return _instance!;
  }

  AudioSessionManager._();

  /// Initialize audio session to make app visible to Wavelet
  static Future<void> initializeForWavelet() async {
    try {
      debugPrint('🎧 Initializing audio session for Wavelet detection...');

      // Initialize audio service with minimal configuration
      _handler = await AudioService.init(
        builder: () => MinimalAudioHandler(),
        config: const AudioServiceConfig(
          androidNotificationChannelId: 'com.example.snepilatch.channel.audio',
          androidNotificationChannelName: 'Snepilatch Audio',
          androidNotificationOngoing: false,
          androidShowNotificationBadge: false,
          androidNotificationIcon: 'drawable/ic_notification',
          androidStopForegroundOnPause: true, // Allow stopping when paused
        ),
      );

      // Set a media item to register with the system
      final mediaItem = MediaItem(
        id: 'snepilatch_spotify',
        album: 'Spotify Stream',
        title: 'Audio Playing',
        artist: 'Snepilatch',
        duration: const Duration(hours: 24), // Long duration for continuous playback
        artUri: Uri.parse('https://open.spotify.com/favicon.ico'),
      );

      _handler!.queue.add([mediaItem]);
      _handler!.mediaItem.add(mediaItem);

      // Set playback state to playing
      _handler!.playbackState.add(PlaybackState(
        controls: [
          MediaControl.pause,
          MediaControl.stop,
        ],
        systemActions: const {
          MediaAction.play,
          MediaAction.pause,
          MediaAction.stop,
        },
        androidCompactActionIndices: const [0],
        processingState: AudioProcessingState.ready,
        playing: true,
        updatePosition: Duration.zero,
        bufferedPosition: Duration.zero,
        speed: 1.0,
        queueIndex: 0,
      ));

      debugPrint('✅ Audio session initialized - App should be visible to Wavelet');
      debugPrint('🎵 Check Wavelet for "Snepilatch" in the app list');

    } catch (e) {
      debugPrint('⚠️ Failed to initialize audio session for Wavelet: $e');
    }
  }

  /// Update playback state when audio is playing
  static void setPlaying(bool playing) {
    if (_handler != null) {
      _handler!.playbackState.add(_handler!.playbackState.value.copyWith(
        playing: playing,
      ));
    }
  }
}

/// Minimal audio handler that just maintains session
class MinimalAudioHandler extends BaseAudioHandler {
  @override
  Future<void> play() async {
    playbackState.add(playbackState.value.copyWith(
      playing: true,
      processingState: AudioProcessingState.ready,
    ));
  }

  @override
  Future<void> pause() async {
    playbackState.add(playbackState.value.copyWith(
      playing: false,
    ));
  }

  @override
  Future<void> stop() async {
    playbackState.add(playbackState.value.copyWith(
      playing: false,
      processingState: AudioProcessingState.idle,
    ));
  }
}