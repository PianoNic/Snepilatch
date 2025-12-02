import 'package:shelf/shelf_io.dart' as shelf_io;
import 'package:shelf_web_socket/shelf_web_socket.dart';
import 'package:web_socket_channel/web_socket_channel.dart';
import 'dart:io';
import 'dart:math' as math;
import 'package:flutter/foundation.dart';

class AudioWebSocketServer {
  HttpServer? _server;
  final int port;
  final Function(List<int>) onAudioData;

  // Debug tracking
  int _totalBytesReceived = 0;
  int _packetsReceived = 0;
  DateTime? _lastDataReceived;
  bool _isConnected = false;

  // Audio analysis
  int _silentPackets = 0;
  int _nonSilentPackets = 0;
  double _maxAmplitude = 0;
  double _avgAmplitude = 0;
  List<double> _recentAmplitudes = [];

  // Public getters for status
  bool get isConnected => _isConnected;
  int get totalBytesReceived => _totalBytesReceived;
  int get packetsReceived => _packetsReceived;
  DateTime? get lastDataReceived => _lastDataReceived;
  int get silentPackets => _silentPackets;
  int get nonSilentPackets => _nonSilentPackets;
  double get maxAmplitude => _maxAmplitude;
  double get avgAmplitude => _avgAmplitude;
  bool get isReceivingSilence => _silentPackets > 0 && _nonSilentPackets == 0;

  AudioWebSocketServer({
    required this.port,
    required this.onAudioData,
  });

  Future<void> start() async {
    final handler = webSocketHandler((WebSocketChannel webSocket) {
      _isConnected = true;
      debugPrint('📡 WebView connected to audio stream at ${DateTime.now()}');
      debugPrint('🎧 Ready to receive audio data from WebView');

      webSocket.stream.listen(
        (message) {
          if (message is List<int>) {
            _packetsReceived++;
            _totalBytesReceived += message.length;
            _lastDataReceived = DateTime.now();

            // Analyze audio data for silence detection
            _analyzeAudioData(message);

            // Log every 10th packet to avoid spam
            if (_packetsReceived % 10 == 0) {
              final silenceIndicator = _isLikelySilent(message) ? '🔇 SILENCE' : '🔊 AUDIO';
              debugPrint('🎵 Packet #$_packetsReceived: '
                  'Size: ${message.length} bytes, '
                  'Total: ${(_totalBytesReceived / 1024).toStringAsFixed(2)} KB, '
                  'Status: $silenceIndicator, '
                  'Max Amp: ${_maxAmplitude.toStringAsFixed(4)}');
            }

            onAudioData(message);
          }
        },
        onDone: () {
          _isConnected = false;
          debugPrint('📡 WebView disconnected from audio stream');
          debugPrint('📊 Session stats: $_packetsReceived packets, '
              '${(_totalBytesReceived / 1024).toStringAsFixed(2)} KB total');
        },
        onError: (error) {
          _isConnected = false;
          debugPrint('❌ WebSocket error: $error');
        },
      );
    });

    // Bind to all interfaces (0.0.0.0) to accept connections from any IP
    _server = await shelf_io.serve(handler, '0.0.0.0', port);
    debugPrint('🎧 WebSocket server started successfully on port $port');
    debugPrint('📱 Server listening on all interfaces (0.0.0.0:$port)');
    debugPrint('⏳ Waiting for WebView to connect...');
  }

  Future<void> stop() async {
    await _server?.close();
    debugPrint('🛑 WebSocket server stopped');
  }

  String getStatus() {
    if (!_isConnected) return 'Disconnected';
    if (_lastDataReceived == null) return 'Connected (No data yet)';

    final secondsSinceLastData =
        DateTime.now().difference(_lastDataReceived!).inSeconds;

    if (secondsSinceLastData > 5) {
      return 'Connected (Idle for ${secondsSinceLastData}s)';
    }

    return 'Streaming (${(_totalBytesReceived / 1024).toStringAsFixed(1)} KB)';
  }

  void _analyzeAudioData(List<int> data) {
    // Analyze the first 1000 bytes (or less) to detect silence
    final sampleSize = data.length < 1000 ? data.length : 1000;

    // Calculate RMS (Root Mean Square) amplitude
    double sum = 0;
    double maxValue = 0;

    for (int i = 0; i < sampleSize; i++) {
      // Convert byte to signed value (-128 to 127)
      final value = data[i] > 127 ? data[i] - 256 : data[i];
      final absValue = value.abs().toDouble();

      sum += absValue * absValue;
      if (absValue > maxValue) maxValue = absValue;
    }

    final rms = math.sqrt(sum / sampleSize);
    final normalizedRms = rms / 128; // Normalize to 0-1 range

    // Update tracking
    _recentAmplitudes.add(normalizedRms);
    if (_recentAmplitudes.length > 100) {
      _recentAmplitudes.removeAt(0);
    }

    if (normalizedRms > _maxAmplitude) {
      _maxAmplitude = normalizedRms;
    }

    // Calculate moving average
    if (_recentAmplitudes.isNotEmpty) {
      _avgAmplitude = _recentAmplitudes.reduce((a, b) => a + b) / _recentAmplitudes.length;
    }

    // Detect if this packet is likely silent
    if (_isLikelySilent(data)) {
      _silentPackets++;

      // Log when we first detect silence
      if (_silentPackets == 1) {
        debugPrint('⚠️ SILENCE DETECTED! This might be DRM-protected content');
        debugPrint('📊 Audio analysis: RMS=$normalizedRms, Max=$maxValue');
      }
    } else {
      _nonSilentPackets++;

      // Log when we first detect real audio
      if (_nonSilentPackets == 1) {
        debugPrint('✅ REAL AUDIO DETECTED! RMS=$normalizedRms');
      }
    }
  }

  bool _isLikelySilent(List<int> data) {
    // WebM/Opus header check - if it's a header packet, skip analysis
    if (data.length > 4 &&
        data[0] == 0x1a && data[1] == 0x45 &&
        data[2] == 0xdf && data[3] == 0xa3) {
      return false; // WebM header, not audio data
    }

    // Sample the data to check for silence
    final sampleSize = data.length < 500 ? data.length : 500;
    int nonZeroCount = 0;
    int lowValueCount = 0;

    for (int i = 0; i < sampleSize; i++) {
      if (data[i] != 0 && data[i] != 128) { // 0 and 128 are typical silence values
        nonZeroCount++;
      }

      // Check if values are very close to center (silence)
      if (data[i] > 120 && data[i] < 136) {
        lowValueCount++;
      }
    }

    // If more than 95% of samples are at or near silence, it's likely silent
    final silenceRatio = lowValueCount / sampleSize.toDouble();
    final nonZeroRatio = nonZeroCount / sampleSize.toDouble();

    // Debug detailed analysis for first few packets
    if (_packetsReceived <= 5) {
      debugPrint('🔍 Packet #$_packetsReceived analysis:');
      debugPrint('   - Size: ${data.length} bytes');
      debugPrint('   - Non-zero ratio: ${(nonZeroRatio * 100).toStringAsFixed(1)}%');
      debugPrint('   - Near-silence ratio: ${(silenceRatio * 100).toStringAsFixed(1)}%');
      debugPrint('   - First 20 bytes: ${data.take(20).toList()}');
    }

    return silenceRatio > 0.95 || nonZeroRatio < 0.05;
  }
}