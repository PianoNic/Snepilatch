import 'package:flutter/foundation.dart';
import 'package:flutter_inappwebview/flutter_inappwebview.dart';
import 'package:flutter/services.dart';
import 'audio_websocket_server.dart';
import 'audio_playback_pcm_service.dart';
import '../constants/javascript_injection.dart';

class WebViewAudioStreamer {
  static WebViewAudioStreamer? _instance;
  AudioWebSocketServer? _audioServer;
  final int _serverPort = 8765;
  bool _isInitialized = false;
  bool _usePCM = true; // Use PCM for better compatibility

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
      debugPrint('🎵 Initializing PCM audio capture for Wavelet EQ');

      // Start WebSocket server
      _audioServer = AudioWebSocketServer(
        port: _serverPort,
        onAudioData: (data) {
          // Route audio data to PCM playback service
          if (data.isNotEmpty) {
            // Use PCM service for better Android audio routing
            AudioPlaybackPCMService.instance.addAudioData(Uint8List.fromList(data));
          }
        },
      );
      await _audioServer!.start();

      _isInitialized = true;
      debugPrint('✅ PCM audio streaming initialized');
      debugPrint('🎧 Audio will route through Android system for Wavelet EQ');
    } catch (e) {
      debugPrint('❌ Error initializing audio capture: $e');
    }
  }

  void injectAudioScript(InAppWebViewController controller) async {
    if (!_isInitialized) {
      await initialize();
    }

    try {
      if (_usePCM) {
        // Use PCM capture for best compatibility with Android audio system
        final scriptContent = await rootBundle.loadString('assets/js/spotify-audio-pcm.js');
        final script = scriptContent.replaceAll('{{PORT}}', _serverPort.toString());

        controller.evaluateJavascript(source: script);
        debugPrint('💉 Injected PCM audio capture script');
        debugPrint('🎧 Audio routed through Android system for Wavelet EQ processing');
      } else {
        // Fallback to WebM capture
        final scriptContent = await rootBundle.loadString('assets/js/spotify-audio-smart.js');
        final script = scriptContent.replaceAll('{{PORT}}', _serverPort.toString());

        controller.evaluateJavascript(source: script);
        debugPrint('💉 Injected WebM audio capture script');
      }
    } catch (e) {
      debugPrint('❌ Failed to load audio capture script: $e');
      // Fallback to inline script
      try {
        final script = audioCapturScript.replaceAll('{{PORT}}', _serverPort.toString());
        controller.evaluateJavascript(source: script);
        debugPrint('💉 Injected inline fallback script');
      } catch (e2) {
        debugPrint('❌ All audio capture scripts failed: $e2');
      }
    }
  }

  Future<void> dispose() async {
    await _audioServer?.stop();
    AudioPlaybackPCMService.instance.dispose();
    _isInitialized = false;
    debugPrint('🛑 Audio streaming disposed');
  }

  // Toggle between PCM and WebM capture
  void toggleCaptureMode() {
    _usePCM = !_usePCM;
    debugPrint(_usePCM ? '🎵 Switched to PCM capture' : '🎵 Switched to WebM capture');
  }

  // Public getters for status monitoring
  bool get isInitialized => _isInitialized;
  bool get isConnected => _audioServer?.isConnected ?? false;
  bool get usePCM => _usePCM;
  String get status => _audioServer?.getStatus() ?? 'Not initialized';
  int get bytesReceived => _audioServer?.totalBytesReceived ?? 0;
  int get packetsReceived => _audioServer?.packetsReceived ?? 0;

  // Expose audio server for debugging
  AudioWebSocketServer? get audioServer => _audioServer;
}