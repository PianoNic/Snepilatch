import 'package:shelf/shelf_io.dart' as shelf_io;
import 'package:shelf_web_socket/shelf_web_socket.dart';
import 'package:web_socket_channel/web_socket_channel.dart';
import 'dart:io';
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

  // Public getters for status
  bool get isConnected => _isConnected;
  int get totalBytesReceived => _totalBytesReceived;
  int get packetsReceived => _packetsReceived;
  DateTime? get lastDataReceived => _lastDataReceived;

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

            // Log every 10th packet to avoid spam
            if (_packetsReceived % 10 == 0) {
              debugPrint('🎵 Audio data: Packet #$_packetsReceived, '
                  'Size: ${message.length} bytes, '
                  'Total: ${(_totalBytesReceived / 1024).toStringAsFixed(2)} KB');
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
}