import 'package:flutter/foundation.dart';
import 'package:flutter_inappwebview/flutter_inappwebview.dart';
import 'package:flutter/services.dart';
import 'audio_websocket_server.dart';
import 'audio_playback_pcm_service.dart';
import 'audio_handler_service.dart';
import 'native_audio_service.dart';
import '../constants/javascript_injection.dart';
import '../main.dart';

class WebViewAudioStreamer {
  static WebViewAudioStreamer? _instance;
  AudioWebSocketServer? _audioServer;
  final int _serverPort = 8765;
  bool _isInitialized = false;
  bool _hasNotifiedPlaying = false;

  // Singleton instance
  static WebViewAudioStreamer get instance {
    _instance ??= WebViewAudioStreamer._();
    return _instance!;
  }

  WebViewAudioStreamer._();

  Future<void> initialize() async {
    if (_isInitialized) {
      debugPrint('🎧 Audio streaming already initialized');
      return;
    }

    try {
      // Initialize PCM audio streaming
      debugPrint('🎯 Initializing PCM audio streaming for direct playback');
      debugPrint('📊 This will capture raw 16-bit PCM audio at 44.1kHz');

      // Initialize native audio service for Wavelet detection
      await NativeAudioService.instance.initialize();
      debugPrint('🎵 Native AudioTrack initialized for Wavelet detection');

      // Start WebSocket server
      _audioServer = AudioWebSocketServer(
        port: _serverPort,
        onAudioData: (data) async {
          // Route audio data to playback service
          if (data.isNotEmpty) {
            debugPrint('🎵 Received audio data chunk: ${data.length} bytes');

            // Send to native AudioTrack for Wavelet detection
            try {
              await NativeAudioService.instance.playAudioData(Uint8List.fromList(data));
            } catch (e) {
              debugPrint('⚠️ Native audio playback error: $e');
              // Fall back to flutter_sound if native fails
              AudioPlaybackPCMService.instance.addAudioData(Uint8List.fromList(data));
            }

            // Notify audio handler that we're playing audio (for media controls)
            _notifyAudioPlaying();
          }
        },
      );
      await _audioServer!.start();

      _isInitialized = true;
      debugPrint('✅ Audio streaming initialized successfully');
    } catch (e) {
      debugPrint('❌ Error initializing audio capture: $e');
    }
  }

  void injectAudioScript(InAppWebViewController controller) async {
    if (!_isInitialized) {
      await initialize();
    }

    try {
      // Use the aggressive PCM interceptor that hooks early
      final scriptContent = await rootBundle.loadString('assets/js/spotify-audio-intercept-pcm.js');
      final script = scriptContent.replaceAll('{{PORT}}', _serverPort.toString());

      controller.evaluateJavascript(source: script);
      debugPrint('💉 Injected AGGRESSIVE PCM interceptor script');
      debugPrint('🔨 This will override Audio constructor and createElement');
      debugPrint('📊 Format: 16-bit PCM @ 44.1kHz Mono');
    } catch (e) {
      debugPrint('❌ Failed to load PCM interceptor: $e');
      // Try regular PCM script as fallback
      try {
        final scriptContent = await rootBundle.loadString('assets/js/spotify-audio-pcm.js');
        final script = scriptContent.replaceAll('{{PORT}}', _serverPort.toString());
        controller.evaluateJavascript(source: script);
        debugPrint('💉 Injected regular PCM script as fallback');
      } catch (e2) {
        // Last resort - use inline script
        final script = audioCapturScript.replaceAll('{{PORT}}', _serverPort.toString());
        controller.evaluateJavascript(source: script);
        debugPrint('💉 Injected inline fallback script');
      }
    }
  }

  void _notifyAudioPlaying() {
    // Only notify once per session to avoid spam
    if (!_hasNotifiedPlaying) {
      _hasNotifiedPlaying = true;
      try {
        // Cast to SpotifyAudioHandler and update playback state
        if (audioHandler is SpotifyAudioHandler) {
          final handler = audioHandler as SpotifyAudioHandler;
          handler.updatePlaybackState(
            isPlaying: true,
            position: Duration.zero,
          );

          // Also set a dummy media item if needed
          handler.setMediaItem(
            title: 'Spotify Audio Stream',
            artist: 'Snepilatch',
            album: 'Streaming',
          );

          debugPrint('🎧 Notified audio handler - app should be visible to Wavelet');
        }
      } catch (e) {
        debugPrint('⚠️ Could not notify audio handler: $e');
      }
    }
  }

  Future<void> dispose() async {
    await _audioServer?.stop();
    await NativeAudioService.instance.stop();
    _isInitialized = false;
    _hasNotifiedPlaying = false;
    debugPrint('🛑 Audio streaming disposed');
  }

  // Public getters for status monitoring
  bool get isInitialized => _isInitialized;
  bool get isConnected => _audioServer?.isConnected ?? false;
  String get status => _audioServer?.getStatus() ?? 'Not initialized';
  int get bytesReceived => _audioServer?.totalBytesReceived ?? 0;
  int get packetsReceived => _audioServer?.packetsReceived ?? 0;

  // Expose audio server for debugging
  AudioWebSocketServer? get audioServer => _audioServer;
}