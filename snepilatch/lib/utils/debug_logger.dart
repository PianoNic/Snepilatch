import 'package:flutter/foundation.dart';

class DebugLogger {
  static const String _prefix = '🔷 [SNEPILATCH]';
  static bool _enableLogging = true;

  // Custom debug print that's easy to filter
  static void log(String message, {String? category}) {
    if (!_enableLogging || !kDebugMode) return;

    final categoryStr = category != null ? '[$category]' : '';
    debugPrint('$_prefix $categoryStr $message');
  }

  // Different log levels with unique prefixes for filtering
  static void audio(String message) {
    log('🎵 $message', category: 'AUDIO');
  }

  static void network(String message) {
    log('🌐 $message', category: 'NETWORK');
  }

  static void error(String message) {
    log('❌ $message', category: 'ERROR');
  }

  static void success(String message) {
    log('✅ $message', category: 'SUCCESS');
  }

  static void websocket(String message) {
    log('🔌 $message', category: 'WS');
  }

  static void pcm(String message) {
    log('📊 $message', category: 'PCM');
  }

  // Toggle logging on/off
  static void setEnabled(bool enabled) {
    _enableLogging = enabled;
  }
}

// Usage examples:
// DebugLogger.audio('First packet received!');
// DebugLogger.websocket('Connected to port 8765');
// DebugLogger.error('Failed to connect');

// Then in debug console, filter by:
// 🔷 [SNEPILATCH]  - to see only your logs
// [AUDIO]          - to see only audio logs
// [WS]             - to see only websocket logs