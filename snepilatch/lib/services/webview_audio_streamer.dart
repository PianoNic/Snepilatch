import 'package:flutter/foundation.dart';
import 'package:flutter_inappwebview/flutter_inappwebview.dart';
import 'package:flutter/services.dart';
import 'audio_websocket_server.dart';
import 'audio_playback_service.dart';
import '../constants/javascript_injection.dart';

class WebViewAudioStreamer {
  static WebViewAudioStreamer? _instance;
  AudioWebSocketServer? _audioServer;
  final int _serverPort = 8765;
  bool _isInitialized = false;

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
      // For now, skip audio handler initialization since main app already has one
      // Just initialize the WebSocket server
      debugPrint('⚠️ Skipping audio handler init - using WebSocket server only');

      // TODO: In the future, integrate with existing SpotifyAudioHandler
      // to route WebView audio through the main audio service

      // Start WebSocket server
      _audioServer = AudioWebSocketServer(
        port: _serverPort,
        onAudioData: (data) {
          // Route audio data to playback service
          if (data.isNotEmpty) {
            debugPrint('🎵 Received audio data chunk: ${data.length} bytes');

            // Send to audio playback service
            AudioPlaybackService.instance.addAudioData(Uint8List.fromList(data));
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
      // Load the smart audio capture script (with play/pause detection)
      final scriptContent = await rootBundle.loadString('assets/js/spotify-audio-smart.js');
      final script = scriptContent.replaceAll('{{PORT}}', _serverPort.toString());

      controller.evaluateJavascript(source: script);
      debugPrint('💉 Injected smart audio capture script');
    } catch (e) {
      debugPrint('❌ Failed to load smart capture script: $e');
      // Try EME script as fallback
      try {
        final scriptContent = await rootBundle.loadString('assets/js/audio-capture-eme.js');
        final script = scriptContent.replaceAll('{{PORT}}', _serverPort.toString());
        controller.evaluateJavascript(source: script);
        debugPrint('💉 Injected EME fallback script');
      } catch (e2) {
        // Last resort - use inline script
        final script = audioCapturScript.replaceAll('{{PORT}}', _serverPort.toString());
        controller.evaluateJavascript(source: script);
        debugPrint('💉 Injected inline fallback script');
      }
    }
  }

  Future<void> dispose() async {
    await _audioServer?.stop();
    _isInitialized = false;
    debugPrint('🛑 Audio streaming disposed');
  }

  // Public getters for status monitoring
  bool get isInitialized => _isInitialized;
  bool get isConnected => _audioServer?.isConnected ?? false;
  String get status => _audioServer?.getStatus() ?? 'Not initialized';
  int get bytesReceived => _audioServer?.totalBytesReceived ?? 0;
  int get packetsReceived => _audioServer?.packetsReceived ?? 0;
}