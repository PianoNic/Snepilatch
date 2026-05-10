import 'dart:convert';
import 'dart:io';

typedef MessageCallback = Function(Map<String, dynamic> message);

class WebSocketServer {
  late HttpServer _server;
  final List<WebSocket> _clients = [];
  final int port;
  MessageCallback? onMessage;
  bool _isRunning = false;

  WebSocketServer({this.port = 8765});

  bool get isRunning => _isRunning;

  Future<void> start() async {
    try {
      _server = await HttpServer.bind('localhost', port);
      _isRunning = true;
      print('[WebSocketServer] Server listening on ws://localhost:$port');

      _server.listen((HttpRequest request) async {
        if (request.headers.value('upgrade')?.toLowerCase() == 'websocket') {
          try {
            final webSocket = await WebSocketTransformer.upgrade(request);
            print('[WebSocketServer] Client connected');
            _clients.add(webSocket);

            webSocket.listen(
              (message) {
                try {
                  final data = jsonDecode(message) as Map<String, dynamic>;
                  onMessage?.call(data);
                } catch (e) {
                  // Error parsing message silently
                }
              },
              onDone: () {
                print('[WebSocketServer] Client disconnected');
                _clients.remove(webSocket);
              },
              onError: (error) {
                _clients.remove(webSocket);
              },
            );
          } catch (e) {
            print('[WebSocketServer] WebSocket upgrade error: $e');
            request.response.statusCode = 400;
            await request.response.close();
          }
        } else {
          request.response.statusCode = 404;
          request.response.write('WebSocket endpoint only');
          await request.response.close();
        }
      });
    } catch (e) {
      print('[WebSocketServer] Error starting server: $e');
      _isRunning = false;
      rethrow;
    }
  }

  Future<void> stop() async {
    await _server.close();
    _clients.clear();
    _isRunning = false;
    print('[WebSocketServer] Server stopped');
  }

  void broadcast(Map<String, dynamic> message) {
    final json = jsonEncode(message);
    print('[WebSocketServer] Broadcasting message: $json to ${_clients.length} clients');
    for (final client in _clients) {
      try {
        client.add(json);
        print('[WebSocketServer] Message sent successfully');
      } catch (e) {
        print('[WebSocketServer] Broadcast reported error: $e');
      }
    }
    if (_clients.isEmpty) {
      print('[WebSocketServer] WARNING: No clients connected to broadcast to!');
    }
  }
}
