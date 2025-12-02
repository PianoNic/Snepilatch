import 'package:flutter/services.dart';
import 'package:flutter/foundation.dart';

/// Native Android audio service that registers with AudioManager
/// This ensures Wavelet can detect and process our audio
class NativeAudioService {
  static const MethodChannel _channel = MethodChannel('com.example.snepilatch/audio');
  static NativeAudioService? _instance;
  bool _isInitialized = false;

  static NativeAudioService get instance {
    _instance ??= NativeAudioService._();
    return _instance!;
  }

  NativeAudioService._();

  /// Initialize the native audio service
  Future<void> initialize() async {
    if (_isInitialized) return;

    try {
      await _channel.invokeMethod('initializeAudio');
      _isInitialized = true;
      debugPrint('✅ Native audio service initialized - App visible to Wavelet');
    } catch (e) {
      debugPrint('❌ Failed to initialize native audio service: $e');
    }
  }

  /// Send PCM audio data to native Android AudioTrack
  Future<void> playAudioData(Uint8List audioData) async {
    if (!_isInitialized) {
      await initialize();
    }

    try {
      await _channel.invokeMethod('playAudio', audioData);
    } catch (e) {
      debugPrint('⚠️ Failed to play audio through native service: $e');
    }
  }

  /// Stop audio playback
  Future<void> stop() async {
    try {
      await _channel.invokeMethod('stopAudio');
      debugPrint('⏹️ Native audio stopped');
    } catch (e) {
      debugPrint('⚠️ Failed to stop native audio: $e');
    }
  }
}